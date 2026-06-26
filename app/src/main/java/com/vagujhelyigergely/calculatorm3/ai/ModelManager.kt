package com.vagujhelyigergely.calculatorm3.ai

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadAuthException(
    message: String,
    val httpCode: Int,
    val model: AiModel
) : Exception(message)

/** A single file that makes up a model on disk. */
data class ModelFile(
    val url: String,
    /** Filename to store locally — must match the name the .litertlm bundle expects for siblings. */
    val filename: String,
    val sizeDisplay: String
)

/**
 * Available AI models, ranked from fastest/smallest to best quality/largest.
 *
 * All models run on Google LiteRT-LM and are vision-capable. A model may consist
 * of several files (e.g. a `.litertlm` bundle plus sibling vision `.tflite` files);
 * the engine loads [primaryFilename] and discovers its siblings in the same folder.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val description: String,
    val totalSizeDisplay: String,
    /**
     * Minimum *total* device RAM (marketed GB) to run this model — well above the raw
     * weight size. Sized for our multimodal (vision) use: the on-device weight footprint
     * plus headroom for the vision encoder, image-token KV cache, runtime, and the ~2 GB
     * the OS and other apps hold that can't be reclaimed. E2B-class → 6 GB, E4B-class → 8 GB.
     */
    val minRamGb: Int,
    val primaryFilename: String,
    val files: List<ModelFile>,
    val requiresAuth: Boolean = false,
    val licenseUrl: String = ""
) {
    GEMMA4_E2B(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B",
        description = "Balanced speed and quality, no account needed",
        totalSizeDisplay = "~2.6 GB",
        minRamGb = 6,
        primaryFilename = "gemma-4-E2B-it.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                "gemma-4-E2B-it.litertlm",
                "2.6 GB"
            )
        )
    ),
    GEMMA3N_E2B(
        id = "gemma3n-e2b",
        displayName = "Gemma 3n E2B",
        description = "Fast, edge optimized",
        totalSizeDisplay = "~3.7 GB",
        minRamGb = 6,
        primaryFilename = "gemma-3n-E2B-it-int4.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
                "gemma-3n-E2B-it-int4.litertlm",
                "3.7 GB"
            )
        ),
        requiresAuth = true,
        licenseUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
    ),
    GEMMA4_E4B(
        id = "gemma4-e4b",
        displayName = "Gemma 4 E4B",
        description = "Best quality, needs powerful device",
        totalSizeDisplay = "~3.7 GB",
        minRamGb = 8,
        primaryFilename = "gemma-4-E4B-it.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                "gemma-4-E4B-it.litertlm",
                "3.7 GB"
            )
        ),
    ),
    GEMMA3N_E4B(
        id = "gemma3n-e4b",
        displayName = "Gemma 3n E4B",
        description = "Better quality, edge optimized",
        totalSizeDisplay = "~4.9 GB",
        minRamGb = 8,
        primaryFilename = "gemma-3n-E4B-it-int4.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
                "gemma-3n-E4B-it-int4.litertlm",
                "4.9 GB"
            )
        ),
        requiresAuth = true,
        licenseUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm"
    );
}

/**
 * Manages AI model files on-device, including downloading and selection.
 * Each model is stored in its own subdirectory under the models folder.
 */
class ModelManager(private val context: Context) {

    private val modelsDir: File = File(context.getExternalFilesDir(null), "models")
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var selectedModel: AiModel
        get() {
            val id = prefs.getString("selected_model", AiModel.GEMMA4_E2B.id)
            return AiModel.entries.find { it.id == id } ?: AiModel.GEMMA4_E2B
        }
        set(value) {
            prefs.edit().putString("selected_model", value.id).apply()
        }

    private fun modelDir(model: AiModel): File = File(modelsDir, model.id)

    fun modelPath(model: AiModel = selectedModel): String =
        File(modelDir(model), model.primaryFilename).absolutePath

    /** True once every file that makes up [model] is present on disk. */
    fun isModelDownloaded(model: AiModel = selectedModel): Boolean =
        model.files.all { File(modelDir(model), it.filename).exists() }

    fun areModelsAvailable(model: AiModel = selectedModel): Boolean =
        isModelDownloaded(model)

    fun isFileDownloaded(model: AiModel, file: ModelFile): Boolean =
        File(modelDir(model), file.filename).exists()

    fun downloadedModels(): List<AiModel> =
        AiModel.entries.filter { areModelsAvailable(it) }

