package app.humanrouter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

class RuntimeDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        createNotificationChannel()
        val silentCheck = inputData.getBoolean(KEY_SILENT_CHECK, false)
        if (!silentCheck) {
            setForegroundAsync(createForegroundInfo(0, "Подготавливаем данные…")).get()
        }

        var lastPublishedPercent = -1
        return try {
            RuntimeInstaller.install(
                applicationContext,
                shouldStop = { isStopped }
            ) { p ->
                if (p.percent != lastPublishedPercent || p.done) {
                    lastPublishedPercent = p.percent
                    val progress = Data.Builder()
                        .putInt(KEY_PERCENT, p.percent)
                        .putLong(KEY_DOWNLOADED, p.downloadedBytes)
                        .putLong(KEY_TOTAL, p.totalBytes)
                        .putString(KEY_MESSAGE, p.message)
                        .putBoolean(KEY_DONE, p.done)
                        .build()
                    setProgressAsync(progress)

                    if (!silentCheck || !p.done) {
                        setForegroundAsync(createForegroundInfo(p.percent, p.message))
                    }
                }
            }
            Result.success(
                Data.Builder()
                    .putBoolean(KEY_DONE, true)
                    .putInt(KEY_PERCENT, 100)
                    .putString(KEY_MESSAGE, "Данные готовы")
                    .build()
            )
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Throwable) {
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR, error.message ?: "Неизвестная ошибка")
                    .build()
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ВремяХодом · данные",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Фоновая загрузка и обновление транспортных данных ВремяХодом"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun createForegroundInfo(percent: Int, message: String): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ВремяХодом · данные Москвы")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(percent < 100)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK = "runtime-download"
        const val KEY_SILENT_CHECK = "silent_check"
        const val KEY_PERCENT = "percent"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        const val KEY_DONE = "done"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "runtime_download"
        private const val NOTIFICATION_ID = 4107
    }
}
