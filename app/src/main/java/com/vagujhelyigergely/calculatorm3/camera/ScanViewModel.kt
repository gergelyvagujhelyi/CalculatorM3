package com.vagujhelyigergely.calculatorm3.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.vagujhelyigergely.calculatorm3.R
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelDownloadWorker
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionException
import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
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
    /**
     * One-time preparation of the model for a new AI engine version (or a freshly downloaded
     * model): it's loaded once, then released before the capture chooser appears. [isUpdate]
     * picks the wording — "Updating to the new model format" vs "Preparing the AI model".
     */
    data class PreparingModel(
        val isUpdate: Boolean,
        val startTimeMs: Long = System.currentTimeMillis()
    ) : ScanUiState
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
    /**
     * [checkOutput]: the model finished but its answer isn't a single number to use (e.g. it
     * ended with a fraction); the screen then points to its output instead of "failed".
     */
    data class Error(
        val message: String,
        val rawResponse: String? = null,
        val checkOutput: Boolean = false
    ) : ScanUiState
    /**
     * [model] can't be used: its file is gone ([missing], e.g. deleted outside the app) or the
     * engine can't load it (damaged, or cut short). Offers to download it again. [detail] is the
     * engine's error, if any.
     */
    data class ModelUnusable(
        val model: AiModel,
        val missing: Boolean,
        val detail: String = ""
    ) : ScanUiState
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
    private val authManager: HuggingFaceAuthManager,
    context: Context
) : ViewModel() {

    // Store the *application* context so the ViewModel can outlive an Activity without leaking it.
    // The factory already passes applicationContext; this makes it safe regardless of caller.
    private val appContext = context.applicationContext

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    private var loadedModelId: String? = null
    /** Image-token budget the loaded model was built with; a different setting needs a reload. */
    private var loadedBudget: Int? = null

    /** The speed/quality switch on the capture screen (Gemma 4 models only). */
    var scanQuality by mutableStateOf(modelManager.scanQuality)
        private set

    /** Whether [scanQuality] applies to the selected model, i.e. whether to show the switch. */
    val scanQualityApplies: Boolean get() = modelManager.selectedModel.supportsScanQuality

    /** Takes effect on the next scan: the model is loaded per scan, with the current budget. */
    fun updateScanQuality(quality: ScanQuality) {
        modelManager.scanQuality = quality
        scanQuality = quality
    }

    private fun budgetFor(model: AiModel): Int? =
        if (model.supportsScanQuality) modelManager.scanQuality.visualTokenBudget else null
    private var downloadObserver: Job? = null
    private var inferenceJob: Job? = null
    private var preparationJob: Job? = null
    /** The background release started by [releaseAll]; the next load must wait for it. */
    private var releaseJob: Job? = null

    fun initialize() {
        // A preparation started earlier is still running (the window was closed and reopened):
        // keep showing it rather than starting a second one.
        if (preparationJob?.isActive == true) return
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
            // A new AI engine version prepares the model on its first load, which is slow
            // (about a minute). Do it now, with a message, instead of during the first scan.
            if (modelManager.needsPreparation(selected)) {
                prepareModel(selected, isUpdate = true)
                return@launch
            }
            // Don't load the model yet — show the capture chooser. The model is
            // loaded lazily in onPhotoCaptured, once a photo exists.
            uiState = ScanUiState.Capturing
        }
    }

    /**
     * Load [model] once so the engine does its one-time preparation, then release it again so
     * it isn't resident while the system camera opens; then show the capture chooser. Shows
     * [ScanUiState.PreparingModel] until finished. A failure here isn't fatal: the first scan
     * loads the model again and reports any error then.
     */
    private fun prepareModel(model: AiModel, isUpdate: Boolean) {
        preparationJob = viewModelScope.launch {
            uiState = ScanUiState.PreparingModel(isUpdate)
            try {
                withContext(Dispatchers.IO) {
                    try {
                        solver.setVisualTokenBudget(budgetFor(model))
                        solver.loadModel(modelManager.modelPath(model))
                    } finally {
                        // Free whatever the failed or finished load left behind.
                        solver.release()
                    }
                }
                loadedModelId = null
                modelManager.markPrepared(model)
                uiState = ScanUiState.Capturing
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("ScanViewModel", "Model preparation failed", e)
                loadedModelId = null
                uiState = unusable(model, e)
            }
        }
    }

    /**
     * Load the selected model into memory if needed. Called lazily, only once a
     * photo exists — so the (potentially multi-GB) model is NOT resident while the
     * system camera app launches, which otherwise OOM-killed us with large models.
     * Returns true if the model is ready, false (and sets an Error state) on failure.
     */
    private suspend fun ensureModelLoaded(model: AiModel): Boolean {
        // The previous scan's release may still be running. Let it finish first; otherwise it
        // takes the solver lock after this load and closes the engine this scan is about to use.
        releaseJob?.join()
        val budget = budgetFor(model)
        if (solver.isModelLoaded && loadedModelId == model.id && loadedBudget == budget) return true
        if (!modelManager.areModelsAvailable(model)) {
            // Deleted outside the app while the window was open.
            uiState = ScanUiState.ModelUnusable(model, missing = true)
            return false
        }
        uiState = ScanUiState.ModelLoading()
        return try {
            solver.setVisualTokenBudget(budget)
            solver.loadModel(modelManager.modelPath(model))
            loadedModelId = model.id
            loadedBudget = budget
            true
        } catch (e: Exception) {
            // A stop during the load cancels this coroutine; let it propagate (the caller's
            // finally releases the engine) instead of surfacing it as a load error.
            if (e is kotlinx.coroutines.CancellationException) throw e
            loadedModelId = null
            uiState = unusable(model, e)
            false
        }
    }

    /** The state for a model whose load just failed with [e]: missing, or present but unloadable. */
    private fun unusable(model: AiModel, e: Exception) =
        if (!modelManager.areModelsAvailable(model)) ScanUiState.ModelUnusable(model, missing = true)
        else ScanUiState.ModelUnusable(model, missing = false, detail = e.message ?: "")

    /**
     * Delete [model]'s files (including a partial download) and its prepared record, then hand
     * it to [startDownload] — the screen passes its own starter, which asks for the notification
     * permission first. Used from [ScanUiState.ModelUnusable] when the file is damaged or gone.
     */
    fun redownload(model: AiModel, startDownload: (AiModel) -> Unit = ::startDownload) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Nothing may hold the file open while it's deleted.
                solver.release()
                modelManager.deleteModel(model)
            }
            loadedModelId = null
            startDownload(model)
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
                uiState = ScanUiState.Error(appContext.getString(
                    R.string.error_signin_failed, e.message ?: appContext.getString(R.string.please_try_again)))
            }
        }
    }

    /** The user accepted the mobile-data warning: allow metered networks for this download. */
    fun confirmMobileDataDownload(model: AiModel) {
        forceDownload(model, allowMeteredNetwork = true)
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
        val alreadyDownloaded = modelManager.areModelsAvailable(model)
        if (!modelManager.isOnUnmeteredNetwork && !alreadyDownloaded) {
            uiState = ScanUiState.MobileDataWarning(model)
            return
        }
        // No consent to mobile data was given, so the download stays on unmetered networks —
        // unless there's nothing left to fetch, in which case no network constraint should block
        // the (instant) worker run.
        forceDownload(model, allowMeteredNetwork = alreadyDownloaded)
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
        downloadObserver?.cancel()
        downloadObserver = null
        showModelSelection()
    }

    private fun forceDownload(model: AiModel, allowMeteredNetwork: Boolean) {
        modelManager.selectedModel = model
        modelManager.enqueueDownload(model, allowMeteredNetwork)
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
                        // A fresh download still needs the engine's one-time preparation; do it
                        // now, with a message, rather than making the first scan slow.
                        if (modelManager.needsPreparation(m)) {
                            prepareModel(m, isUpdate = false)
                        } else {
                            uiState = ScanUiState.Capturing
                        }
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
                            uiState = ScanUiState.Error(appContext.getString(
                                R.string.error_download_failed,
                                out.getString(ModelDownloadWorker.KEY_MESSAGE) ?: ""
                            ))
                        }
                    }
                    WorkInfo.State.CANCELLED, null -> { /* nothing to show */ }
                }
            }
        }
    }

    fun onPhotoCaptured(imagePath: String) {
        val model = modelManager.selectedModel
        // Cancel any still-running inference before starting a new one, so an overlapping call
        // can't orphan the previous job (the ViewModel is Activity-scoped, so an un-cancelled job
        // keeps generating in the background). Keep the new Job so stopInference() can cancel it.
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            // Streaming UI updates launch from this scope (not viewModelScope) so cancelling the
            // inference also drops any in-flight token update, instead of it racing to overwrite
            // the Capturing state stopInference() returns to.
            val inferenceScope = this
            var processPath: String? = null
            try {
                // Load the model now that a photo exists (it wasn't resident while the camera app
                // was open). Shows "Loading AI model…" then proceeds. Kept inside the try so the
                // finally releases the engine even if a stop cancels during the (uninterruptible)
                // native load — otherwise the just-loaded model would stay resident.
                if (!ensureModelLoaded(model)) return@launch
                val startTime = System.currentTimeMillis()
                uiState = ScanUiState.Processing(startTimeMs = startTime, backend = solver.activeBackend)
                // Apply EXIF rotation + downscale: a full-res photo can blow up the vision
                // pipeline's memory, and the camera stores it sideways with an EXIF orientation
                // tag the model would otherwise ignore.
                processPath = withContext(Dispatchers.IO) { prepareImage(imagePath, MAX_IMAGE_EDGE) }
                if (processPath == null) {
                    // Preprocessing failed — do NOT fall back to the original full-resolution
                    // photo, which can OOM the vision pipeline. Surface an error instead.
                    uiState = ScanUiState.Error(appContext.getString(R.string.error_image_process))
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
                        inferenceScope.launch {
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
                        val res = (it as? RecognitionException)?.messageRes ?: 0
                        val msg = if (res != 0) appContext.getString(res)
                            else it.message ?: appContext.getString(R.string.recognition_error)
                        ScanUiState.Error(msg, raw, checkOutput = res == R.string.error_no_numerical_answer)
                    }
                )
            } catch (e: Throwable) {
                // Let cancellation (e.g. user closed the screen) propagate instead
                // of showing it as an inference error.
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState = ScanUiState.Error(appContext.getString(R.string.error_inference_failed, e.message ?: ""))
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
     * Abort an in-flight scan: signal the native side to stop generating, cancel the coroutine,
     * and return to the capture screen with no error. The engine is released by onPhotoCaptured's
     * finally (same as a normal scan), so the next photo reloads it lazily. Safe to call when
     * nothing is running — used by both the Stop button and dialog dismiss.
     */
    fun stopInference() {
        // Order matters: interrupt native generation first (it ignores coroutine cancellation),
        // then cancel the coroutine so it unwinds cleanly via its CancellationException guards.
        solver.cancel()
        inferenceJob?.cancel()
        inferenceJob = null
        // Only snap back to capture if we were actually mid-scan; calling this from a dialog
        // dismiss in another state (Success, Downloading, …) must not disturb that state.
        if (uiState is ScanUiState.Processing || uiState is ScanUiState.ModelLoading) {
            uiState = ScanUiState.Capturing
        }
    }

    /**
     * Normalize a captured/picked photo for the model: apply its EXIF orientation
     * (so handwriting isn't sideways) and downscale so the longest edge is ≤ [maxEdge] px.
     * Returns the path to a new JPEG, or null on failure (caller falls back to the original).
     */
    internal fun prepareImage(imagePath: String, maxEdge: Int): String? {
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
            androidx.exifinterface.media.ExifInterface(imagePath).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            return bitmap
        }
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
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
        uiState = ScanUiState.Error(appContext.getString(R.string.error_capture_failed, message))
    }

    fun retry() {
        // Back to the capture chooser if the model is available (loaded lazily on
        // the next photo); otherwise re-run the first-time / download flow.
        // A model that still needs its one-time preparation goes through initialize(), which
        // prepares it (with the message) before the capture chooser.
        if (modelManager.areModelsAvailable() && !modelManager.needsPreparation(modelManager.selectedModel)) {
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
        // Chained so overlapping releases finish in order, and kept so ensureModelLoaded can
        // wait for the last one.
        val previous = releaseJob
        releaseJob = CoroutineScope(Dispatchers.IO).launch {
            previous?.join()
            solver.release()
        }
    }

    companion object {
        /**
         * Longest edge (px) the captured photo is downscaled to before inference. Capped at the
         * model's native vision resolution (Gemma's encoder resizes any input to ≤768² anyway), so
         * feeding more just added a redundant resample (photo→1024→768 instead of photo→768).
         */
        private const val MAX_IMAGE_EDGE = 768

        /** Min gap between streaming UI updates so per-token recomposition can't throttle decode. */
        private const val UI_UPDATE_THROTTLE_MS = 50L
    }
}
