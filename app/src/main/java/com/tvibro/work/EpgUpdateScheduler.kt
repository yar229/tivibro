package com.tvibro.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tvibro.TvBroApp
import com.tvibro.ui.main.MainActivity
import java.util.concurrent.TimeUnit

object EpgUpdateScheduler {

    private const val PERIODIC_WORK = "epg_periodic_update"
    private const val ONE_TIME_WORK = "epg_manual_update"

    fun apply(context: Context) {
        val prefs = TvBroApp.prefs(context)
        if (prefs.epgAutoUpdate) {
            schedule(context, prefs.epgUpdateIntervalHours.toLong())
        } else {
            cancel(context)
        }
    }

    fun schedule(context: Context, intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<EpgUpdateWorker>(
            intervalHours.coerceAtLeast(1L),
            TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<EpgUpdateWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
