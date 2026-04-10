package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vagujhelyigergely.calculatorm3.ai.MathRecognizer
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object ModelLoading : ScanUiState
    data object Capturing : ScanUiState
    data object Processing : ScanUiState
    data class Success(val expression: String) : ScanUiState
    data class Error(val message: String) : ScanUiState
    data object ModelMissing : ScanUiState
    data class Downloading(
        val currentFile: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fileIndex: Int,     // 0 = model, 1 = mmproj
        val fileCount: Int      // always 2
    ) : ScanUiState
}

class ScanViewModel(
    private val recognizer: MathRecognizer,
    private val modelManager: ModelManager
) : ViewModel() {

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    /** Check native library + model availability and load if needed. */
    fun initialize() {
        if (!recognizer.isNativeLibraryAvailable) {
            uiState = ScanUiState.Error(
                "Native library not found. Build libnobodywho_android.so and " +
                "place it in app/src/main/jniLibs/arm64-v8a/"
            )
            return
        }
        if (!modelManager.areModelsAvailable) {
            uiState = ScanUiState.ModelMissing
            return
        }
        loadModel()
    }

    private fun loadModel() {
        if (recognizer.isModelLoaded) {
            uiState = ScanUiState.Capturing
            return
        }
        uiState = ScanUiState.ModelLoading
        viewModelScope.launch {
            try {
                recognizer.loadModel(modelManager.modelPath, modelManager.mmprojPath)
                uiState = ScanUiState.Capturing
            } catch (e: Exception) {
                uiState = ScanUiState.Error("Failed to load AI model: ${e.message}")
            }
        }
    }

    /** Download both model files, then load the model. */
    fun startDownload() {
        viewModelScope.launch {
            try {
                // Download main model if needed
                if (!modelManager.isModelDownloaded) {
                    uiState = ScanUiState.Downloading(
                        currentFile = "Gemma 4 E2B (${ModelManager.MODEL_SIZE_DISPLAY})",
                        downloadedBytes = 0,
                        totalBytes = -1,
                        fileIndex = 0,
                        fileCount = 2
                    )
                    modelManager.downloadFile(
                        url = ModelManager.MODEL_URL,
                        destFilename = ModelManager.MODEL_FILENAME
                    ) { downloaded, total ->
                        uiState = ScanUiState.Downloading(
                            currentFile = "Gemma 4 E2B (${ModelManager.MODEL_SIZE_DISPLAY})",
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            fileIndex = 0,
                            fileCount = 2
                        )
                    }
                }

                // Download mmproj if needed
                if (!modelManager.isMmprojDownloaded) {
                    uiState = ScanUiState.Downloading(
                        currentFile = "Vision projector (${ModelManager.MMPROJ_SIZE_DISPLAY})",
                        downloadedBytes = 0,
                        totalBytes = -1,
                        fileIndex = 1,
                        fileCount = 2
                    )
                    modelManager.downloadFile(
                        url = ModelManager.MMPROJ_URL,
                        destFilename = ModelManager.MMPROJ_FILENAME
                    ) { downloaded, total ->
                        uiState = ScanUiState.Downloading(
                            currentFile = "Vision projector (${ModelManager.MMPROJ_SIZE_DISPLAY})",
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            fileIndex = 1,
                            fileCount = 2
                        )
                    }
                }

                // Both files downloaded, now load the model
                loadModel()
            } catch (e: Exception) {
                uiState = ScanUiState.Error("Download failed: ${e.message}")
            }
        }
    }

    /** Called when the user captures a photo. */
    fun onPhotoCaptured(imagePath: String) {
        uiState = ScanUiState.Processing
        viewModelScope.launch {
            val result = recognizer.recognizeExpression(imagePath)
            uiState = result.fold(
                onSuccess = { ScanUiState.Success(it) },
                onFailure = { ScanUiState.Error(it.message ?: "Recognition failed") }
            )
        }
    }

    /** Return to the camera preview for another capture. */
    fun retry() {
        uiState = ScanUiState.Capturing
    }

    val modelsDirectory: String get() = modelManager.modelsDirectory
}
