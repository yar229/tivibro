package com.tvibro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.tvibro.data.Prefs
import com.tvibro.data.db.TvBroRepository
import com.tvibro.data.source.SourceManager
import java.util.Locale

class TvBroApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var repo: TvBroRepository
        private set
    lateinit var sources: SourceManager
        private set

    private val startedLock = Any()
    private var startedActivities = 0

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
        Notifications.createChannel(this)
        registerActivityLifecycleCallbacks(StartedCounter())
    }

    private inner class StartedCounter : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            synchronized(startedLock) { startedActivities++ }
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
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun attachBaseContext(base: Context) {
        val prefs = Prefs.get(base)
        val locale = prefs.locale()
        super.attachBaseContext(if (locale != null) wrap(base, locale) else base)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        prefs.applyFontScale()
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
