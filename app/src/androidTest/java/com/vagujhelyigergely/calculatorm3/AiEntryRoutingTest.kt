package com.vagujhelyigergely.calculatorm3

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.AiEntry
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

    /** Holds loadModel() open until [loadGate], so the preparation can be finished on cue. */
    private class FakeSolver : MathSolver {
        val loadGate = CompletableDeferred<Unit>()
        @Volatile private var loaded = false
        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) { loadGate.await(); loaded = true }
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException())
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

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun open(entry: AiEntry, solver: FakeSolver = FakeSolver(), resumed: Boolean = false) =
        ScanViewModel(solver, manager, HuggingFaceAuthManager(context), context).also { vm ->
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
}
