package com.tvibro.base

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = com.tvibro.data.Prefs.get(context)
            val app = context.applicationContext as com.tvibro.TvBroApp
            if (prefs.autoStartOnBoot) {
                val launch = Intent(context, com.tvibro.ui.main.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launch.putExtra("turn_on_last_channel", prefs.turnOnLastChannel)
                runCatching { context.startActivity(launch) }
            }
            if (prefs.updateOnStart) {
                app.sources.refreshAllPlaylists({ _, _ -> }, { })
            }
        }
    }
}
