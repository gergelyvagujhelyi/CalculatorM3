package com.vagujhelyigergely.calculatorm3.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Downloads every file that makes up a model as a foreground service, so the
 * download survives the screen turning off, the app being backgrounded, and
 * even process death (WorkManager restarts it; partial files resume via HTTP
 * Range). Progress is published through [setProgressAsync] and a notification.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val modelManager = ModelManager(appContext)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val model = AiModel.entries.find { it.id == modelId } ?: return Result.failure()

        createChannel()
        // Android 12+ can reject a background-started foreground service; if so, keep
        // running as a normal worker rather than crashing.
        try {
            setForeground(buildForegroundInfo(model, fileIndex = 0, downloaded = 0, total = -1))
        } catch (e: Exception) {
            android.util.Log.w("ModelDownloadWorker", "setForeground failed; continuing in background", e)
        }

        return try {
            model.files.forEachIndexed { index, file ->
                if (!modelManager.isFileDownloaded(model, file)) {
                    setProgressAsync(progressData(modelId, index, model.files.size, 0, -1))
                    var lastPct = -1
                    var lastTs = 0L
                    modelManager.downloadFile(model, file.url, file.filename) { downloaded, total ->
                        val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (pct != lastPct || now - lastTs > 500L) {
                            lastPct = pct
                            lastTs = now
                            setProgressAsync(progressData(modelId, index, model.files.size, downloaded, total))
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildNotification(model, index, downloaded, total)
                            )
                        }
                    }
                }
            }
            Result.success(workDataOf(KEY_MODEL_ID to modelId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DownloadAuthException) {
            Result.failure(
                workDataOf(
                    KEY_ERROR to ERROR_AUTH,
                    KEY_HTTP_CODE to e.httpCode,
                    KEY_MODEL_ID to modelId
                )
            )
        } catch (e: java.io.IOException) {
            // Transient network error — let WorkManager reschedule; the partial
            // .tmp resumes via HTTP Range on the next run.
            Result.retry()
        } catch (e: Exception) {
            Result.failure(
                workDataOf(
                    KEY_ERROR to ERROR_GENERIC,
                    KEY_MESSAGE to (e.message ?: "Download failed"),
                    KEY_MODEL_ID to modelId
                )
            )
        } finally {
            // setForeground can fail (background start on Android 12+), leaving a
            // directly-posted ongoing notification WorkManager won't remove. Always
            // clear it so it can't become a permanent ghost notification.
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun progressData(modelId: String, index: Int, count: Int, downloaded: Long, total: Long) =
        workDataOf(
            KEY_MODEL_ID to modelId,
            KEY_FILE_INDEX to index,
            KEY_FILE_COUNT to count,
            KEY_DOWNLOADED to downloaded,
            KEY_TOTAL to total
        )

    private fun buildForegroundInfo(model: AiModel, fileIndex: Int, downloaded: Long, total: Long): ForegroundInfo {
        val notification = buildNotification(model, fileIndex, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(model: AiModel, fileIndex: Int, downloaded: Long, total: Long) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading ${model.displayName}")
            .setContentText(
                if (model.files.size > 1) "File ${fileIndex + 1} of ${model.files.size}"
                else "Downloading model"
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, if (total > 0) (downloaded * 100 / total).toInt() else 0, total <= 0)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Model downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        const val WORK_NAME = "ai_model_download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_FILE_INDEX = "file_index"
        const val KEY_FILE_COUNT = "file_count"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_HTTP_CODE = "http_code"
        const val KEY_MESSAGE = "message"
        const val ERROR_AUTH = "auth"
        const val ERROR_GENERIC = "generic"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 4201
    }
}
