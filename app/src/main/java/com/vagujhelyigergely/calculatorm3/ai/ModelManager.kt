package com.vagujhelyigergely.calculatorm3.ai

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
    val minRamGb: Int
) {
    INTERNVL_1B(
        id = "internvl2-1b",
        displayName = "InternVL2.5 1B",
        description = "Fastest, basic quality",
        totalSizeDisplay = "~1.0 GB",
        modelUrl = "https://huggingface.co/ggml-org/InternVL2_5-1B-GGUF/resolve/main/InternVL2_5-1B-Q8_0.gguf",
        modelSizeDisplay = "675 MB",
        mmprojUrl = "https://huggingface.co/ggml-org/InternVL2_5-1B-GGUF/resolve/main/mmproj-InternVL2_5-1B-Q8_0.gguf",
        mmprojSizeDisplay = "333 MB",
        minRamGb = 3
    ),
    GEMMA3_4B(
        id = "gemma3-4b",
        displayName = "Gemma 3 4B",
        description = "Good quality, well tested",
        totalSizeDisplay = "~2.9 GB",
        modelUrl = "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
        modelSizeDisplay = "2.5 GB",
        mmprojUrl = "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/mmproj-model-f16.gguf",
        mmprojSizeDisplay = "411 MB",
        minRamGb = 5
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
        minRamGb = 6
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
        minRamGb = 8
    );
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

    fun modelPath(model: AiModel = selectedModel): String =
        File(modelDir(model), "model.gguf").absolutePath

    fun mmprojPath(model: AiModel = selectedModel): String =
        File(modelDir(model), "mmproj.gguf").absolutePath

    fun isModelDownloaded(model: AiModel = selectedModel): Boolean =
        File(modelDir(model), "model.gguf").exists()

    fun isMmprojDownloaded(model: AiModel = selectedModel): Boolean =
        File(modelDir(model), "mmproj.gguf").exists()

    fun areModelsAvailable(model: AiModel = selectedModel): Boolean =
        isModelDownloaded(model) && isMmprojDownloaded(model)

    /** List of all models that have been downloaded. */
    fun downloadedModels(): List<AiModel> =
        AiModel.entries.filter { areModelsAvailable(it) }

    /** Total device RAM in GB. */
    val deviceRamGb: Int
        get() {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            return (memInfo.totalMem / (1024L * 1024L * 1024L)).toInt()
        }

    /** Check if a model's RAM requirement exceeds the device's total RAM. */
    fun isModelTooLarge(model: AiModel): Boolean = model.minRamGb > deviceRamGb

    private fun modelDir(model: AiModel): File = File(modelsDir, model.id)

    /**
     * Download a file from [url] to [destFilename] in the model's directory.
     * Calls [onProgress] with (bytesDownloaded, totalBytes) periodically.
     */
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
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            val buffer = ByteArray(131_072)

            connection.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            tmpFile.renameTo(destFile)
        } finally {
            if (tmpFile.exists() && !File(dir, destFilename).exists()) {
                tmpFile.delete()
            }
        }
    }
}
