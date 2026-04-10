package com.vagujhelyigergely.calculatorm3.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages AI model files on-device, including downloading from HuggingFace.
 *
 * Uses Gemma 4 E2B (edge-optimized, ~4.1 GB total):
 *   model.gguf   — Gemma 4 E2B IT Q4_K_M (~3.1 GB)
 *   mmproj.gguf  — Vision projection model F16 (~986 MB)
 */
class ModelManager(context: Context) {

    private val modelsDir: File = File(context.getExternalFilesDir(null), "models")

    val modelPath: String get() = File(modelsDir, MODEL_FILENAME).absolutePath
    val mmprojPath: String get() = File(modelsDir, MMPROJ_FILENAME).absolutePath

    val areModelsAvailable: Boolean
        get() = File(modelsDir, MODEL_FILENAME).exists() &&
                File(modelsDir, MMPROJ_FILENAME).exists()

    val isModelDownloaded: Boolean
        get() = File(modelsDir, MODEL_FILENAME).exists()

    val isMmprojDownloaded: Boolean
        get() = File(modelsDir, MMPROJ_FILENAME).exists()

    val modelsDirectory: String get() = modelsDir.absolutePath

    /**
     * Download a file from [url] to [destFilename] in the models directory.
     * Calls [onProgress] with (bytesDownloaded, totalBytes) periodically.
     * totalBytes is -1 if the server doesn't report content length.
     */
    suspend fun downloadFile(
        url: String,
        destFilename: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val destFile = File(modelsDir, destFilename)
        val tmpFile = File(modelsDir, "$destFilename.tmp")

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
            val buffer = ByteArray(131_072) // 128 KB buffer

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

            // Rename tmp to final only on success
            tmpFile.renameTo(destFile)
        } finally {
            // Clean up partial download on failure
            if (tmpFile.exists() && !destFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "model.gguf"
        const val MMPROJ_FILENAME = "mmproj.gguf"

        const val MODEL_URL =
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"
        const val MMPROJ_URL =
            "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/mmproj-F16.gguf"

        const val MODEL_SIZE_DISPLAY = "3.1 GB"
        const val MMPROJ_SIZE_DISPLAY = "986 MB"
        const val TOTAL_SIZE_DISPLAY = "~4.1 GB"
    }
}
