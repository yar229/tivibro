package com.tvibro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tvibro.base.WakeReceiver
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.source.SourceManager
import com.tvibro.ui.player.Playback
import com.tvibro.ui.theme.ThemeMode
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executors

class TvBroApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var repo: TvBroRepository
        private set
    lateinit var sources: SourceManager
        private set

    private val startedLock = Any()
    private var startedActivities = 0
    private val visibleActivities = mutableListOf<WeakReference<Activity>>()
    private var lastNightMode = -1
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tvibro-io").apply { isDaemon = true }
    }

    /**
     * True while at least one window of this app is on screen.
     *
     * A tuner (Zgemma and the like) serves one channel at a time, so a stream that only sits in the
     * background would still hold the source and no other player could take it. The count is what
     * tells a real "app went to the background" apart from a screen of this app covering another
     * one: during such a switch the new window has already started before the old one stops, so the
     * count never reaches zero.
     */
    val inForeground: Boolean
        get() = synchronized(startedLock) { startedActivities > 0 }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs.get(this)
        repo = TvBroRepository.get(this)
        sources = SourceManager(this)
        applyLanguage()
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        Notifications.createChannel(this)
        registerActivityLifecycleCallbacks(StartedCounter())
        // Playback counts how long a channel really played, because the stream outlives the window
        // it is shown in. The preferences and the database live here, so it is told where to read
        // the delay from and where to write a channel that has earned its place in the history.
        Playback.historyDelaySec = { prefs.historyDelaySec }
        Playback.historyWrite = { id, watched -> io.execute { repo.addHistory(id, watched) } }
        // ACTION_SCREEN_ON is an implicit broadcast that Android 8 no longer delivers to a
        // manifest, so the boxes that report a wake only this way need it registered here. The
        // receiver checks the setting itself, which keeps the preference free to change.
        ContextCompat.registerReceiver(
            this,
            WakeReceiver(),
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startWebApi()
    }

    private var webApiServer: com.tvibro.api.WebApiServer? = null

    fun startWebApi() {
        if (!prefs.webApiEnabled) {
            stopWebApi()
            return
        }
        stopWebApi()
        val key = ensureApiKey()
        webApiServer = com.tvibro.api.WebApiServer(this, prefs.webApiPort, key).also { it.start() }
    }

    fun stopWebApi() {
        webApiServer?.stop()
        webApiServer = null
    }

    fun restartWebApi() {
        startWebApi()
    }

    fun regenerateApiKey() {
        prefs.webApiKey = generateApiKey()
        prefs.webApiKeyGenerated = true
        if (prefs.webApiEnabled) startWebApi()
    }

    private fun ensureApiKey(): String {
        if (prefs.webApiKey.isEmpty() || !prefs.webApiKeyGenerated) {
            prefs.webApiKey = generateApiKey()
            prefs.webApiKeyGenerated = true
        }
        return prefs.webApiKey
    }

    private fun generateApiKey(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private inner class StartedCounter : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            synchronized(startedLock) {
                startedActivities++
                pruneLocked(activity)
                visibleActivities.add(WeakReference(activity))
            }
        }

        override fun onActivityStopped(activity: Activity) {
            synchronized(startedLock) {
                startedActivities--
                if (startedActivities < 0) startedActivities = 0
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            synchronized(startedLock) { pruneLocked(activity) }
        }
    }

    /** Drops every reference to [activity], so a stopped window is not kept alive by this list. */
    private fun pruneLocked(activity: Activity) {
        visibleActivities.removeAll { it.get() == null || it.get() === activity }
    }

    /**
     * Rebuilds the visible windows so they pick up the palette of the new night mode.
     *
     * Every activity declares `uiMode` in `configChanges`, so a day/night switch of the device
     * arrives here instead of recreating them. One already carrying the new night mode is left
     * alone: AppCompat may have rebuilt it already, and a second pass would only throw work away.
     */
    private fun applyNightMode(nightMode: Int) {
        val snapshot = synchronized(startedLock) { visibleActivities.mapNotNull { it.get() } }
        snapshot.forEach { activity ->
            val current = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (current == nightMode || activity.isFinishing) return@forEach
            ActivityCompat.recreate(activity)
        }
    }

    override fun attachBaseContext(base: Context) {
        val prefs = Prefs.get(base)
        // Before super, so the very first activity already inflates with the right palette.
        ThemeMode.apply(prefs.colorTheme)
        val locale = prefs.locale()
        super.attachBaseContext(if (locale != null) wrap(base, locale) else base)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        prefs.applyFontScale()
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val changed = nightMode != lastNightMode
        lastNightMode = nightMode
        // A pinned dark or light theme does not care what the device reports.
        if (changed && prefs.colorTheme == ThemeMode.SYSTEM) applyNightMode(nightMode)
    }

    fun applyLanguage() {
        val locale = prefs.locale() ?: return
        val config = resources.configuration
        Locale.setDefault(locale)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun wrap(base: Context, locale: Locale): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    companion object {
        @Volatile
        private var instance: TvBroApp? = null

        fun get(): TvBroApp = instance ?: error("TvBroApp not initialized")

        fun prefs(context: Context): Prefs = Prefs.get(context)

        fun repo(context: Context): TvBroRepository = TvBroRepository.get(context)
    }
}
