package com.vagujhelyigergely.calculatorm3

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.AiEntry
import com.vagujhelyigergely.calculatorm3.camera.InkPoint
import com.vagujhelyigergely.calculatorm3.camera.InkStroke
import com.vagujhelyigergely.calculatorm3.camera.ScanUiState
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Where the AI window goes for each AI-menu choice: Camera and Gallery open their source once
 * (autoLaunch), the gear opens the settings page and stays there. Uses a fake solver and
 * placeholder model files.
 */
class AiEntryRoutingTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val model = AiModel.GEMMA4_E2B
    private lateinit var manager: ModelManager
    private val created = mutableListOf<File>()

    /**
     * Holds loadModel() open until [loadGate], so the preparation can be finished on cue. A typed
     * problem gets [textReply], after waiting for [textGate] if one is set (so Stop can land).
     */
    private class FakeSolver(
        val textReply: String = "Problem: 6 × 7\n1. 6 × 7 = 42\nAnswer: 42",
        val textGate: CompletableDeferred<Unit>? = null,
    ) : MathSolver {
        val loadGate = CompletableDeferred<Unit>()
        val solvedTexts = mutableListOf<String>()
        /** The drawings sent to the model: (path, whether that file existed at the time). */
        val solvedImages = mutableListOf<Pair<String, Boolean>>()
        @Volatile private var loaded = false
        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) { loadGate.await(); loaded = true }
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException())
        override suspend fun solveFromTextStreaming(
            problem: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> {
            solvedTexts += problem
            onToken("Problem:")
            textGate?.await()
            return Result.success(RecognitionResult(textReply, ""))
        }
        override suspend fun solveFromImageStepsStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> {
            solvedImages += imagePath to File(imagePath).exists()
            onToken("Problem:")
            textGate?.await()
            return Result.success(RecognitionResult(textReply, ""))
        }
        override suspend fun release() { loaded = false }
    }

    @Before
    fun setUp() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        manager = ModelManager(context)
        for (f in model.files) {
            val file = File(File(context.getExternalFilesDir(null), "models/${model.id}"), f.filename)
            if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file }
        }
        manager.selectedModel = model
        manager.markPrepared(model)
    }

    @After
    fun tearDown() {
        created.forEach { it.delete() }
        if (::manager.isInitialized) {
            context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
                .remove("prepared_engine_version_${model.id}").commit()
        }
    }

    private var lastVm: ScanViewModel? = null

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        throw AssertionError("timed out waiting for: $what (state: ${lastVm?.uiState})")
    }

    private fun open(entry: AiEntry, solver: FakeSolver = FakeSolver(), resumed: Boolean = false) =
        ScanViewModel(solver, manager, HuggingFaceAuthManager(context), context).also { vm ->
            lastVm = vm
            instrumentation.runOnMainSync { vm.initialize(entry, resumed) }
        }

    @Test
    fun cameraAndGalleryOpenTheirSourceStraightAway() {
        for (entry in listOf(AiEntry.CAMERA, AiEntry.GALLERY)) {
            val vm = open(entry)
            waitFor("the capture state for $entry") { vm.uiState is ScanUiState.Capturing }
            assertEquals(ScanUiState.Capturing(autoLaunch = true), vm.uiState)
            assertEquals(entry, vm.entry)
        }
    }

    @Test
    fun theSourceOpensOnlyOnceButRetryOpensItAgain() {
        val vm = open(AiEntry.GALLERY)
        waitFor("the capture state") { vm.uiState is ScanUiState.Capturing }
        instrumentation.runOnMainSync { vm.onCaptureLaunched() }
        assertEquals("a rotation must not reopen the picker",
            ScanUiState.Capturing(autoLaunch = false), vm.uiState)

        instrumentation.runOnMainSync { vm.retry() }
        assertEquals(ScanUiState.Capturing(autoLaunch = true), vm.uiState)
        assertEquals(AiEntry.GALLERY, vm.entry)
    }

    @Test
    fun aRecreatedWindowDoesNotOpenTheCameraOnTopOfItsPendingResult() {
        val vm = open(AiEntry.CAMERA, resumed = true)
        waitFor("the capture state") { vm.uiState is ScanUiState.Capturing }
        assertEquals(ScanUiState.Capturing(autoLaunch = false), vm.uiState)
    }

    @Test
    fun theGearOpensSettingsAndPickingAModelStaysThere() {
        val vm = open(AiEntry.SETTINGS)
        waitFor("the settings page") { vm.uiState is ScanUiState.ModelSelection }
        instrumentation.runOnMainSync { vm.selectModel(model) }
        val state = vm.uiState
        assertTrue("still on settings, not the camera: $state", state is ScanUiState.ModelSelection)
        assertEquals(model, (state as ScanUiState.ModelSelection).selectedModel)
    }

    @Test
    fun afterTheOneMinutePreparationTheCameraIsOfferedNotOpened() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .remove("prepared_engine_version_${model.id}").commit()
        val solver = FakeSolver()
        val vm = open(AiEntry.CAMERA, solver)
        waitFor("the preparing message") { vm.uiState is ScanUiState.PreparingModel }
        solver.loadGate.complete(Unit)
        waitFor("the capture state") { vm.uiState is ScanUiState.Capturing }
        assertEquals(ScanUiState.Capturing(autoLaunch = false), vm.uiState)
    }

    @Test
    fun aPreparationStartedFromSettingsEndsOnSettings() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .remove("prepared_engine_version_${model.id}").commit()
        val solver = FakeSolver()
        val vm = open(AiEntry.SETTINGS, solver)
        waitFor("the preparing message") { vm.uiState is ScanUiState.PreparingModel }
        solver.loadGate.complete(Unit)
        waitFor("the settings page") { vm.uiState is ScanUiState.ModelSelection }
    }

    // ── Type ────────────────────────────────────────────────────────────────

    private fun solveTyped(vm: ScanViewModel, text: String) {
        instrumentation.runOnMainSync {
            vm.updateTypedProblem(text)
            vm.onTextSubmitted(text)
        }
    }

    @Test
    fun typeOpensTheTypeScreen() {
        val vm = open(AiEntry.TYPE)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
    }

    @Test
    fun aTypedProblemIsSolvedStepByStepAndItsNumberOffered() {
        val solver = FakeSolver().apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.TYPE, solver)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
        solveTyped(vm, "  6 × 7  ")
        waitFor("the answer") { vm.uiState is ScanUiState.Success }
        val state = vm.uiState as ScanUiState.Success
        assertEquals(listOf("6 × 7"), solver.solvedTexts)
        assertEquals("42", state.answer)
        assertEquals("42", state.steps?.usableAnswer)
        assertEquals("1. 6 × 7 = 42", state.steps?.steps)
    }

    @Test
    fun anEquationIsAnsweredButNotOfferedForUse() {
        val solver = FakeSolver(textReply = "Problem: 3x + 5 = 20\n1. 3x = 15\nAnswer: x = 5")
            .apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.TYPE, solver)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
        solveTyped(vm, "3x + 5 = 20")
        waitFor("the answer") { vm.uiState is ScanUiState.Success }
        val state = vm.uiState as ScanUiState.Success
        assertEquals("x = 5", state.steps?.answer)
        assertEquals("", state.answer)
    }

    @Test
    fun aReplyWithoutAnAnswerLineIsShownToCheck() {
        val solver = FakeSolver(textReply = "I think it is about forty.").apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.TYPE, solver)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
        solveTyped(vm, "6 × 7")
        waitFor("the check-output error") { vm.uiState is ScanUiState.Error }
        val state = vm.uiState as ScanUiState.Error
        assertTrue(state.checkOutput)
        assertEquals("I think it is about forty.", state.rawResponse)
    }

    @Test
    fun editAndRetryGoBackToTheTypedProblem() {
        val solver = FakeSolver().apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.TYPE, solver)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
        solveTyped(vm, "6 × 7")
        waitFor("the answer") { vm.uiState is ScanUiState.Success }
        instrumentation.runOnMainSync { vm.editTypedProblem() }
        assertEquals(ScanUiState.Typing, vm.uiState)
        assertEquals("6 × 7", vm.typedProblem)
        instrumentation.runOnMainSync { vm.retry() }
        assertEquals(ScanUiState.Typing, vm.uiState)
        assertEquals("6 × 7", vm.typedProblem)
    }

    @Test
    fun stoppingATypedSolveGoesBackToTheText() {
        val gate = CompletableDeferred<Unit>()
        val solver = FakeSolver(textGate = gate).apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.TYPE, solver)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
        solveTyped(vm, "6 × 7")
        waitFor("the solve") { vm.uiState is ScanUiState.Processing }
        instrumentation.runOnMainSync { vm.stopInference() }
        assertEquals(ScanUiState.Typing, vm.uiState)
        assertEquals("6 × 7", vm.typedProblem)
        gate.complete(Unit)
    }

    @Test
    fun blankTextIsNotSolved() {
        val solver = FakeSolver().apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.TYPE, solver)
        waitFor("the Type screen") { vm.uiState is ScanUiState.Typing }
        solveTyped(vm, "   ")
        Thread.sleep(200)
        assertEquals(ScanUiState.Typing, vm.uiState)
        assertTrue(solver.solvedTexts.isEmpty())
    }

    // ── Write ───────────────────────────────────────────────────────────────

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        InkStroke(listOf(InkPoint(x0, y0), InkPoint((x0 + x1) / 2, (y0 + y1) / 2), InkPoint(x1, y1)))

    private fun ScanViewModel.draw(vararg strokes: InkStroke) =
        instrumentation.runOnMainSync { strokes.forEach { addStroke(it) } }

    @Test
    fun aDrawingIsSentAsAPictureAndAnsweredStepByStep() {
        val solver = FakeSolver(textReply = "Problem: 7 + 5\n1. 7 + 5 = 12\nAnswer: 12")
            .apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.WRITE, solver)
        waitFor("the pad") { vm.uiState is ScanUiState.Drawing }
        vm.draw(line(0.1f, 0.3f, 0.2f, 0.3f), line(0.15f, 0.25f, 0.15f, 0.35f))
        instrumentation.runOnMainSync { vm.onDrawingSubmitted() }
        waitFor("the answer") { vm.uiState is ScanUiState.Success }
        val state = vm.uiState as ScanUiState.Success
        assertEquals("7 + 5", state.steps?.problem)
        assertEquals("12", state.answer)
        val (path, existed) = solver.solvedImages.single()
        assertTrue("the picture existed when solved", existed)
        waitFor("the picture deleted") { !File(path).exists() }
        assertEquals("the drawing is kept for Draw again", 2, vm.strokes.size)
    }

    @Test
    fun anEmptyPadIsNotSolved() {
        val solver = FakeSolver().apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.WRITE, solver)
        waitFor("the pad") { vm.uiState is ScanUiState.Drawing }
        instrumentation.runOnMainSync { vm.clearInk(); vm.onDrawingSubmitted() }
        Thread.sleep(200)
        assertEquals(ScanUiState.Drawing, vm.uiState)
        assertTrue(solver.solvedImages.isEmpty())
    }

    @Test
    fun undoRedoEraseAndClearEditTheDrawing() {
        val vm = open(AiEntry.WRITE)
        instrumentation.runOnMainSync { vm.clearInk() }
        val a = line(0.1f, 0.1f, 0.3f, 0.1f)
        val b = line(0.1f, 0.5f, 0.3f, 0.5f)
        vm.draw(a, b)
        instrumentation.runOnMainSync { vm.undo() }
        assertEquals(listOf(a), vm.strokes.toList())
        assertTrue(vm.canRedo)
        instrumentation.runOnMainSync { vm.redo() }
        assertEquals(listOf(a, b), vm.strokes.toList())
        assertEquals(false, vm.canRedo)
        // The eraser takes the stroke it touches, and a new edit drops what could be redone.
        instrumentation.runOnMainSync { vm.undo() }
        instrumentation.runOnMainSync { assertTrue(vm.eraseAt(InkPoint(0.2f, 0.1f), 0.02f)) }
        assertTrue(vm.strokes.isEmpty())
        assertEquals(false, vm.canRedo)
        vm.draw(a, b)
        instrumentation.runOnMainSync { vm.clearInk() }
        assertTrue(vm.strokes.isEmpty())
    }

    @Test
    fun drawAgainStopAndRetryGoBackToTheDrawing() {
        val gate = CompletableDeferred<Unit>()
        val solver = FakeSolver(textGate = gate).apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.WRITE, solver)
        waitFor("the pad") { vm.uiState is ScanUiState.Drawing }
        vm.draw(line(0.1f, 0.3f, 0.2f, 0.3f))
        instrumentation.runOnMainSync { vm.onDrawingSubmitted() }
        waitFor("the solve") { vm.uiState is ScanUiState.Processing }
        instrumentation.runOnMainSync { vm.stopInference() }
        assertEquals(ScanUiState.Drawing, vm.uiState)
        instrumentation.runOnMainSync { vm.retry() }
        assertEquals(ScanUiState.Drawing, vm.uiState)
        instrumentation.runOnMainSync { vm.drawAgain() }
        assertEquals(ScanUiState.Drawing, vm.uiState)
        assertEquals(1, vm.strokes.size)
        gate.complete(Unit)
    }

    @Test
    fun fixAsTextContinuesAsATypedProblem() {
        val solver = FakeSolver(textReply = "Problem: 7 + 3\n1. 7 + 3 = 10\nAnswer: 10")
            .apply { loadGate.complete(Unit) }
        val vm = open(AiEntry.WRITE, solver)
        waitFor("the pad") { vm.uiState is ScanUiState.Drawing }
        vm.draw(line(0.1f, 0.3f, 0.2f, 0.3f))
        instrumentation.runOnMainSync { vm.onDrawingSubmitted() }
        waitFor("the answer") { vm.uiState is ScanUiState.Success }
        // It read 7 + 3; the user meant 7 + 5 and corrects it as text.
        instrumentation.runOnMainSync { vm.fixAsText("7 + 3") }
        assertEquals(ScanUiState.Typing, vm.uiState)
        assertEquals(AiEntry.TYPE, vm.entry)
        assertEquals("7 + 3", vm.typedProblem)
    }

    @Test
    fun aStylusMakesFingersStopDrawing() {
        val vm = open(AiEntry.WRITE)
        assertEquals(false, vm.stylusOnly)
        instrumentation.runOnMainSync { vm.onStylusSeen() }
        assertTrue(vm.stylusOnly)
    }
}
