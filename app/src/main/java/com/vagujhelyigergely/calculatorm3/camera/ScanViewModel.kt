package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.DownloadAuthException
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var downloadJob: Job? = null

    fun initialize() {
        if (!modelManager.canRunAnyModel) {
            uiState = ScanUiState.DeviceTooWeak
            return
        }
        val downloaded = modelManager.downloadedModels()
        if (downloaded.isEmpty()) {
            uiState = ScanUiState.FirstTimeWarning
            return
        }
        val selected = modelManager.selectedModel
        if (!modelManager.areModelsAvailable(selected)) {
            uiState = ScanUiState.ModelSelection(
                selected, downloaded, modelManager.deviceRamGb
            )
            return
        }
        loadModel(selected)
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
        downloadJob?.cancel()
        downloadJob = null
        showModelSelection()
    }

    private fun forceDownload(model: AiModel) {
        modelManager.selectedModel = model
        val files = model.files
        downloadJob = viewModelScope.launch {
            try {
                files.forEachIndexed { index, file ->
                    if (!modelManager.isFileDownloaded(model, file)) {
                        val label = "${model.displayName} (${file.sizeDisplay})"
                        uiState = ScanUiState.Downloading(
                            model = model,
                            currentFile = label,
                            downloadedBytes = 0, totalBytes = -1,
                            fileIndex = index, fileCount = files.size
                        )
                        modelManager.downloadFile(
                            model = model,
                            url = file.url,
                            destFilename = file.filename
                        ) { downloaded, total ->
                            uiState = ScanUiState.Downloading(
                                model = model,
                                currentFile = label,
                                downloadedBytes = downloaded, totalBytes = total,
                                fileIndex = index, fileCount = files.size
                            )
                        }
                    }
                }

                uiState = ScanUiState.DownloadComplete()
                loadModel(model)
            } catch (e: DownloadAuthException) {
                uiState = ScanUiState.AuthError(
                    httpCode = e.httpCode,
                    model = e.model
                )
            } catch (e: Exception) {
                uiState = ScanUiState.Error("Download failed: ${e.message}")
            }
        }
    }

    fun onPhotoCaptured(imagePath: String) {
        if (!solver.isModelLoaded) return
        val startTime = System.currentTimeMillis()
        uiState = ScanUiState.Processing(startTimeMs = startTime)
        viewModelScope.launch {
            try {
                val result = solver.solveFromImageStreaming(imagePath) { partialRaw ->
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
            } finally {
                // Clean up captured photo
                java.io.File(imagePath).delete()
            }
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
}
