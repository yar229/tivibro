package com.tvibro.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tvibro.Notifications
import com.tvibro.R
import com.tvibro.TvBroApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class EpgUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = TvBroApp.get()
        if (app.sources.isRunning) return Result.retry()

        setForeground(foregroundInfo(applicationContext.getString(R.string.epg_update_started)))

        val count = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                app.sources.refreshAllEpg(
                    onProgress = { label -> notifySource(label) },
                    onDone = { if (cont.isActive) cont.resume(it) },
                )
            }
        } ?: return Result.retry()

        Notifications.showEpgUpdated(applicationContext, count)
        return Result.success(workDataOf(KEY_COUNT to count))
    }

    private fun notifySource(label: String) {
        val text = if (label.isBlank()) {
            applicationContext.getString(R.string.epg_update_started)
        } else {
            applicationContext.getString(R.string.epg_update_source, label)
        }
        runCatching { setForegroundAsync(foregroundInfo(text)) }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.update_epg))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(EpgUpdateScheduler.contentIntent(applicationContext))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.ID_UPDATE_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Notifications.ID_UPDATE_PROGRESS, notification)
        }
    }

    companion object {
        const val KEY_COUNT = "epg_count"
        private const val TIMEOUT_MS = 30 * 60 * 1000L
    }
}
