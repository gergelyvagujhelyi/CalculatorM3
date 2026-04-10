package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.Backend
import com.vagujhelyigergely.calculatorm3.ai.DownloadAuthException
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.NobodyWhoSolver
import com.vagujhelyigergely.calculatorm3.ai.RecognitionException
import kotlinx.coroutines.Dispatchers
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
    data class MobileDataWarning(val model: AiModel) : ScanUiState
}

class ScanViewModel(
    private val nobodyWhoSolver: NobodyWhoSolver,
    private val liteRTSolver: MathSolver,
    private val modelManager: ModelManager
) : ViewModel() {

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    private var activeSolver: MathSolver? = null
    private var loadedModelId: String? = null

    private fun solverFor(model: AiModel): MathSolver = when (model.backend) {
        Backend.NOBODYWHO -> nobodyWhoSolver
        Backend.LITERT -> liteRTSolver
    }

    fun initialize() {
        val downloaded = modelManager.downloadedModels()
        if (downloaded.isEmpty()) {
            uiState = ScanUiState.FirstTimeWarning
            return
        }
        if (modelManager.selectedModel.backend == Backend.NOBODYWHO && !nobodyWhoSolver.isNativeLibraryAvailable) {
            uiState = ScanUiState.ModelSelection(
                modelManager.selectedModel, downloaded, modelManager.deviceRamGb
            )
            return
        }
        val selected = modelManager.selectedModel
        if (!modelManager.areModelsAvailable(selected)) {
            uiState = ScanUiState.ModelSelection(
                modelManager.selectedModel, downloaded, modelManager.deviceRamGb
            )
            return
        }
        loadModel(selected)
    }

    private fun loadModel(model: AiModel) {
        val solver = solverFor(model)
        if (solver.isModelLoaded && loadedModelId == model.id) {
            activeSolver = solver
            uiState = ScanUiState.Capturing
            return
        }
        uiState = ScanUiState.ModelLoading()
        viewModelScope.launch {
            try {
                // Release the other solver if switching backends
                if (activeSolver != null && activeSolver != solver) {
                    activeSolver?.release()
                }
                val mmproj = if (model.needsMmproj) modelManager.mmprojPath(model) else null
                solver.loadModel(modelManager.modelPath(model), mmproj)
                activeSolver = solver
                loadedModelId = model.id
                uiState = ScanUiState.Capturing
            } catch (e: Exception) {
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

    private fun forceDownload(model: AiModel) {
        modelManager.selectedModel = model
        val fileCount = if (model.needsMmproj) 2 else 1
        viewModelScope.launch {
            try {
                if (!modelManager.isModelDownloaded(model)) {
                    uiState = ScanUiState.Downloading(
                        model = model,
                        currentFile = "${model.displayName} (${model.modelSizeDisplay})",
                        downloadedBytes = 0, totalBytes = -1,
                        fileIndex = 0, fileCount = fileCount
                    )
                    modelManager.downloadFile(
                        model = model,
                        url = model.modelUrl,
                        destFilename = "model.${model.modelFileExtension}"
                    ) { downloaded, total ->
                        uiState = ScanUiState.Downloading(
                            model = model,
                            currentFile = "${model.displayName} (${model.modelSizeDisplay})",
                            downloadedBytes = downloaded, totalBytes = total,
                            fileIndex = 0, fileCount = fileCount
                        )
                    }
                }

                if (model.needsMmproj && !modelManager.isMmprojDownloaded(model)) {
                    uiState = ScanUiState.Downloading(
                        model = model,
                        currentFile = "Vision projector (${model.mmprojSizeDisplay})",
                        downloadedBytes = 0, totalBytes = -1,
                        fileIndex = 1, fileCount = fileCount
                    )
                    modelManager.downloadFile(
                        model = model,
                        url = model.mmprojUrl,
                        destFilename = "mmproj.gguf"
                    ) { downloaded, total ->
                        uiState = ScanUiState.Downloading(
                            model = model,
                            currentFile = "Vision projector (${model.mmprojSizeDisplay})",
                            downloadedBytes = downloaded, totalBytes = total,
                            fileIndex = 1, fileCount = fileCount
                        )
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
        val solver = activeSolver ?: return
        val startTime = System.currentTimeMillis()
        uiState = ScanUiState.Processing(startTimeMs = startTime)
        viewModelScope.launch {
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
        }
    }

    fun retry() {
        if (activeSolver?.isModelLoaded == true) {
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

    fun releaseAll() {
        nobodyWhoSolver.release()
        liteRTSolver.release()
    }
}
