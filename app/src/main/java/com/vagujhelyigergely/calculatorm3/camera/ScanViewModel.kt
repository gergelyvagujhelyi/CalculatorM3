package com.vagujhelyigergely.calculatorm3.camera

import android.content.Intent
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
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
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
        val startTimeMs: Long = System.currentTimeMillis(),
        val tokenCount: Int = 0,
        val backend: String = "",
        /** Wall-clock of the first generated token; null while still prefilling the image. */
        val firstTokenMs: Long? = null
    ) : ScanUiState
    data class Success(
        val answer: String,
        val rawResponse: String,
        val elapsedMs: Long,
        val tokenCount: Int = 0,
        val backend: String = "",
        /** Time-to-first-token (image prefill), kept separate from the decode rate. */
        val ttftMs: Long = 0L,
        /** Steady-state decode speed, excluding prefill. */
        val decodeTokensPerSec: Double = 0.0
    ) : ScanUiState
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
    /** A gated model needs HuggingFace sign-in before it can be downloaded. */
    data class SignInRequired(val model: AiModel) : ScanUiState
    /** Briefly shown while the OAuth code is exchanged for a token. */
    data object Authenticating : ScanUiState
    data class AuthError(val httpCode: Int, val model: AiModel) : ScanUiState
    data object FirstTimeWarning : ScanUiState
    data class MobileDataWarning(val model: AiModel) : ScanUiState
}