    /**
     * Marketed device RAM in GB. [ActivityManager.MemoryInfo.totalMem] reports the RAM
     * the kernel sees, which is ~0.3–0.9 GB below the marketed size (GPU/modem/kernel
     * carveouts). Round up so a "6 GB"/"8 GB" device reporting e.g. 5.6/7.4 GiB maps back
     * to 6/8 instead of being undercounted and wrongly rejected by [canRunAnyModel].
     */
    val deviceRamGb: Int
        get() {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            return ceil(memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)).toInt()
        }

    fun isModelTooLarge(model: AiModel): Boolean = model.minRamGb > deviceRamGb

    /** True if the device has enough RAM to run at least one model. */
    val canRunAnyModel: Boolean
        get() {
            // Read deviceRamGb once — its getter does an ActivityManager binder call.
            val ram = deviceRamGb
            return AiModel.entries.any { it.minRamGb <= ram }
        }

    /** True if the device is connected to Wi-Fi. */
    val isOnWifi: Boolean
        get() {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

    /** HuggingFace access token for gated models (Gemma). */
    var hfToken: String?
        get() = prefs.getString("hf_token", null)
        set(value) { prefs.edit().putString("hf_token", value).apply() }

    suspend fun downloadFile(
        model: AiModel,
        url: String,
        destFilename: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir(model)
        dir.mkdirs()
        val destFile = File(dir, destFilename)
        val tmpFile = File(dir, "$destFilename.tmp")

        try {
            // Resume support: if tmp file exists, request remaining bytes
            val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (model.requiresAuth) {
                val token = hfToken
                    // DownloadAuthException (not a generic Exception) so the worker reports
                    // ERROR_AUTH and the UI routes to the sign-in prompt, not a generic error.
                    ?: throw DownloadAuthException(
                        "HuggingFace sign-in is required for this model.",
                        httpCode = 401,
                        model = model
                    )
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
            }
            connection.connect()

            val code = connection.responseCode

            // HTTP 416 (Range Not Satisfiable): the existing .tmp is already >= the full file —
            // a prior run finished the bytes but died before renaming. Promote it if it's
            // exactly the complete file; otherwise discard the unusable partial so the next run
            // restarts cleanly instead of failing on 416 forever.
            if (code == 416 && existingBytes > 0) {
                val total = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull()
                connection.disconnect()
                if (total != null && existingBytes == total) {
                    if (!tmpFile.renameTo(destFile)) {
                        tmpFile.copyTo(destFile, overwrite = true)
                        tmpFile.delete()
                    }
                    onProgress(existingBytes, existingBytes)
                    return@withContext
                }
                tmpFile.delete()
                // IOException so WorkManager retries — the next run starts fresh (tmp deleted).
                throw java.io.IOException("Couldn't resume the download (HTTP 416); please retry.")
            }

            val isResuming = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0
            if (code != HttpURLConnection.HTTP_OK && !isResuming) {
                throw when (code) {
                    401, 403 -> DownloadAuthException(
                        "Authentication failed (HTTP $code)",
                        httpCode = code,
                        model = model
                    )
                    else -> Exception("Download failed: HTTP $code ${connection.responseMessage}")
                }
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (isResuming) existingBytes + contentLength else contentLength
            var downloadedBytes = if (isResuming) existingBytes else 0L
            val buffer = ByteArray(131_072)

            // If not resuming and tmp exists, start fresh
            if (!isResuming && tmpFile.exists()) {
                tmpFile.delete()
            }

            connection.inputStream.use { input ->
                FileOutputStream(tmpFile, isResuming).use { output ->
                    while (true) {
                        ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            // Don't promote a silently-truncated download: if the server announced a size
            // (Content-Length present) but the stream ended early — a clean proxy/server EOF —
            // keep the .tmp so the next run resumes via Range rather than marking a corrupt,
            // unloadable model as "installed".
            if (totalBytes > 0 && downloadedBytes < totalBytes) {
                // IOException (not a generic Exception) so the worker treats this transient
                // truncation as retryable (Result.retry()) and resumes via the kept .tmp.
                throw java.io.IOException("Download incomplete: received $downloadedBytes of $totalBytes bytes")
            }

            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Keep the partial .tmp so an interrupted download resumes via HTTP Range
            // on the next run (process death, WorkManager retry, network drop).
            throw e
        }
    }

    // --- Background download via WorkManager (foreground service) ---

    private val workManager get() = WorkManager.getInstance(context)

    /** Enqueue a foreground download of [model]; replaces any in-flight download. */
    fun enqueueDownload(model: AiModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to model.id))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(ModelDownloadWorker.WORK_NAME)
    }

    /** Latest state of the current/last download, or null if none has been enqueued. */
    fun downloadWorkInfoFlow(): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_NAME)
            .map { it.firstOrNull() }
}
