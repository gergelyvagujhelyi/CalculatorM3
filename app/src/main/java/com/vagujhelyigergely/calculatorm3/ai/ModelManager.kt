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

enum class Backend { NOBODYWHO, LITERT }

class DownloadAuthException(
    message: String,
    val httpCode: Int,
    val model: AiModel
) : Exception(message)

/**
 * Available AI models, ranked from fastest/smallest to best quality/largest.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val description: String,
    val totalSizeDisplay: String,
    val modelUrl: String,
    val modelSizeDisplay: String,
    val mmprojUrl: String,
    val mmprojSizeDisplay: String,
    val minRamGb: Int,
    val backend: Backend,
    val requiresAuth: Boolean = false,
    val licenseUrl: String = ""
) {
    GEMMA3N_E2B(
        id = "gemma3n-e2b",
        displayName = "Gemma 3n E2B",
        description = "Fast, vision + audio, edge optimized",
        totalSizeDisplay = "~3.7 GB",
        modelUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
        modelSizeDisplay = "3.7 GB",
        mmprojUrl = "",
        mmprojSizeDisplay = "",
        minRamGb = 4,
        backend = Backend.LITERT,
        requiresAuth = true,
        licenseUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
    ),
    GEMMA3N_E4B(
        id = "gemma3n-e4b",
        displayName = "Gemma 3n E4B",
        description = "Better quality, edge optimized",
        totalSizeDisplay = "~4.2 GB",
        modelUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
        modelSizeDisplay = "4.2 GB",
        mmprojUrl = "",
        mmprojSizeDisplay = "",
        minRamGb = 6,
        backend = Backend.LITERT,
        requiresAuth = true,
        licenseUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm"
    ),
    GEMMA4_E2B(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B",
        description = "Balanced speed and quality",
        totalSizeDisplay = "~4.1 GB",
        modelUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
        modelSizeDisplay = "3.1 GB",
        mmprojUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/mmproj-F16.gguf",
        mmprojSizeDisplay = "986 MB",
        minRamGb = 6,
        backend = Backend.NOBODYWHO
    ),
    GEMMA4_E4B(
        id = "gemma4-e4b",
        displayName = "Gemma 4 E4B",
        description = "Best quality, needs powerful device",
        totalSizeDisplay = "~6.0 GB",
        modelUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
        modelSizeDisplay = "5.0 GB",
        mmprojUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/mmproj-F16.gguf",
        mmprojSizeDisplay = "990 MB",
        minRamGb = 8,
        backend = Backend.NOBODYWHO
    ),
    QWEN25_VL_7B(
        id = "qwen25-vl-7b",
        displayName = "Qwen2.5-VL 7B",
        description = "Strong OCR and math, very large",
        totalSizeDisplay = "~5.2 GB",
        modelUrl = "https://huggingface.co/ggml-org/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
        modelSizeDisplay = "4.4 GB",
        mmprojUrl = "https://huggingface.co/ggml-org/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-Qwen2.5-VL-7B-Instruct-Q8_0.gguf",
        mmprojSizeDisplay = "793 MB",
        minRamGb = 8,
        backend = Backend.NOBODYWHO
    );

    val needsMmproj: Boolean get() = mmprojUrl.isNotEmpty()
    val modelFileExtension: String get() = if (backend == Backend.LITERT) "litertlm" else "gguf"
}

/**
 * Manages AI model files on-device, including downloading and selection.
 * Each model is stored in its own subdirectory under the models folder.
 */
class ModelManager(private val context: Context) {

    private val modelsDir: File = File(context.getExternalFilesDir(null), "models")
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    init {
        // Migrate old-style model files (models/model.gguf) to subdirectory (models/gemma4-e2b/)
        val oldModel = File(modelsDir, "model.gguf")
        val oldMmproj = File(modelsDir, "mmproj.gguf")
        if (oldModel.exists() && !areModelsAvailable(AiModel.GEMMA4_E2B)) {
            val dest = modelDir(AiModel.GEMMA4_E2B)
            dest.mkdirs()
            oldModel.renameTo(File(dest, "model.gguf"))
            if (oldMmproj.exists()) {
                oldMmproj.renameTo(File(dest, "mmproj.gguf"))
            }
        }
    }

    var selectedModel: AiModel
        get() {
            val id = prefs.getString("selected_model", AiModel.GEMMA4_E2B.id)
            return AiModel.entries.find { it.id == id } ?: AiModel.GEMMA4_E2B
        }
        set(value) {
            prefs.edit().putString("selected_model", value.id).apply()
        }

    private val modelFilename = "model"

    fun modelPath(model: AiModel = selectedModel): String =
        File(modelDir(model), "$modelFilename.${model.modelFileExtension}").absolutePath

    fun mmprojPath(model: AiModel = selectedModel): String =
        File(modelDir(model), "mmproj.gguf").absolutePath

    fun isModelDownloaded(model: AiModel = selectedModel): Boolean =
        File(modelDir(model), "$modelFilename.${model.modelFileExtension}").exists()

    fun isMmprojDownloaded(model: AiModel = selectedModel): Boolean =
        !model.needsMmproj || File(modelDir(model), "mmproj.gguf").exists()

    fun areModelsAvailable(model: AiModel = selectedModel): Boolean =
        isModelDownloaded(model) && isMmprojDownloaded(model)

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

    private fun modelDir(model: AiModel): File = File(modelsDir, model.id)

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
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (model.requiresAuth) {
                val token = hfToken
                    ?: throw Exception("HuggingFace token required. Go to huggingface.co/settings/tokens to create one, then enter it in the app.")
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.connect()

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw when (code) {
                    401, 403 -> DownloadAuthException(
                        "Authentication failed (HTTP $code)",
                        httpCode = code,
                        model = model
                    )
                    else -> Exception("Download failed: HTTP $code ${connection.responseMessage}")
                }
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            val buffer = ByteArray(131_072)

            connection.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    while (true) {
                        ensureActive() // support coroutine cancellation
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            if (!tmpFile.renameTo(destFile)) {
                // renameTo can fail on cross-filesystem; fall back to copy
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            if (tmpFile.exists() && !destFile.exists()) {
                tmpFile.delete()
            }
        }
    }
}
