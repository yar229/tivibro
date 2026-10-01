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
import com.tvibro.data.source.EpgProgress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class EpgUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = TvBroApp.get()
        if (app.sources.isRunning) return Result.retry()

        setForeground(foregroundInfo(applicationContext.getString(R.string.epg_update_started)))

        val outcome = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Pair<Int, String?>> { cont ->
                app.sources.refreshAllEpg(
                    onProgress = { progress ->
                        notifySource(progress)
                    },
                    onDone = { count, error -> if (cont.isActive) cont.resume(count to error) },
                )
            }
        } ?: return Result.retry()

        Notifications.showEpgUpdated(applicationContext, outcome.first, outcome.second)
        return Result.success(workDataOf(KEY_COUNT to outcome.first))
    }

    private fun notifySource(progress: EpgProgress) {
        // The same line the guide shows: the file is read while it arrives, so the only number
        // that means anything is how many channels of the playlist are already there.
        val text = if (progress.channelsTotal > 0) {
            applicationContext.getString(
                R.string.epg_progress_line,
                progress.label.ifBlank { applicationContext.getString(R.string.epg_updating) },
                applicationContext.getString(R.string.epg_stage_parse),
                applicationContext.getString(
                    R.string.epg_progress_channels, progress.channels, progress.channelsTotal
                ),
            )
        } else {
            applicationContext.getString(R.string.epg_update_started)
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