class ScanViewModel(
    private val solver: MathSolver,
    private val modelManager: ModelManager,
    private val authManager: HuggingFaceAuthManager
) : ViewModel() {

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    private var loadedModelId: String? = null
    private var downloadObserver: Job? = null

    fun initialize() {
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
            // Don't load the model yet — show the capture chooser. The model is
            // loaded lazily in onPhotoCaptured, once a photo exists.
            uiState = ScanUiState.Capturing
        }
    }

    /**
     * Load the selected model into memory if needed. Called lazily, only once a
     * photo exists — so the (potentially multi-GB) model is NOT resident while the
     * system camera app launches, which otherwise OOM-killed us with large models.
     * Returns true if the model is ready, false (and sets an Error state) on failure.
     */
    private suspend fun ensureModelLoaded(model: AiModel): Boolean {
        if (solver.isModelLoaded && loadedModelId == model.id) return true
        uiState = ScanUiState.ModelLoading()
        return try {
            solver.loadModel(modelManager.modelPath(model))
            loadedModelId = model.id
            true
        } catch (e: Exception) {
            loadedModelId = null
            uiState = ScanUiState.Error("Failed to load AI model: ${e.message}")
            false
        }
    }

    fun selectModel(model: AiModel) {
        modelManager.selectedModel = model
        if (modelManager.areModelsAvailable(model)) {
            // Loaded lazily on first photo, not here.
            uiState = ScanUiState.Capturing
        } else {
            startDownload(model)
        }
    }

    val hasHfToken: Boolean get() = !modelManager.hfToken.isNullOrBlank()

    /**
     * Build the AppAuth intent to launch for [model]. The Composable launches this via an
     * ActivityResultLauncher and routes the result back through [onSignInResult]. The target
     * model is persisted in [ModelManager.selectedModel] (set here and in [startDownload]),
     * so the flow survives process death while the user is in the browser.
     */
    fun signInIntentFor(model: AiModel): Intent {
        modelManager.selectedModel = model
        return authManager.authRequestIntent()
    }

    /** Drop any stale token and return to the sign-in prompt (e.g. after a 401). */
    fun showSignIn(model: AiModel) {
        modelManager.hfToken = null
        uiState = ScanUiState.SignInRequired(model)
    }

    /** Handle the OAuth Custom Tab result: exchange the code for a token, then resume download. */
    fun onSignInResult(data: Intent?) {
        val model = modelManager.selectedModel
        // A null result, or an explicit user cancellation (AppAuth returns a non-null intent
        // carrying USER_CANCELED_AUTH_FLOW), means the user dismissed the browser — return to
        // the picker quietly rather than showing an error.
        if (data == null || authManager.isUserCanceled(data)) {
            showModelSelection()
            return
        }
        uiState = ScanUiState.Authenticating
        viewModelScope.launch {
            try {
                modelManager.hfToken = authManager.exchangeCodeForToken(data)
                startDownload(model)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState = ScanUiState.Error("Sign-in failed: ${e.message ?: "please try again"}")
            }
        }
    }

    /** Force download even on mobile data. */
    fun confirmMobileDataDownload(model: AiModel) {
        forceDownload(model)
    }

    fun startDownload(model: AiModel) {
        // Never fetch a model the device can't run (the picker also blocks this, but
        // guard here so no path can download an unusable multi-GB model).
        if (modelManager.isModelTooLarge(model)) return
        modelManager.selectedModel = model
        if (model.requiresAuth && !hasHfToken) {
            uiState = ScanUiState.SignInRequired(model)
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
                        modelManager.selectedModel = m
                        // Model loads lazily on first photo, not right after download.
                        uiState = ScanUiState.Capturing
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
        val model = modelManager.selectedModel
        viewModelScope.launch {
            // Load the model now that a photo exists (it wasn't resident while the
            // camera app was open). Shows "Loading AI model…" then proceeds.
            if (!ensureModelLoaded(model)) {
                try { java.io.File(imagePath).delete() } catch (_: Exception) {}
                return@launch
            }
            val startTime = System.currentTimeMillis()
            uiState = ScanUiState.Processing(startTimeMs = startTime, backend = solver.activeBackend)
            var processPath: String? = null
            try {
                // Apply EXIF rotation + downscale: a full-res photo can blow up the vision
                // pipeline's memory, and the camera stores it sideways with an EXIF orientation
                // tag the model would otherwise ignore.
                processPath = withContext(Dispatchers.IO) { prepareImage(imagePath, MAX_IMAGE_EDGE) }
                if (processPath == null) {
                    // Preprocessing failed — do NOT fall back to the original full-resolution
                    // photo, which can OOM the vision pipeline. Surface an error instead.
                    uiState = ScanUiState.Error("Couldn't process that image. Please try another photo.")
                    return@launch
                }
                var tokenCount = 0
                var firstTokenMs = 0L
                var lastTokenMs = 0L
                var lastUiUpdateMs = 0L
                var lastRawLen = 0
                val result = solver.solveFromImageStreaming(processPath) { partialRaw ->
                    // Time decode at the source (inference dispatcher), independent of the UI:
                    // the first token marks end-of-prefill (TTFT); the rest is the decode window.
                    val now = System.currentTimeMillis()
                    // The solver can silently reload GPU→CPU and replay inference with this same
                    // callback, restarting the streamed text from empty. Detect that reset (the
                    // cumulative output shrank) and restart our counters so the stats reflect the
                    // attempt that actually produced the answer.
                    if (partialRaw.length < lastRawLen) {
                        tokenCount = 0
                        firstTokenMs = 0L
                    }
                    lastRawLen = partialRaw.length
                    if (tokenCount == 0) firstTokenMs = now
                    tokenCount++
                    lastTokenMs = now
                    // Coalesce UI updates so per-token recomposition can't throttle the loop.
                    if (now - lastUiUpdateMs >= UI_UPDATE_THROTTLE_MS) {
                        lastUiUpdateMs = now
                        // Dispatch the UI update without suspending the inference loop on the
                        // Main thread; snapshot the mutable counters first to avoid a race.
                        val uiTokenCount = tokenCount
                        val uiFirstTokenMs = firstTokenMs.takeIf { it > 0L }
                        viewModelScope.launch {
                            uiState = ScanUiState.Processing(
                                partialRaw = partialRaw,
                                startTimeMs = startTime,
                                tokenCount = uiTokenCount,
                                backend = solver.activeBackend,
                                firstTokenMs = uiFirstTokenMs
                            )
                        }
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                val ttftMs = if (firstTokenMs > 0L) firstTokenMs - startTime else elapsed
                val decodeMs = (lastTokenMs - firstTokenMs).coerceAtLeast(0L)
                val decodeTps = if (tokenCount > 1 && decodeMs > 0L)
                    (tokenCount - 1) / (decodeMs / 1000.0) else 0.0
                uiState = result.fold(
                    onSuccess = {
                        // Re-read the backend after inference — it may have fallen back GPU→CPU.
                        ScanUiState.Success(it.answer, it.raw, elapsed, tokenCount, solver.activeBackend, ttftMs, decodeTps)
                    },
                    onFailure = {
                        val raw = (it as? RecognitionException)?.rawResponse
                        ScanUiState.Error(it.message ?: "Recognition failed", raw)
                    }
                )
            } catch (e: Throwable) {
                // Let cancellation (e.g. user closed the screen) propagate instead
                // of showing it as an inference error.
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState = ScanUiState.Error("Inference failed: ${e.message}")
            } finally {
                // Free the engine as soon as inference is done: it must NOT stay resident while
                // the system camera launches for the next scan (large models OOM-kill the app —
                // the reason model loading is deferred to here). It reloads lazily on the next
                // photo.
                releaseAll()
                // Clean up captured photos
                try { java.io.File(imagePath).delete() } catch (_: Exception) {}
                if (processPath != null && processPath != imagePath) {
                    try { java.io.File(processPath).delete() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Normalize a captured/picked photo for the model: apply its EXIF orientation
     * (so handwriting isn't sideways) and downscale so the longest edge is ≤ [maxEdge] px.
     * Returns the path to a new JPEG, or null on failure (caller falls back to the original).
     */
    private fun prepareImage(imagePath: String, maxEdge: Int): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null

        // inSampleSize keeps the full-res bitmap from ever loading into memory.
        val sampleSize = if (maxOf(w, h) > maxEdge) Integer.highestOneBit(maxOf(w, h) / maxEdge) else 1
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, sampleSize) }
        var bmp = BitmapFactory.decodeFile(imagePath, decodeOpts) ?: return null
        return try {
            bmp = applyExifOrientation(imagePath, bmp)
            // Scale down further if still larger than maxEdge after sampling.
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            val outFile = java.io.File(java.io.File(imagePath).parent, "scan_prepared_${System.nanoTime()}.jpg")
            outFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Rotate/flip [bitmap] to upright per the source file's EXIF orientation tag. */
    private fun applyExifOrientation(imagePath: String, bitmap: Bitmap): Bitmap {
        val orientation = try {
            android.media.ExifInterface(imagePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            return bitmap
        }
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    fun onCaptureError(message: String) {
        uiState = ScanUiState.Error("Capture failed: $message")
    }

    fun retry() {
        // Back to the capture chooser if the model is available (loaded lazily on
        // the next photo); otherwise re-run the first-time / download flow.
        if (modelManager.areModelsAvailable()) {
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

    /** True if the device has enough RAM to run at least one AI model. */
    val canRunAnyModel: Boolean get() = modelManager.canRunAnyModel

    override fun onCleared() {
        super.onCleared()
        authManager.dispose()
        releaseAll()
    }

    fun releaseAll() {
        // Invalidate the cache marker so the next scan reloads if the engine is gone.
        loadedModelId = null
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

        /** Min gap between streaming UI updates so per-token recomposition can't throttle decode. */
        private const val UI_UPDATE_THROTTLE_MS = 50L
    }
}
