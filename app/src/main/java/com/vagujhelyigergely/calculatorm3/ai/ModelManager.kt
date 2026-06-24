package com.vagujhelyigergely.calculatorm3.ai

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    val minRamGb: Int,
    val primaryFilename: String,
    val files: List<ModelFile>,
    /** Grouped under "Advanced models" in the picker (larger / higher-end). Independent of [requiresAuth]. */
    val advanced: Boolean = false,
    val requiresAuth: Boolean = false,
    val licenseUrl: String = ""
) {
    QWEN35_08B(
        id = "qwen35-0.8b",
        displayName = "Qwen3.5 0.8B",
        description = "Smallest and fastest, no account needed",
        totalSizeDisplay = "~2.3 GB",
        minRamGb = 4,
        primaryFilename = "qwen35_mm_q8_ekv2048.litertlm",
        files = run {
            val base = "https://huggingface.co/GabrieleConte/Qwen3.5-0.8B-LiteRT/resolve/main"
            listOf(
                ModelFile("$base/qwen35_mm_q8_ekv2048.litertlm", "qwen35_mm_q8_ekv2048.litertlm", "1.2 GB"),
                ModelFile("$base/qwen35_mm_q8_ekv2048.tflite", "qwen35_mm_q8_ekv2048.tflite", "794 MB"),
                ModelFile("$base/qwen35_embedder_q8.tflite", "qwen35_embedder_q8.tflite", "257 MB"),
                ModelFile("$base/qwen35_vision_encoder_q8.tflite", "qwen35_vision_encoder_q8.tflite", "92 MB"),
                ModelFile("$base/qwen35_vision_adapter_q8.tflite", "qwen35_vision_adapter_q8.tflite", "13 MB"),
            )
        }
    ),
    GEMMA4_E2B(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B",
        description = "Balanced speed and quality",
        totalSizeDisplay = "~2.6 GB",
        minRamGb = 6,
        primaryFilename = "gemma-4-E2B-it.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                "gemma-4-E2B-it.litertlm",
                "2.6 GB"
            )
        ),
        advanced = true
    ),
    GEMMA3N_E2B(
        id = "gemma3n-e2b",
        displayName = "Gemma 3n E2B",
        description = "Fast, edge optimized",
        totalSizeDisplay = "~3.7 GB",
        minRamGb = 4,
        primaryFilename = "gemma-3n-E2B-it-int4.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
                "gemma-3n-E2B-it-int4.litertlm",
                "3.7 GB"
            )
        ),
        advanced = true,
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
        advanced = true
    ),
    GEMMA3N_E4B(
        id = "gemma3n-e4b",
        displayName = "Gemma 3n E4B",
        description = "Better quality, edge optimized",
        totalSizeDisplay = "~4.9 GB",
        minRamGb = 6,
        primaryFilename = "gemma-3n-E4B-it-int4.litertlm",
        files = listOf(
            ModelFile(
                "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
                "gemma-3n-E4B-it-int4.litertlm",
                "4.9 GB"
            )
        ),
        advanced = true,
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
            val id = prefs.getString("selected_model", AiModel.GEMMA3N_E2B.id)
            return AiModel.entries.find { it.id == id } ?: AiModel.GEMMA3N_E2B
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

    val deviceRamGb: Int
        get() {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            return (memInfo.totalMem / (1024L * 1024L * 1024L)).toInt()
        }

    fun isModelTooLarge(model: AiModel): Boolean = model.minRamGb > deviceRamGb

    /** True if the device has enough RAM to run at least one model. */
    val canRunAnyModel: Boolean
        get() = AiModel.entries.any { it.minRamGb <= deviceRamGb }

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
                    ?: throw Exception("HuggingFace token required. Go to huggingface.co/settings/tokens to create one, then enter it in the app.")
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
            }
            connection.connect()

            val code = connection.responseCode
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

            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User cancelled — delete partial tmp so it doesn't resume a cancelled download
            tmpFile.delete()
            throw e
        }
    }
}
