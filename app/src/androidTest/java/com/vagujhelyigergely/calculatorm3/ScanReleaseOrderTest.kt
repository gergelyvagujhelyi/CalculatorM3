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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        override suspend fun solveFromImageStepsStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> {
            loadedWhenSolving = loaded
            events += "solve"
            return Result.success(RecognitionResult(raw = "Problem: 6 × 7\nAnswer: 42", answer = ""))
        }
        override suspend fun release() {
            val first = synchronized(this) { releases++ == 0 }
            events += "release-start"
            if (first) releaseGate.await()
            loaded = false
            events += "release-end"
        }
    }

    /**
     * Like the real LiteRTSolver: load, release and solve share one lock, and a load can't be
     * interrupted (the native call runs to the end; the caller's cancellation only shows once it
     * returns, with the engine loaded). The first load is held open until [loadGate].
     */
    private class LockedSolver : MathSolver {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val loadGate = CompletableDeferred<Unit>()
        private val mutex = Mutex()
        @Volatile var loaded = false
        @Volatile var loadedWhenSolving: Boolean? = null
        private var loads = 0

        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) = mutex.withLock {
            val first = synchronized(this) { loads++ == 0 }
            withContext(NonCancellable) {
                events += "load-start"
                if (first) loadGate.await()
                loaded = true
                events += "load-end"
            }
            currentCoroutineContext().ensureActive()
        }
        override suspend fun solveFromImageStepsStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = mutex.withLock {
            loadedWhenSolving = loaded
            events += "solve"
            if (loaded) Result.success(RecognitionResult(raw = "Problem: 6 × 7\nAnswer: 42", answer = ""))
            else Result.failure(IllegalStateException("Model not loaded"))
        }
        override suspend fun release() = mutex.withLock {
            loaded = false
            events += "release"
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
        // A successful (fake) load records the model as prepared; don't leave that for other tests.
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
            .remove("prepared_engine_version_${AiModel.GEMMA4_E2B.id}").commit()
    }

    @Test
    fun aScanStoppedDuringItsLoadDoesNotCloseTheNextScansEngine() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        val solver = LockedSolver()
        installPlaceholderModel()
        val vm = ScanViewModel(solver, ModelManager(context), HuggingFaceAuthManager(context), context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Scan A starts loading the model (slow, can't be interrupted) and the user stops it.
        instrumentation.runOnMainSync { vm.onPhotoCaptured(photo()) }
        assertTrue(waitFor(5_000) { "load-start" in solver.events })
        instrumentation.runOnMainSync { vm.stopInference() }
        // Right away the user picks another photo: scan B.
        instrumentation.runOnMainSync { vm.onPhotoCaptured(photo()) }
        Thread.sleep(500)
        // A's load finishes now. A's cleanup then releases the engine; that release must not
        // land between B's load and B's solve.
        solver.loadGate.complete(Unit)

        assertTrue("scan B never solved: ${solver.events}", waitFor(10_000) { "solve" in solver.events })
        assertEquals("the engine was closed under scan B: ${solver.events}", true, solver.loadedWhenSolving)
    }

    private fun installPlaceholderModel() {
        // Placeholder files so the selected model counts as downloaded (the fake solver never
        // reads them); only files created here are deleted afterwards.
        val manager = ModelManager(context)
        val model = AiModel.GEMMA4_E2B
        manager.selectedModel = model
        for (f in model.files) {
            val file = File(File(context.getExternalFilesDir(null), "models/${model.id}"), f.filename)
            if (!file.exists()) { file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file }
        }
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
