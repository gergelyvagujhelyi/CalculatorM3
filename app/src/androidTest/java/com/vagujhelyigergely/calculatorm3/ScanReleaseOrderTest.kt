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
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * After a scan, ScanViewModel releases the model in the background. If the next scan starts
 * before that release has finished, the release must not close the engine the new scan just
 * loaded: the new scan has to wait for it. Uses a fake solver whose release() is held open.
 */
class ScanReleaseOrderTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Records load/release/solve, and holds the first release() open until [releaseGate]. */
    private class GatedSolver : MathSolver {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val releaseGate = CompletableDeferred<Unit>()
        @Volatile var loaded = true
        @Volatile var loadedWhenSolving: Boolean? = null
        private var releases = 0

        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) { events += "load"; loaded = true }
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> {
            loadedWhenSolving = loaded
            events += "solve"
            return Result.success(RecognitionResult(raw = "42", answer = "42"))
        }
        override suspend fun release() {
            val first = synchronized(this) { releases++ == 0 }
            events += "release-start"
            if (first) releaseGate.await()
            loaded = false
            events += "release-end"
        }
    }

    private fun photo(): String {
        val file = File(context.cacheDir, "release_order_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return file.path
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(25) }
        return condition()
    }

    private val created = mutableListOf<File>()

    @After
    fun tearDown() {
        created.forEach { it.delete() }
    }

    @Test
    fun nextScanWaitsForThePreviousReleaseBeforeLoading() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        val solver = GatedSolver()
        // Placeholder files so the selected model counts as downloaded (the fake solver never
        // reads them); only files created here are deleted afterwards.
        val manager = ModelManager(context)
        val model = AiModel.GEMMA4_E2B
        manager.selectedModel = model
        for (f in model.files) {
            val file = File(File(context.getExternalFilesDir(null), "models/${model.id}"), f.filename)
            if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file }
        }
        val vm = ScanViewModel(solver, manager, HuggingFaceAuthManager(context), context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // The previous scan finished and released the model in the background; that release is
        // still running (held open) when the next photo arrives.
        instrumentation.runOnMainSync { vm.releaseAll() }
        assertTrue(waitFor(5_000) { "release-start" in solver.events })
        instrumentation.runOnMainSync { vm.onPhotoCaptured(photo()) }

        // Give the new scan time to (wrongly) load while the release is still pending.
        Thread.sleep(1_000)
        solver.releaseGate.complete(Unit)

        assertTrue("the new scan never solved: ${solver.events}", waitFor(10_000) { "solve" in solver.events })
        val firstLoad = solver.events.indexOf("load")
        val firstReleaseEnd = solver.events.indexOf("release-end")
        assertTrue("loaded before the pending release finished: ${solver.events}",
            firstReleaseEnd in 0 until firstLoad)
        assertEquals("the engine was closed under the running scan", true, solver.loadedWhenSolving)
    }
}
