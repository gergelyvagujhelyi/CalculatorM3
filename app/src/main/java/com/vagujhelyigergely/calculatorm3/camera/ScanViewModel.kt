package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathRecognizer
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
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
    data class Error(val message: String) : ScanUiState
    data class ModelSelection(val downloadedModels: List<AiModel>, val deviceRamGb: Int) : ScanUiState
    data class Downloading(
        val model: AiModel,
        val currentFile: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fileIndex: Int,
        val fileCount: Int
    ) : ScanUiState
    data class DownloadComplete(val startTimeMs: Long = System.currentTimeMillis()) : ScanUiState
}

class ScanViewModel(
    private val recognizer: MathRecognizer,
    private val modelManager: ModelManager
) : ViewModel() {

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    /** The model currently loaded in memory (may differ from selectedModel if not yet loaded). */
    private var loadedModelId: String? = null

    fun initialize() {
        if (!recognizer.isNativeLibraryAvailable) {
            uiState = ScanUiState.Error(
                "Native library not found. Build libnobodywho_android.so and " +
                "place it in app/src/main/jniLibs/arm64-v8a/"
            )
            return
        }
        val selected = modelManager.selectedModel
        if (!modelManager.areModelsAvailable(selected)) {
            uiState = ScanUiState.ModelSelection(modelManager.downloadedModels(), modelManager.deviceRamGb)
            return
        }
        loadModel(selected)
    }

    private fun loadModel(model: AiModel) {
        if (recognizer.isModelLoaded && loadedModelId == model.id) {
            uiState = ScanUiState.Capturing
            return
        }
        uiState = ScanUiState.ModelLoading()
        viewModelScope.launch {
            try {
                recognizer.loadModel(
                    modelManager.modelPath(model),
                    modelManager.mmprojPath(model)
                )
                loadedModelId = model.id
                uiState = ScanUiState.Capturing
            } catch (e: Exception) {
                uiState = ScanUiState.Error("Failed to load AI model: ${e.message}")
            }
        }
    }

    /** Select and use a model that's already downloaded. */
    fun selectModel(model: AiModel) {
        modelManager.selectedModel = model
        if (modelManager.areModelsAvailable(model)) {
            loadModel(model)
        } else {
            startDownload(model)
        }
    }

    /** Download a model's files, then load it. */
    fun startDownload(model: AiModel) {
        modelManager.selectedModel = model
        viewModelScope.launch {
            try {
                if (!modelManager.isModelDownloaded(model)) {
                    uiState = ScanUiState.Downloading(
                        model = model,
                        currentFile = "${model.displayName} (${model.modelSizeDisplay})",
                        downloadedBytes = 0, totalBytes = -1,
                        fileIndex = 0, fileCount = 2
                    )
                    modelManager.downloadFile(
                        model = model,
                        url = model.modelUrl,
                        destFilename = "model.gguf"
                    ) { downloaded, total ->
                        uiState = ScanUiState.Downloading(
                            model = model,
                            currentFile = "${model.displayName} (${model.modelSizeDisplay})",
                            downloadedBytes = downloaded, totalBytes = total,
                            fileIndex = 0, fileCount = 2
                        )
                    }
                }

                if (!modelManager.isMmprojDownloaded(model)) {
                    uiState = ScanUiState.Downloading(
                        model = model,
                        currentFile = "Vision projector (${model.mmprojSizeDisplay})",
                        downloadedBytes = 0, totalBytes = -1,
                        fileIndex = 1, fileCount = 2
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
                            fileIndex = 1, fileCount = 2
                        )
                    }
                }

                uiState = ScanUiState.DownloadComplete()
                loadModel(model)
            } catch (e: Exception) {
                uiState = ScanUiState.Error("Download failed: ${e.message}")
            }
        }
    }

    fun onPhotoCaptured(imagePath: String) {
        val startTime = System.currentTimeMillis()
        uiState = ScanUiState.Processing(startTimeMs = startTime)
        viewModelScope.launch {
            val result = recognizer.solveFromImageStreaming(imagePath) { partialRaw ->
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
                onFailure = { ScanUiState.Error(it.message ?: "Recognition failed") }
            )
        }
    }

    fun retry() {
        uiState = ScanUiState.Capturing
    }

    /** Go back to model selection screen. */
    fun showModelSelection() {
        uiState = ScanUiState.ModelSelection(modelManager.downloadedModels(), modelManager.deviceRamGb)
    }

    val selectedModelName: String get() = modelManager.selectedModel.displayName
}
