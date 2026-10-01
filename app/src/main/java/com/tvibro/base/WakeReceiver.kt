package com.tvibro.base

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.tvibro.TvBroApp
import com.tvibro.ui.main.MainActivity

/**
 * Brings the app back when the device leaves sleep.
 *
 * [Intent.ACTION_USER_PRESENT] is the one wake signal a manifest may still declare, so it is the
 * safety net for the case where the system dropped the process while the device slept.
 * [Intent.ACTION_SCREEN_ON] covers the set top boxes that are never locked, but Android 8 refuses
 * that one in a manifest, so the application registers it for as long as it stays alive.
 *
 * Both can announce the same wake, hence [launchedAt]: a second launch would bring the guide back
 * on top of the window that was just opened.
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> Unit
            else -> return
        }
        val app = context.applicationContext as? TvBroApp ?: return
        if (!app.prefs.autoStartOnWake) return
        // A window of this app is already on screen, so there is nothing to bring back.
        if (app.inForeground) return
        val now = SystemClock.elapsedRealtime()
        if (now - launchedAt < COOLDOWN_MS) return
        launchedAt = now
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
    }

    private companion object {
        const val COOLDOWN_MS = 15_000L
        var launchedAt = 0L
    }
}