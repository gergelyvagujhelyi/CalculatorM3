package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.ScanUiState
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The speed stats after a silent GPU→CPU retry describe the attempt that produced the answer:
 * the solver signals the restart with an empty partial output, and token count and TTFT start
 * again from there. Uses a fake solver that streams a failed attempt, then a retry.
 */
class ScanStatsTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = AiModel.GEMMA4_E2B
    private val created = mutableListOf<File>()

    /** Streams three chunks, waits [retryDelayMs] (the CPU reload), signals a restart, streams two. */
    private class RetryingSolver(private val retryDelayMs: Long) : MathSolver {
        override val isModelLoaded = true
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) {}
        override suspend fun solveFromImageStepsStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> {
            onToken("1"); onToken("12"); onToken("123")
            delay(retryDelayMs)
            onToken("")
            onToken("4"); onToken("42")
            return Result.success(RecognitionResult(raw = "Problem: 6 × 7\nAnswer: 42", answer = ""))
        }
        override suspend fun release() {}
    }

    private fun photo(): String {
        val file = File(context.cacheDir, "stats_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return file.path
    }

    @After
    fun tearDown() {
        created.forEach { it.delete() }
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .remove("prepared_engine_version_${model.id}").commit()
    }

    @Test
    fun statsAfterARetryDescribeTheRetryOnly() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        val manager = ModelManager(context)
        manager.selectedModel = model
        for (f in model.files) {
            val file = File(File(context.getExternalFilesDir(null), "models/${model.id}"), f.filename)
            if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file }
        }
        val retryDelayMs = 600L
        val vm = ScanViewModel(RetryingSolver(retryDelayMs), manager, HuggingFaceAuthManager(context), context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.onPhotoCaptured(photo()) }

        val end = System.currentTimeMillis() + 10_000
        while (vm.uiState !is ScanUiState.Success && System.currentTimeMillis() < end) Thread.sleep(25)
        val success = vm.uiState as? ScanUiState.Success ?: throw AssertionError("no answer: ${vm.uiState}")
        assertEquals("tokens of the retry only", 2, success.tokenCount)
        assertTrue("TTFT counted from the retry, not the first attempt: ${success.ttftMs} ms",
            success.ttftMs < retryDelayMs / 2)
        assertTrue("'Solved in' still covers the whole wait: ${success.elapsedMs} ms",
            success.elapsedMs >= retryDelayMs)
    }
}
