package com.vagujhelyigergely.calculatorm3.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelDownloadWorker
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class ModelLoading(val startTimeMs: Long = System.currentTimeMillis()) : ScanUiState
    data object Capturing : ScanUiState
    data class Processing(
        val partialRaw: String = "",
        val startTimeMs: Long = System.currentTimeMillis()
    ) : ScanUiState
    data class Success(val answer: String, val rawResponse: String, val elapsedMs: Long) : ScanUiState
    data class Error(val message: String, val rawResponse: String? = null) : ScanUiState
    data class ModelSelection(val selectedModel: AiModel, val downloadedModels: List<AiModel>, val deviceRamGb: Int) : ScanUiState
    data class Downloading(
        val model: AiModel,
        val currentFile: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fileIndex: Int,
        val fileCount: Int
    ) : ScanUiState
    data class DownloadComplete(val startTimeMs: Long = System.currentTimeMillis()) : ScanUiState
    data class TokenRequired(val model: AiModel) : ScanUiState
    data class AuthError(val httpCode: Int, val model: AiModel) : ScanUiState
    data object FirstTimeWarning : ScanUiState
    data object DeviceTooWeak : ScanUiState
    data class MobileDataWarning(val model: AiModel) : ScanUiState
}

class ScanViewModel(
    private val solver: MathSolver,
    private val modelManager: ModelManager
) : ViewModel() {

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    private var loadedModelId: String? = null
    private var downloadObserver: Job? = null

    fun initialize() {
        if (!modelManager.canRunAnyModel) {
            uiState = ScanUiState.DeviceTooWeak
            return
        }
        viewModelScope.launch {
            // Re-attach to a download already running in the background (e.g. started
            // before the app was backgrounded) instead of showing the idle UI.
            val current = modelManager.downloadWorkInfoFlow().first()
            if (current != null &&
                (current.state == WorkInfo.State.RUNNING || current.state == WorkInfo.State.ENQUEUED)
            ) {
                observeDownload()
                return@launch
            }
            val downloaded = modelManager.downloadedModels()
            if (downloaded.isEmpty()) {
                uiState = ScanUiState.FirstTimeWarning
                return@launch
            }
            val selected = modelManager.selectedModel
            if (!modelManager.areModelsAvailable(selected)) {
                uiState = ScanUiState.ModelSelection(
                    selected, downloaded, modelManager.deviceRamGb
                )
                return@launch
            }
            loadModel(selected)
        }
    }

    private fun loadModel(model: AiModel) {
        if (solver.isModelLoaded && loadedModelId == model.id) {
            uiState = ScanUiState.Capturing
            return
        }
        uiState = ScanUiState.ModelLoading()
        viewModelScope.launch {
            try {
                solver.loadModel(modelManager.modelPath(model))
                loadedModelId = model.id
                uiState = ScanUiState.Capturing
            } catch (e: Exception) {
                loadedModelId = null
                uiState = ScanUiState.Error("Failed to load AI model: ${e.message}")
            }
        }
    }

    fun selectModel(model: AiModel) {
        modelManager.selectedModel = model
        if (modelManager.areModelsAvailable(model)) {
            loadModel(model)
        } else {
            startDownload(model)
        }
    }

    /** Set HF token and retry download. */
    fun setHfTokenAndDownload(token: String, model: AiModel) {
        modelManager.hfToken = token
        startDownload(model)
    }

    val hasHfToken: Boolean get() = !modelManager.hfToken.isNullOrBlank()

    /** Force download even on mobile data. */
    fun confirmMobileDataDownload(model: AiModel) {
        forceDownload(model)
    }

    fun startDownload(model: AiModel) {
        modelManager.selectedModel = model
        if (model.requiresAuth && !hasHfToken) {
            uiState = ScanUiState.TokenRequired(model)
            return
        }
        if (!modelManager.isOnWifi && !modelManager.areModelsAvailable(model)) {
            uiState = ScanUiState.MobileDataWarning(model)
            return
        }
        forceDownload(model)
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
        downloadObserver?.cancel()
        downloadObserver = null
        showModelSelection()
    }

    private fun forceDownload(model: AiModel) {
        modelManager.selectedModel = model
        modelManager.enqueueDownload(model)
        observeDownload()
    }

    /**
     * Observe the foreground-service download and map its [WorkInfo] to UI state.
     * The download runs in WorkManager, so it keeps going while this screen is
     * gone; here we just reflect its progress whenever the screen is present.
     */
    private fun observeDownload() {
        if (downloadObserver?.isActive == true) return
        downloadObserver = viewModelScope.launch {
            modelManager.downloadWorkInfoFlow().collect { info ->
                when (info?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        val m = modelManager.selectedModel
                        uiState = ScanUiState.Downloading(
                            model = m,
                            currentFile = "${m.displayName} — waiting for network",
                            downloadedBytes = 0, totalBytes = -1,
                            fileIndex = 0, fileCount = m.files.size
                        )
                    }
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val m = AiModel.entries.find {
                            it.id == p.getString(ModelDownloadWorker.KEY_MODEL_ID)
                        } ?: modelManager.selectedModel
                        uiState = ScanUiState.Downloading(
                            model = m,
                            currentFile = "${m.displayName} (${m.totalSizeDisplay})",
                            downloadedBytes = p.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0L),
                            totalBytes = p.getLong(ModelDownloadWorker.KEY_TOTAL, -1L),
                            fileIndex = p.getInt(ModelDownloadWorker.KEY_FILE_INDEX, 0),
                            fileCount = p.getInt(ModelDownloadWorker.KEY_FILE_COUNT, m.files.size)
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val m = AiModel.entries.find {
                            it.id == info.outputData.getString(ModelDownloadWorker.KEY_MODEL_ID)
                        } ?: modelManager.selectedModel
                        uiState = ScanUiState.DownloadComplete()
                        loadModel(m)
                    }
                    WorkInfo.State.FAILED -> {
                        val out = info.outputData
                        if (out.getString(ModelDownloadWorker.KEY_ERROR) == ModelDownloadWorker.ERROR_AUTH) {
                            val m = AiModel.entries.find {
                                it.id == out.getString(ModelDownloadWorker.KEY_MODEL_ID)
                            } ?: modelManager.selectedModel
                            uiState = ScanUiState.AuthError(
                                httpCode = out.getInt(ModelDownloadWorker.KEY_HTTP_CODE, 401),
                                model = m
                            )
                        } else {
                            uiState = ScanUiState.Error(
                                "Download failed: ${out.getString(ModelDownloadWorker.KEY_MESSAGE) ?: ""}"
                            )
                        }
                    }
                    WorkInfo.State.CANCELLED, null -> { /* nothing to show */ }
                }
            }
        }
    }

    fun onPhotoCaptured(imagePath: String) {
        if (!solver.isModelLoaded) return
        val startTime = System.currentTimeMillis()
        uiState = ScanUiState.Processing(startTimeMs = startTime)
        viewModelScope.launch {
            // Downscale first — a full-resolution camera photo can blow up the
            // vision pipeline's memory and crash the process natively.
            val processPath = withContext(Dispatchers.IO) {
                downscaleImage(imagePath, MAX_IMAGE_EDGE) ?: imagePath
            }
            try {
                val result = solver.solveFromImageStreaming(processPath) { partialRaw ->
                    withContext(Dispatchers.Main) {
                        uiState = ScanUiState.Processing(
                            partialRaw = partialRaw,
                            startTimeMs = startTime
                        )
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                uiState = result.fold(
                    onSuccess = { ScanUiState.Success(it.answer, it.raw, elapsed) },
                    onFailure = {
                        val raw = (it as? RecognitionException)?.rawResponse
                        ScanUiState.Error(it.message ?: "Recognition failed", raw)
                    }
                )
            } catch (e: Throwable) {
                uiState = ScanUiState.Error("Inference failed: ${e.message}")
            } finally {
                // Clean up captured photos
                try { java.io.File(imagePath).delete() } catch (_: Exception) {}
                if (processPath != imagePath) {
                    try { java.io.File(processPath).delete() } catch (_: Exception) {}
                }
            }
        }
    }

    /** Downscale an image so its longest edge is at most [maxEdge] px. Returns the new path, or null if no resize is needed. */
    private fun downscaleImage(imagePath: String, maxEdge: Int): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0 || (w <= maxEdge && h <= maxEdge)) return null

        // inSampleSize keeps the full-res bitmap from ever loading into memory.
        val sampleSize = Integer.highestOneBit(maxOf(w, h) / maxEdge)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, sampleSize) }
        val sampled = BitmapFactory.decodeFile(imagePath, decodeOpts) ?: return null
        return try {
            val scale = maxEdge.toFloat() / maxOf(sampled.width, sampled.height)
            val newW = (sampled.width * scale).toInt()
            val newH = (sampled.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(sampled, newW, newH, true)
            if (scaled !== sampled) sampled.recycle()
            try {
                val outFile = java.io.File(java.io.File(imagePath).parent, "small_${System.nanoTime()}.jpg")
                outFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                outFile.absolutePath
            } finally {
                scaled.recycle()
            }
        } catch (e: Exception) {
            sampled.recycle()
            null
        }
    }

    fun onCaptureError(message: String) {
        uiState = ScanUiState.Error("Capture failed: $message")
    }

    fun retry() {
        if (solver.isModelLoaded) {
            uiState = ScanUiState.Capturing
        } else {
            initialize()
        }
    }

    fun showModelSelection() {
        uiState = ScanUiState.ModelSelection(
            modelManager.selectedModel, modelManager.downloadedModels(), modelManager.deviceRamGb
        )
    }

    val selectedModelName: String get() = modelManager.selectedModel.displayName
    val deviceRamGb: Int get() = modelManager.deviceRamGb

    override fun onCleared() {
        super.onCleared()
        releaseAll()
    }

    fun releaseAll() {
        // Launch on IO to avoid blocking the main thread — release() acquires
        // the solver mutex which may be held by an in-flight inference whose
        // onToken callback dispatches to Dispatchers.Main.  Blocking main here
        // with runBlocking would deadlock.
        CoroutineScope(Dispatchers.IO).launch {
            solver.release()
        }
    }

    companion object {
        /** Longest edge (px) the captured photo is downscaled to before inference. */
        private const val MAX_IMAGE_EDGE = 1024
    }
}
