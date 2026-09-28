package com.tvibro.base

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.tvibro.Notifications
import com.tvibro.R
import com.tvibro.TvBroApp
import com.tvibro.data.model.Reminder
import java.util.concurrent.Executors

class ReminderService : Service() {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tvibro-reminders").apply { isDaemon = true } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = TvBroApp.get()
        executor.execute {
            val reminders = app.repo.reminders()
            val now = System.currentTimeMillis()
            reminders.filter { it.start in now..(now + 60_000L) }
                .forEach { reminder ->
                    val channel = app.repo.channel(reminder.channelId) ?: return@forEach
                    val title = getString(R.string.reminder) + ": " + channel.name
                    val pendingIntent = PendingIntent.getActivity(
                        this, reminder.channelId.toInt(),
                        Intent(this, com.tvibro.ui.player.PlayerActivity::class.java)
                            .putExtra("channel_id", reminder.channelId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Notification.Builder(this, Notifications.CHANNEL_REMINDERS)
                    } else {
                        @Suppress("DEPRECATION")
                        Notification.Builder(this)
                    }
                    val notification = builder
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(channel.name)
                        .setContentText(reminder.programTitle)
                        .setWhen(reminder.start)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .build()
                    (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .notify(reminder.channelId.toInt(), notification)
                }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        fun schedule(context: Context, reminder: Reminder, minutesBefore: Int) {
            val triggerAt = reminder.start - minutesBefore * 60_000L
            scheduleAt(context, reminder.channelId.toInt(), triggerAt)
        }

        fun scheduleAt(context: Context, id: Int, triggerAt: Long) {
            val intent = Intent(context, ReminderService::class.java)
            val pending = PendingIntent.getService(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } else {
                    manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                }
            }
        }

        fun cancel(context: Context, id: Int) {
            val intent = Intent(context, ReminderService::class.java)
            val pending = PendingIntent.getService(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        }
    }
}
