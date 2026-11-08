package com.vagujhelyigergely.calculatorm3

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.ScanUiState
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * The one-time "Updating to the new model format" step: opening the AI window with a model that
 * the current LiteRT-LM version hasn't prepared yet loads it once (showing the message), releases
 * it, and only then shows the capture chooser. Uses a fake solver and placeholder model files.
 */
class ScanPreparationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val model = AiModel.GEMMA4_E2B
    private lateinit var manager: ModelManager
    private val created = mutableListOf<File>()

    /** Records load/release; holds loadModel() open until [loadGate] so the message is observable. */
    private class FakeSolver : MathSolver {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val loadGate = CompletableDeferred<Unit>()
        @Volatile private var loaded = false
        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) { events += "load"; loadGate.await(); loaded = true }
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException())
        override suspend fun release() { events += "release"; loaded = false }
    }

    @Before
    fun setUp() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        manager = ModelManager(context)
        // Placeholder files so the model counts as downloaded (only ones we create are deleted).
        for (f in model.files) {
            val file = File(File(context.getExternalFilesDir(null), "models/${model.id}"), f.filename)
            if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file }
        }
        manager.selectedModel = model
        forgetPrepared()
    }

    @After
    fun tearDown() {
        created.forEach { it.delete() }
        if (::manager.isInitialized) forgetPrepared()
    }

    private fun forgetPrepared() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .remove("prepared_engine_version_${model.id}").commit()
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun newViewModel(solver: FakeSolver) =
        ScanViewModel(solver, manager, HuggingFaceAuthManager(context), context)

    @Test
    fun firstOpenAfterAnEngineUpdateShowsTheMessageUntilPrepared() {
        val solver = FakeSolver()
        val vm = newViewModel(solver)
        instrumentation.runOnMainSync { vm.initialize() }

        waitFor(5_000, "the preparing message") { vm.uiState is ScanUiState.PreparingModel }
        assertEquals(true, (vm.uiState as ScanUiState.PreparingModel).isUpdate)
        Thread.sleep(500)
        assertTrue("message must stay until preparation finishes", vm.uiState is ScanUiState.PreparingModel)

        solver.loadGate.complete(Unit)
        waitFor(5_000, "the capture chooser") { vm.uiState is ScanUiState.Capturing }
        assertEquals(listOf("load", "release"), solver.events.toList())
        assertFalse("the model is left unloaded for the camera", solver.isModelLoaded)
        assertFalse("prepared for this engine version", manager.needsPreparation(model))
    }

    @Test
    fun anAlreadyPreparedModelOpensStraightToTheCaptureChooser() {
        manager.markPrepared(model)
        val solver = FakeSolver()
        val vm = newViewModel(solver)
        instrumentation.runOnMainSync { vm.initialize() }
        waitFor(5_000, "the capture chooser") { vm.uiState is ScanUiState.Capturing }
        assertEquals(emptyList<String>(), solver.events.toList())
    }

    @Test
    fun reopeningTheWindowDuringPreparationDoesNotStartASecondOne() {
        val solver = FakeSolver()
        val vm = newViewModel(solver)
        instrumentation.runOnMainSync { vm.initialize() }
        waitFor(5_000, "the preparing message") { vm.uiState is ScanUiState.PreparingModel }

        instrumentation.runOnMainSync { vm.initialize() } // window closed and opened again
        Thread.sleep(300)
        assertTrue(vm.uiState is ScanUiState.PreparingModel)

        solver.loadGate.complete(Unit)
        waitFor(5_000, "the capture chooser") { vm.uiState is ScanUiState.Capturing }
        assertEquals("exactly one preparation", listOf("load", "release"), solver.events.toList())
    }
}
