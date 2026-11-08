package com.vagujhelyigergely.calculatorm3

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.ScanUiState
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * The speed/quality switch reaches the engine: the image-token budget in force when the model
 * loads is the one for the chosen [ScanQuality], and Gemma 3n models always get the engine
 * default. Uses a fake solver and placeholder model files; never touches a real model.
 */
class ScanQualityTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var manager: ModelManager
    private val created = mutableListOf<File>()
    private var savedQuality: ScanQuality? = null
    private var savedModel: AiModel? = null

    /** Records the budget each load was made with ("load:70", "load:null"). */
    private class FakeSolver : MathSolver {
        val loads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile private var budget: Int? = null
        @Volatile private var loaded = false
        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override fun setVisualTokenBudget(budget: Int?) { this.budget = budget }
        override suspend fun loadModel(modelPath: String) { loads += "load:$budget"; loaded = true }
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.success(RecognitionResult(raw = "42", answer = "42"))
        override suspend fun release() { loaded = false }
    }

    @Before
    fun setUp() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        manager = ModelManager(context)
        savedQuality = manager.scanQuality
        savedModel = manager.selectedModel
    }

    @After
    fun tearDown() {
        if (!::manager.isInitialized) return
        created.forEach { it.delete() }
        savedQuality?.let { manager.scanQuality = it }
        savedModel?.let { manager.selectedModel = it }
    }

    /** Placeholder files so [model] counts as downloaded and already prepared. */
    private fun install(model: AiModel) {
        assumeTrue("a real ${model.id} is installed; this test only uses placeholders",
            model.files.none { File(File(context.getExternalFilesDir(null), "models/${model.id}"), it.filename).exists() })
        for (f in model.files) {
            val file = File(File(context.getExternalFilesDir(null), "models/${model.id}"), f.filename)
            if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file }
        }
        manager.selectedModel = model
        manager.markPrepared(model)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        throw AssertionError("timed out waiting for: $what")
    }

    /** Opens the capture screen, scans one photo, and returns the loads the fake recorded. */
    private fun scanOnce(configure: (ScanViewModel) -> Unit = {}): List<String> {
        val solver = FakeSolver()
        val vm = ScanViewModel(solver, manager, HuggingFaceAuthManager(context), context)
        instrumentation.runOnMainSync { vm.initialize() }
        waitFor("the capture screen") { vm.uiState is ScanUiState.Capturing }
        instrumentation.runOnMainSync { configure(vm) }
        val photo = File(context.cacheDir, "quality_${System.nanoTime()}.jpg").apply { writeText("x") }
        instrumentation.runOnMainSync { vm.onPhotoCaptured(photo.path) }
        waitFor("a model load") { solver.loads.isNotEmpty() }
        return solver.loads.toList()
    }

    @Test
    fun accurateIsTheDefaultAndKeepsTheEngineDefault() {
        install(AiModel.GEMMA4_E2B)
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .remove("scan_quality").commit()
        assertEquals(ScanQuality.ACCURATE, ModelManager(context).scanQuality)
        assertEquals(listOf("load:null"), scanOnce())
    }

    @Test
    fun fastAndBalancedSetTheBudgetAndAreRemembered() {
        install(AiModel.GEMMA4_E2B)
        assertEquals(listOf("load:70"), scanOnce { it.updateScanQuality(ScanQuality.FAST) })
        assertEquals(ScanQuality.FAST, ModelManager(context).scanQuality)
        assertEquals(listOf("load:140"), scanOnce { it.updateScanQuality(ScanQuality.BALANCED) })
        assertEquals(ScanQuality.BALANCED, ModelManager(context).scanQuality)
    }

    @Test
    fun gemma3nAlwaysUsesTheEngineDefault() {
        install(AiModel.GEMMA3N_E2B)
        manager.scanQuality = ScanQuality.FAST
        val loads = scanOnce { assertEquals(false, it.scanQualityApplies) }
        assertEquals(listOf("load:null"), loads)
    }
}
