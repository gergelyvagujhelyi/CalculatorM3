package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.LiteRTSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.ScanUiState
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real engine-update scenario: a model downloaded and used by an older LiteRT-LM build is
 * opened with this build. The AI window must show "Updating to the new model format" for the
 * slow one-time preparation, end on the capture chooser, and the next load must be fast.
 *
 * Opt-in (`realModel=true`, `allowDataWrites=true`): needs the model already downloaded, e.g. by
 * running RealModelExtractionTest with an older build first, in the isolated -PdeviceTest app.
 */
class RealModelPreparationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun engineUpdateIsPreparedBehindTheMessageAndTheNextLoadIsFast() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real model run; pass realModel=true and allowDataWrites=true",
            args.getString("realModel") == "true" && args.getString("allowDataWrites") == "true")
        val model = AiModel.GEMMA4_E2B
        val manager = ModelManager(context)
        assumeTrue("needs the model downloaded by an earlier build", manager.areModelsAvailable(model))
        manager.selectedModel = model

        val vm = ScanViewModel(LiteRTSolver(), manager, HuggingFaceAuthManager(context), context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.initialize() }

        val shownAt = waitFor(10_000) { vm.uiState is ScanUiState.PreparingModel }
        val state = vm.uiState as ScanUiState.PreparingModel
        Log.i(TAG, "message shown: isUpdate=${state.isUpdate}")
        assertTrue("an engine update must say 'Updating to the new model format'", state.isUpdate)
        val doneAt = waitFor(300_000) { vm.uiState is ScanUiState.Capturing }
        val prepMs = doneAt - shownAt
        Log.i(TAG, "preparation (message on screen): $prepMs ms")
        assertFalse(manager.needsPreparation(model))

        // What the first scan now pays to load the model.
        val solver = LiteRTSolver()
        val t0 = System.currentTimeMillis()
        runBlocking { solver.loadModel(manager.modelPath(model)) }
        val loadMs = System.currentTimeMillis() - t0
        runBlocking { solver.release() }
        Log.i(TAG, "next load after preparation: $loadMs ms on ${solver.activeBackend}")
        assertTrue("the next load should be the fast one ($loadMs ms vs preparation $prepMs ms)", loadMs < prepMs)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Long {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return System.currentTimeMillis()
            Thread.sleep(50)
        }
        throw AssertionError("timed out after ${timeoutMs / 1000}s")
    }

    companion object { const val TAG = "RealModelPrep" }
}
