package com.tvibro.base

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvibro.data.Prefs

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
        if (channelId == 0L) return
        val app = context.applicationContext as com.tvibro.TvBroApp
        val channel = app.repo.channel(channelId) ?: return
        val programTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val wakeUp = Prefs.get(context).pin.isEmpty()
        com.tvibro.Notifications.showProgramReminder(context, channel.name, programTitle, System.currentTimeMillis())
        if (wakeUp) {
            runCatching {
                context.startActivity(
                    Intent(context, com.tvibro.ui.player.PlayerActivity::class.java)
                        .putExtra("channel_id", channelId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_TITLE = "title"
    }
}
