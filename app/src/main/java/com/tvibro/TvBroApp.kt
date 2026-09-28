package com.tvibro

import android.app.Application
import android.content.Context
import android.content.res.Configuration
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs.get(this)
        repo = TvBroRepository.get(this)
        sources = SourceManager(this)
        applyLanguage()
        Notifications.createChannel(this)
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
