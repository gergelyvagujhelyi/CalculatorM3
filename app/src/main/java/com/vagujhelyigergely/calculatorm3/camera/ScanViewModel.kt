package com.vagujhelyigergely.calculatorm3.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.ai.SolvedSteps
import com.vagujhelyigergely.calculatorm3.ai.SolverPrompts
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
    /**
     * Ready for a picture from the source the AI menu was opened for ([ScanViewModel.entry]).
     * [autoLaunch]: open the camera or photo picker straight away — when the user just asked for
     * it (the menu, Retry). False after a detour they may have walked away from (a download,
     * the one-time preparation, a stopped scan): the screen then offers a button instead.
     */
    data class Capturing(val autoLaunch: Boolean = true) : ScanUiState
    /** The Type screen: a typed problem ([ScanViewModel.typedProblem]) waiting for Solve. */
    data object Typing : ScanUiState
    /** The handwriting pad: [ScanViewModel.strokes] waiting for Solve. */
    data object Drawing : ScanUiState
    data class Processing(
        val partialRaw: String = "",
        /** Start of the current inference attempt (a GPU→CPU retry starts a new one). */
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
        val decodeTokensPerSec: Double = 0.0,
        /** A typed problem's step-by-step answer; null for a photo scan. */
        val steps: SolvedSteps? = null
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
    context: Context,
    /** Where AI history is kept; tests pass their own folder. */
    historyDir: java.io.File? = null,
) : ViewModel() {

    // Store the *application* context so the ViewModel can outlive an Activity without leaking it.
    // The factory already passes applicationContext; this makes it safe regardless of caller.
    private val appContext = context.applicationContext

    // ── AI history ──────────────────────────────────────────────────────────

    private val historyStore = AiHistoryStore(historyDir ?: java.io.File(appContext.filesDir, "ai_history"))
    /** All store access runs here, one operation at a time. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val historyIo = Dispatchers.IO.limitedParallelism(1)

    /** Saved AI solves, newest first ([AiHistoryStore]). */
    var aiHistory by mutableStateOf<List<AiHistoryEntry>>(emptyList())
        private set

    init {
        viewModelScope.launch { refreshHistory() }
    }

    private suspend fun refreshHistory() {
        aiHistory = withContext(historyIo) { historyStore.load() }
    }

    /** The picture saved with [entry] (Camera/Gallery), if it's still there. */
    fun historyImage(entry: AiHistoryEntry): java.io.File? = historyStore.imageOf(entry)

    fun deleteHistoryEntry(id: String) {
        viewModelScope.launch {
            withContext(historyIo) { historyStore.delete(id) }
            refreshHistory()
        }
    }

    fun clearAiHistory() {
        viewModelScope.launch {
            withContext(historyIo) { historyStore.clear() }
            refreshHistory()
        }
    }

    /** What a solve was given, for its AI history entry. */
    private sealed interface HistoryInput {
        data class Typed(val text: String) : HistoryInput
        data class Drawn(val strokes: List<InkStroke>) : HistoryInput
        /** A photo: the prepared picture (the solve's input path) is saved with it. */
        data object Photo : HistoryInput
    }

    /**
     * Save the solve that just ended in [state] to AI history: every answer, and every failure
     * that still produced output. A run that produced nothing (stopped early, a crash before the
     * first word) isn't saved. Never fails the solve: a storage error only skips the entry.
     */
    private suspend fun saveToHistory(
        state: ScanUiState, input: HistoryInput, preparedInput: String?, model: AiModel, elapsedMs: Long
    ): AiHistoryEntry? {
        val base = AiHistoryEntry(
            id = "", timestamp = System.currentTimeMillis(), mode = entry,
            typedText = (input as? HistoryInput.Typed)?.text,
            strokes = (input as? HistoryInput.Drawn)?.strokes,
            output = "", outcome = AiHistoryOutcome.SOLVED, model = model.displayName,
            // Speed applies to pictures (photos, drawings) on models that support it.
            speed = modelManager.scanQuality.takeIf { model.supportsScanQuality && input !is HistoryInput.Typed },
            elapsedMs = elapsedMs,
        )
        val entryToSave = when (state) {
            is ScanUiState.Success -> base.copy(output = state.rawResponse, answer = state.answer, steps = state.steps)
            is ScanUiState.Error -> {
                val raw = state.rawResponse
                if (raw.isNullOrBlank()) return null
                base.copy(
                    output = raw, message = state.message,
                    outcome = if (state.checkOutput) AiHistoryOutcome.CHECK else AiHistoryOutcome.FAILED
                )
            }
            else -> return null
        }
        val image = if (input is HistoryInput.Photo) preparedInput?.let { java.io.File(it) } else null
        return try {
            withContext(historyIo) { historyStore.add(entryToSave, image) }.also { refreshHistory() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ScanViewModel", "Couldn't save to AI history", e)
            // Not saved (no id), but questions about it can still be asked while it's on screen.
            entryToSave
        }
    }

    /**
     * A history entry to solve again once the window is ready ([initialize]'s rerunId): the same
     * input, with the model and speed set now.
     */
    private var pendingRerun: AiHistoryEntry? = null

    // ── Follow-up questions ─────────────────────────────────────────────────

    /** Where a question about the current answer stands. */
    sealed interface FollowUpUi {
        data object Idle : FollowUpUi
        /** The engine was released (window closed, app in the background) and is reloading. */
        data object LoadingModel : FollowUpUi
        data class Answering(val question: String, val partial: String) : FollowUpUi
        data class Failed(val message: String) : FollowUpUi
    }

    /** The answer on screen: what questions are about. Null when there's none. */
    var currentAnswer by mutableStateOf<AiHistoryEntry?>(null)
        private set
    var followUp by mutableStateOf<FollowUpUi>(FollowUpUi.Idle)
        private set
    private var followUpJob: Job? = null

    /** Answered or "check the answer" results take questions; failures and partials don't. */
    private fun takesQuestions(entry: AiHistoryEntry) =
        entry.outcome != AiHistoryOutcome.FAILED && entry.followUps.size < MAX_FOLLOW_UPS

    /** Questions still allowed about the current answer. */
    val followUpsLeft: Int get() = currentAnswer?.let { MAX_FOLLOW_UPS - it.followUps.size } ?: 0

    /** The screen state that shows a saved [entry] as it was answered. */
    private fun answerState(entry: AiHistoryEntry): ScanUiState = when (entry.outcome) {
        AiHistoryOutcome.SOLVED -> ScanUiState.Success(
            entry.answer, entry.output, entry.elapsedMs, steps = entry.steps
        )
        else -> ScanUiState.Error(
            entry.message.orEmpty(), entry.output, checkOutput = entry.outcome == AiHistoryOutcome.CHECK
        )
    }

    /**
     * Ask [question] about the current answer (at most [MAX_FOLLOW_UPS]). Built fresh from the
     * saved text each time, so it works whether or not the engine is still loaded: if it isn't,
     * it's loaded first ([FollowUpUi.LoadingModel]); a model that can't load shows the usual
     * "download again" screen. The reply is saved with the answer in AI history.
     */
    fun askFollowUp(question: String) {
        val answer = currentAnswer ?: return
        val q = question.trim()
        // Busy only while a question is being answered: the previous one may still be saving its
        // reply to history, which mustn't swallow a question asked right after the reply shows.
        val busy = followUp is FollowUpUi.Answering || followUp is FollowUpUi.LoadingModel
        if (q.isEmpty() || !takesQuestions(answer) || busy) return
        val model = modelManager.selectedModel
        followUpJob = viewModelScope.launch {
            val scope = this
            try {
                if (!(solver.isModelLoaded && loadedModelId == model.id)) {
                    followUp = FollowUpUi.LoadingModel
                    if (!ensureModelLoaded(model, showLoading = false)) {
                        followUp = FollowUpUi.Idle
                        return@launch
                    }
                }
                followUp = FollowUpUi.Answering(q, "")
                val message = SolverPrompts.followUpMessage(
                    problem = answer.steps?.problem ?: answer.typedText.orEmpty(),
                    answer = answer.steps?.answer ?: answer.answer,
                    working = answer.steps?.steps?.takeIf { it.isNotBlank() } ?: answer.output,
                    earlier = answer.followUps.map { it.question to it.reply },
                    question = q
                )
                var lastUiMs = 0L
                val result = solver.followUpStreaming(message) { partial ->
                    val now = System.currentTimeMillis()
                    if (partial.isNotEmpty() && now - lastUiMs >= UI_UPDATE_THROTTLE_MS) {
                        lastUiMs = now
                        scope.launch {
                            if (followUp is FollowUpUi.Answering) followUp = FollowUpUi.Answering(q, partial)
                        }
                    }
                }
                val reply = result.getOrNull()?.raw?.trim().orEmpty()
                if (reply.isEmpty()) {
                    val res = (result.exceptionOrNull() as? RecognitionException)?.messageRes ?: 0
                    followUp = FollowUpUi.Failed(
                        if (res != 0) appContext.getString(res) else appContext.getString(R.string.follow_up_failed)
                    )
                    return@launch
                }
                val updated = answer.copy(followUps = answer.followUps + FollowUp(q, reply))
                currentAnswer = updated
                followUp = FollowUpUi.Idle
                if (updated.id.isNotEmpty()) {
                    try {
                        withContext(historyIo) { historyStore.update(updated) }
                        refreshHistory()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("ScanViewModel", "Couldn't save the follow-up", e)
                    }
                }
                // That was the last allowed question: nothing left to keep the engine for.
                if (!takesQuestions(updated)) releaseAll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (followUp !is FollowUpUi.Failed) followUp = FollowUpUi.Idle
                throw e
            }
        }
    }

    /** Stop a question being answered; it doesn't count against the limit. */
    fun stopFollowUp() {
        if (followUpJob?.isActive == true) solver.cancel()
        followUpJob?.cancel()
        followUpJob = null
        if (followUp !is FollowUpUi.Failed) followUp = FollowUpUi.Idle
    }

    /** Leave the answer on screen: end any question and free the engine it was keeping. */
    private fun leaveAnswer() {
        stopFollowUp()
        followUp = FollowUpUi.Idle
        if (currentAnswer != null) {
            currentAnswer = null
            releaseAll()
        }
    }

    /**
     * Solve the current answer's input again, as a new answer with its own questions (offered
     * once its questions are used up). Falls back to [retry] if the input is gone.
     */
    fun solveAnswerAgain() {
        val answer = currentAnswer ?: return retry()
        leaveAnswer()
        if (!rerun(answer)) retry()
    }

    /** Start [entry]'s solve again from its saved input. False if its input is gone. */
    private fun rerun(entry: AiHistoryEntry): Boolean {
        when (entry.mode) {
            AiEntry.TYPE -> {
                val text = entry.typedText ?: return false
                typedProblem = text
                onTextSubmitted(text)
            }
            AiEntry.WRITE -> {
                val saved = entry.strokes ?: return false
                strokes.clear()
                strokes.addAll(saved)
                clearRedo()
                onDrawingSubmitted()
            }
            AiEntry.CAMERA, AiEntry.GALLERY -> {
                // The scan deletes its input afterwards, so give it a copy of the saved picture.
                val picture = historyStore.imageOf(entry) ?: return false
                val copy = java.io.File(appContext.cacheDir, "rerun_${System.nanoTime()}.jpg")
                picture.copyTo(copy, overwrite = true)
                onPhotoCaptured(copy.path)
            }
            AiEntry.SETTINGS -> return false
        }
        return true
    }

    var uiState by mutableStateOf<ScanUiState>(ScanUiState.Idle)
        private set

    private var loadedModelId: String? = null
    /** Image-token budget the loaded model was built with; a different setting needs a reload. */
    private var loadedBudget: Int? = null

    /** What the AI window was opened for from the AI menu; set by [initialize]. */
    var entry by mutableStateOf(AiEntry.CAMERA)
        private set

    /**
     * The Type screen's text. Kept across solves, so Retry and Edit return to it; the screen
     * clears it when the window is opened afresh from the menu.
     */
    var typedProblem by mutableStateOf("")
        private set

    fun updateTypedProblem(text: String) {
        typedProblem = text
    }

    /** Back to the Type screen with the problem as it was (Edit on a typed problem's answer). */
    fun editTypedProblem() {
        leaveAnswer()
        uiState = ScanUiState.Typing
    }

    /**
     * Correct how a drawing was read: continue as a typed problem, starting from [readAs]
     * (Fix as text on a drawing's answer). A typed solve skips the picture, so it's quick.
     */
    fun fixAsText(readAs: String) {
        leaveAnswer()
        entry = AiEntry.TYPE
        typedProblem = readAs
        uiState = ScanUiState.Typing
    }

    // ── Handwriting pad ─────────────────────────────────────────────────────

    /** The pad's strokes, oldest first. Kept across solves, so Draw again returns to them. */
    val strokes = mutableStateListOf<InkStroke>()
    /** Strokes taken back by [undo], newest last; any new stroke or erase clears them. */
    private val undone = mutableListOf<InkStroke>()
    var canRedo by mutableStateOf(false)
        private set
    /** Once a stylus has touched the pad, fingers no longer draw (a resting palm leaves no marks). */
    var stylusOnly by mutableStateOf(false)
        private set

    fun addStroke(stroke: InkStroke) {
        if (stroke.points.isEmpty()) return
        strokes += stroke
        clearRedo()
    }

    /** Erase the stroke under [point] ([InkGeometry.strokeAt]); true if one was removed. */
    fun eraseAt(point: InkPoint, radius: Float): Boolean {
        val index = InkGeometry.strokeAt(strokes, point, radius) ?: return false
        strokes.removeAt(index)
        clearRedo()
        return true
    }

    fun undo() {
        val last = strokes.removeLastOrNull() ?: return
        undone += last
        canRedo = true
    }

    fun redo() {
        val stroke = undone.removeLastOrNull() ?: return
        strokes += stroke
        canRedo = undone.isNotEmpty()
    }

    fun clearInk() {
        strokes.clear()
        clearRedo()
    }

    fun onStylusSeen() {
        stylusOnly = true
    }

    private fun clearRedo() {
        undone.clear()
        canRedo = false
    }

    /** Back to the pad with the drawing as it was (Draw again on a drawing's answer). */
    fun drawAgain() {
        leaveAnswer()
        uiState = ScanUiState.Drawing
    }

    /**
     * Solve the drawing: render it black on white ([InkRasterizer]) and solve that picture step
     * by step. The picture is a temporary file, deleted afterwards.
     */
    fun onDrawingSubmitted() {
        val ink = strokes.toList()
        if (ink.isEmpty()) return
        startSolve(
            prepareInput = {
                withContext(Dispatchers.IO) {
                    InkRasterizer.writeJpeg(ink, appContext.cacheDir, MAX_IMAGE_EDGE)?.path
                }.also {
                    if (it == null) uiState = ScanUiState.Error(appContext.getString(R.string.error_image_process))
                }
            },
            solve = { path, onToken -> solver.solveFromImageStepsStreaming(path, onToken) },
            parseSteps = { raw -> SolverPrompts.parseSteps(raw, "") },
            history = HistoryInput.Drawn(ink),
            cleanup = { path -> path?.let { try { java.io.File(it).delete() } catch (_: Exception) {} } }
        )
    }

    /** The speed/quality setting on the AI settings page (Gemma 4 models only). */
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

    /**
     * Where the window goes once a model is ready: the capture source for [AiEntry.CAMERA] and
     * [AiEntry.GALLERY] (opened right away if [autoLaunch]), or the settings page.
     */
    private fun showReady(autoLaunch: Boolean) {
        pendingRerun?.let { rerunEntry ->
            pendingRerun = null
            if (rerun(rerunEntry)) return
        }
        uiState = when (entry) {
            AiEntry.SETTINGS -> settingsState()
            AiEntry.TYPE -> ScanUiState.Typing
            AiEntry.WRITE -> ScanUiState.Drawing
            AiEntry.CAMERA, AiEntry.GALLERY -> ScanUiState.Capturing(autoLaunch)
        }
    }

    /** False for an [initialize] that resumes a recreated window (see there). */
    private var autoLaunchAllowed = true

    /**
     * The screen has opened the camera/picker for a [ScanUiState.Capturing] with autoLaunch: keep
     * the state but drop the flag, so a recomposition (rotation) can't open it a second time.
     */
    fun onCaptureLaunched() {
        if ((uiState as? ScanUiState.Capturing)?.autoLaunch == true) {
            uiState = ScanUiState.Capturing(autoLaunch = false)
        }
    }

    private fun settingsState() = ScanUiState.ModelSelection(
        modelManager.selectedModel, modelManager.downloadedModels(), modelManager.deviceRamGb
    )

    /**
     * Set up the window for [entry]. [resumed]: the window was already open and Android
     * recreated the app (e.g. while the system camera was in front); the camera's pending result
     * may still arrive, so don't open the camera again on top of it.
     */
    fun initialize(
        entry: AiEntry = AiEntry.CAMERA,
        resumed: Boolean = false,
        rerunId: String? = null,
        /** A saved answer to open as it was, to ask about it (Ask about this, in AI history). */
        answerId: String? = null,
    ) {
        this.entry = entry
        leaveAnswer()
        if (!resumed && answerId != null) {
            val saved = aiHistory.find { it.id == answerId }
            if (saved != null) {
                // No model checks yet: showing the answer needs none; a question loads it.
                currentAnswer = saved
                uiState = answerState(saved)
                return
            }
        }
        autoLaunchAllowed = !resumed
        // Solve again (from AI history): found by id, and not repeated for a recreated window.
        pendingRerun = if (resumed || rerunId == null) null else aiHistory.find { it.id == rerunId }
        // A preparation started earlier is still running (the window was closed and reopened):
        // keep showing it rather than starting a second one. It ends at the new entry's screen.
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
            // The first-time explanation leads to choosing a model; opened from the settings
            // gear, the user is already there.
            if (downloaded.isEmpty() && entry != AiEntry.SETTINGS) {
                uiState = ScanUiState.FirstTimeWarning
                return@launch
            }
            val selected = modelManager.selectedModel
            if (!modelManager.areModelsAvailable(selected)) {
                uiState = settingsState()
                return@launch
            }
            // A new AI engine version prepares the model on its first load, which is slow
            // (about a minute). Do it now, with a message, instead of during the first scan.
            if (modelManager.needsPreparation(selected)) {
                prepareModel(selected, isUpdate = true)
                return@launch
            }
            // Don't load the model yet — open the camera/picker (or settings). The model is
            // loaded lazily in onPhotoCaptured, once a photo exists.
            showReady(autoLaunch = autoLaunchAllowed)
        }
    }

    /**
     * Load [model] once so the engine does its one-time preparation, then release it again so
     * it isn't resident while the system camera opens; then show the ready screen. Shows
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
                // After a minute-long wait, offer the camera rather than springing it on the user.
                showReady(autoLaunch = false)
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
    private suspend fun ensureModelLoaded(model: AiModel, showLoading: Boolean = true): Boolean {
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
        if (showLoading) uiState = ScanUiState.ModelLoading()
        return try {
            solver.setVisualTokenBudget(budget)
            solver.loadModel(modelManager.modelPath(model))
            loadedModelId = model.id
            loadedBudget = budget
            // A successful load is the one-time preparation, whichever path got here; record it
            // so Retry and the next open don't prepare the model again.
            if (modelManager.needsPreparation(model)) modelManager.markPrepared(model)
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
            if (modelManager.needsPreparation(model)) {
                // Same as initialize(): do the slow one-time preparation now, with its message,
                // instead of silently during the first scan.
                prepareModel(model, isUpdate = true)
            } else {
                // Loaded lazily on first photo, not here. In settings, stay there with the new
                // selection; reached on the way to the camera, offer it with a button.
                showReady(autoLaunch = false)
            }
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
                            showReady(autoLaunch = false)
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
        startSolve(
            // Apply EXIF rotation + downscale: a full-res photo can blow up the vision pipeline's
            // memory, and the camera stores it sideways with an EXIF orientation tag the model
            // would otherwise ignore. If that fails, do NOT fall back to the original
            // full-resolution photo, which can OOM the vision pipeline: show an error instead.
            prepareInput = {
                withContext(Dispatchers.IO) { prepareImage(imagePath, MAX_IMAGE_EDGE) }.also {
                    if (it == null) uiState = ScanUiState.Error(appContext.getString(R.string.error_image_process))
                }
            },
            solve = { path, onToken -> solver.solveFromImageStreaming(path, onToken) },
            parseSteps = null,
            history = HistoryInput.Photo,
            cleanup = { processPath ->
                // Clean up captured photos
                try { java.io.File(imagePath).delete() } catch (_: Exception) {}
                if (processPath != null && processPath != imagePath) {
                    try { java.io.File(processPath).delete() } catch (_: Exception) {}
                }
            }
        )
    }

    /**
     * Solve the typed [problem] step by step (the Type screen's Solve). Kept as [typedProblem],
     * so Retry and Edit come back to it.
     */
    fun onTextSubmitted(problem: String) {
        val text = problem.trim()
        if (text.isEmpty()) return
        typedProblem = problem
        startSolve(
            prepareInput = { text },
            solve = { input, onToken -> solver.solveFromTextStreaming(input, onToken) },
            parseSteps = { raw -> SolverPrompts.parseSteps(raw, text) },
            history = HistoryInput.Typed(text),
            cleanup = {}
        )
    }

    /**
     * The shared scan: wait for a stopped one to finish, load the model, show Processing, get the
     * input from [prepareInput] (null = it set an error), stream [solve] with live stats, then
     * show the result. [parseSteps] (typed problems) turns the raw reply into steps; null means a
     * photo scan's single-number result. The engine is released afterwards, then [cleanup] runs
     * with the prepared input.
     */
    private fun startSolve(
        prepareInput: suspend () -> String?,
        solve: suspend (input: String, onToken: suspend (String) -> Unit) -> Result<RecognitionResult>,
        parseSteps: ((raw: String) -> SolvedSteps?)?,
        history: HistoryInput,
        cleanup: (preparedInput: String?) -> Unit,
    ) {
        val model = modelManager.selectedModel
        leaveAnswer()
        // Cancel any still-running inference before starting a new one, so an overlapping call
        // can't orphan the previous job (the ViewModel is Activity-scoped, so an un-cancelled job
        // keeps generating in the background). Keep the new Job so stopInference() can cancel it.
        val previousScan = inferenceJob
        previousScan?.cancel()
        inferenceJob = viewModelScope.launch {
            // Streaming UI updates launch from this scope (not viewModelScope) so cancelling the
            // inference also drops any in-flight token update, instead of it racing to overwrite
            // the Capturing state stopInference() returns to.
            val inferenceScope = this
            var input: String? = null
            // Set when the result can take questions: the engine then stays loaded for them.
            var keepModel = false
            try {
                // A stopped scan can still be inside its model load, which can't be interrupted.
                // When that load returns, the stopped scan's finally releases the engine. Wait
                // for that first: otherwise the release lands after this scan's load and closes
                // the engine this scan is about to use ("Model not loaded").
                // (isCompleted, not isActive: a cancelled Job is no longer "active" while it is
                // still finishing.)
                if (previousScan != null && !previousScan.isCompleted) {
                    uiState = ScanUiState.ModelLoading()
                    previousScan.join()
                }
                // Load the model now that a photo exists (it wasn't resident while the camera app
                // was open). Shows "Loading AI model…" then proceeds. Kept inside the try so the
                // finally releases the engine even if a stop cancels during the (uninterruptible)
                // native load — otherwise the just-loaded model would stay resident.
                if (!ensureModelLoaded(model)) return@launch
                val startTime = System.currentTimeMillis()
                uiState = ScanUiState.Processing(startTimeMs = startTime, backend = solver.activeBackend)
                input = prepareInput() ?: return@launch
                var tokenCount = 0
                var firstTokenMs = 0L
                var lastTokenMs = 0L
                var lastUiUpdateMs = 0L
                var lastRawLen = 0
                // When the attempt that produced the answer started: TTFT is measured from here.
                var attemptStartMs = startTime
                // Set once the result is shown; a throttled streaming update still queued on the
                // main thread must not then overwrite it with Processing. (Both run on Main.)
                var finished = false
                val result = solve(input) { partialRaw ->
                    // Time decode at the source (inference dispatcher), independent of the UI:
                    // the first token marks end-of-prefill (TTFT); the rest is the decode window.
                    val now = System.currentTimeMillis()
                    // The solver can silently reload GPU→CPU and replay inference with this same
                    // callback. It signals that with an empty string; restart the counters and the
                    // TTFT reference so the stats describe the attempt that produced the answer
                    // ("Solved in" still counts the whole wait). A shrinking output is treated
                    // the same, for solvers that don't send the signal.
                    if (partialRaw.isEmpty() || partialRaw.length < lastRawLen) {
                        tokenCount = 0
                        firstTokenMs = 0L
                        attemptStartMs = now
                        lastRawLen = 0
                        if (partialRaw.isEmpty()) return@solve
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
                        val uiAttemptStartMs = attemptStartMs
                        inferenceScope.launch {
                            if (finished) return@launch
                            uiState = ScanUiState.Processing(
                                partialRaw = partialRaw,
                                // The live prefill/TTFT readout times the current attempt.
                                startTimeMs = uiAttemptStartMs,
                                tokenCount = uiTokenCount,
                                backend = solver.activeBackend,
                                firstTokenMs = uiFirstTokenMs
                            )
                        }
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                val ttftMs = if (firstTokenMs > 0L) firstTokenMs - attemptStartMs else elapsed
                val decodeMs = (lastTokenMs - firstTokenMs).coerceAtLeast(0L)
                val decodeTps = if (tokenCount > 1 && decodeMs > 0L)
                    (tokenCount - 1) / (decodeMs / 1000.0) else 0.0
                finished = true
                uiState = result.fold(
                    onSuccess = {
                        val steps = parseSteps?.invoke(it.raw)
                        when {
                            // A typed problem's reply without an Answer line: show it to check.
                            parseSteps != null && steps == null -> ScanUiState.Error(
                                appContext.getString(R.string.error_no_numerical_answer),
                                it.raw, checkOutput = true
                            )
                            // Re-read the backend after inference — it may have fallen back GPU→CPU.
                            else -> ScanUiState.Success(
                                steps?.usableAnswer ?: it.answer, it.raw, elapsed, tokenCount,
                                solver.activeBackend, ttftMs, decodeTps, steps
                            )
                        }
                    },
                    onFailure = {
                        val raw = (it as? RecognitionException)?.rawResponse
                        val res = (it as? RecognitionException)?.messageRes ?: 0
                        val msg = if (res != 0) appContext.getString(res)
                            else it.message ?: appContext.getString(R.string.recognition_error)
                        ScanUiState.Error(msg, raw, checkOutput = res == R.string.error_no_numerical_answer)
                    }
                )
                // Into AI history, before the finally below cleans up the input (a photo's picture).
                currentAnswer = saveToHistory(uiState, history, input, model, elapsed)
                keepModel = currentAnswer?.let(::takesQuestions) == true
            } catch (e: Throwable) {
                // Let cancellation (e.g. user closed the screen) propagate instead
                // of showing it as an inference error.
                if (e is kotlinx.coroutines.CancellationException) throw e
                uiState = ScanUiState.Error(appContext.getString(R.string.error_inference_failed, e.message ?: ""))
            } finally {
                // Free the engine as soon as inference is done: it must NOT stay resident while
                // the system camera launches for the next scan (large models OOM-kill the app —
                // the reason model loading is deferred to here). It reloads lazily on the next
                // photo. An answer that can take questions keeps it until the user leaves it
                // ([leaveAnswer]), the app goes to the background ([stopInference]) or the last
                // question is answered.
                if (!keepModel) releaseAll()
                cleanup(input)
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
        // Keep the cancelled Job: it may still be finishing an uninterruptible model load, and
        // the next onPhotoCaptured waits for it before loading again.
        inferenceJob?.cancel()
        // Only snap back to capture if we were actually mid-scan; calling this from a dialog
        // dismiss in another state (Success, Downloading, …) must not disturb that state.
        // Not straight back into the camera: the user just said stop. A typed problem goes back
        // to its text.
        if (uiState is ScanUiState.Processing || uiState is ScanUiState.ModelLoading) {
            showReady(autoLaunch = false)
        }
        // Closing the window or leaving the app also ends a question and frees the engine an
        // answer was keeping; a later question reloads it. (A running solve frees it itself.)
        stopFollowUp()
        if (inferenceJob?.isActive != true) releaseAll()
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
        var outFile: java.io.File? = null
        val result = try {
            bmp = applyExifOrientation(imagePath, bmp)
            // Scale down further if still larger than maxEdge after sampling.
            val longest = maxOf(bmp.width, bmp.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            outFile = java.io.File(java.io.File(imagePath).parent, "scan_prepared_${System.nanoTime()}.jpg")
            val written = outFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            // compress() returns false instead of throwing when it can't encode: don't hand the
            // model an empty or truncated file.
            if (written) outFile.absolutePath else null
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
        // The caller only deletes a file whose path it got back: remove a failed one here.
        if (result == null) outFile?.delete()
        return result
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

    /** A photo picked from the gallery couldn't be read. */
    fun onPickedImageUnreadable() {
        uiState = ScanUiState.Error(appContext.getString(R.string.error_image_process))
    }

    fun onCaptureError(message: String) {
        uiState = ScanUiState.Error(appContext.getString(R.string.error_capture_failed, message))
    }

    fun retry() {
        leaveAnswer()
        // Reopen the same source (camera or picker) if the model is available (loaded lazily on
        // the next photo); otherwise re-run the first-time / download flow.
        // A model that still needs its one-time preparation goes through initialize(), which
        // prepares it (with the message) first.
        if (modelManager.areModelsAvailable() && !modelManager.needsPreparation(modelManager.selectedModel)) {
            showReady(autoLaunch = true)
        } else {
            initialize(entry)
        }
    }

    /** The AI settings page: model choice and speed. */
    fun showModelSelection() {
        uiState = settingsState()
    }

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
