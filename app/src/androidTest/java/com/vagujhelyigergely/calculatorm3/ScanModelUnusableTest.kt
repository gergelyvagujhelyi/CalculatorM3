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
 * A model that is deleted outside the app, or can't be loaded, leads to a screen that offers to
 * download it again, and "Download again" really removes the old files first. Uses a fake solver
 * and placeholder model files; the test is skipped if a real model is installed.
 */
class ScanModelUnusableTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val model = AiModel.GEMMA4_E2B
    private lateinit var manager: ModelManager
    private val created = mutableListOf<File>()
    private val modelDir get() = File(context.getExternalFilesDir(null), "models/${model.id}")

    private class FakeSolver(private val failLoad: Boolean) : MathSolver {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile private var loaded = false
        override val isModelLoaded get() = loaded
        override val activeBackend = "fake"
        override suspend fun loadModel(modelPath: String) {
            events += "load"
            if (failLoad) throw IllegalStateException("Unable to open zip archive.")
            loaded = true
        }
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException())
        override suspend fun release() { events += "release"; loaded = false }
    }

    @Before
    fun setUp() {
        assumeTrue("Writes app data; run with allowDataWrites=true",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true")
        // The tests delete the model's files: never run them against a real download.
        assumeTrue("a real model is installed", model.files.none { File(modelDir, it.filename).exists() })
        manager = ModelManager(context)
        for (f in model.files) {
            val file = File(modelDir, f.filename)
            file.parentFile!!.mkdirs(); file.writeText("placeholder"); created += file
        }
        manager.selectedModel = model
    }

    @After
    fun tearDown() {
        if (!::manager.isInitialized) return
        created.forEach { it.delete() }
        manager.deleteModel(model)
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(25) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun newViewModel(solver: FakeSolver) =
        ScanViewModel(solver, manager, HuggingFaceAuthManager(context), context)

    @Test
    fun aModelThatFailsToLoadOffersToDownloadItAgain() {
        val solver = FakeSolver(failLoad = true)
        val vm = newViewModel(solver)
        instrumentation.runOnMainSync { vm.initialize() } // unprepared, so it loads right away

        waitFor(5_000, "the unusable-model screen") { vm.uiState is ScanUiState.ModelUnusable }
        val state = vm.uiState as ScanUiState.ModelUnusable
        assertFalse("the file is there, just unloadable", state.missing)
        assertEquals("Unable to open zip archive.", state.detail)
        assertEquals("the failed load is released", listOf("load", "release"), solver.events.toList())
        assertTrue("not recorded as prepared", manager.needsPreparation(model))

        // A partial download left next to it is removed too.
        File(modelDir, "${model.primaryFilename}.tmp").writeText("partial")
        var started: AiModel? = null
        instrumentation.runOnMainSync { vm.redownload(model) { started = it } }
        waitFor(5_000, "the download to be started") { started != null }
        assertEquals(model, started)
        assertFalse("old files deleted before downloading again", modelDir.exists())
        assertFalse(manager.isModelDownloaded(model))
    }

    @Test
    fun aModelDeletedWhileTheWindowIsOpenSaysItIsMissing() {
        manager.markPrepared(model)
        val solver = FakeSolver(failLoad = false)
        val vm = newViewModel(solver)
        instrumentation.runOnMainSync { vm.initialize() }
        waitFor(5_000, "the capture chooser") { vm.uiState is ScanUiState.Capturing }

        // Deleted with a file manager, then a photo is taken.
        created.forEach { it.delete() }
        val photo = File(context.cacheDir, "unusable_test_photo.jpg").apply { writeText("x") }
        instrumentation.runOnMainSync { vm.onPhotoCaptured(photo.absolutePath) }

        waitFor(5_000, "the missing-model screen") { vm.uiState is ScanUiState.ModelUnusable }
        assertTrue((vm.uiState as ScanUiState.ModelUnusable).missing)
        assertFalse("no load of a missing file is attempted", "load" in solver.events)
    }

    @Test
    fun retryAfterAFailedPreparationPreparesAgain() {
        val solver = FakeSolver(failLoad = true)
        val vm = newViewModel(solver)
        instrumentation.runOnMainSync { vm.initialize() }
        waitFor(5_000, "the unusable-model screen") { vm.uiState is ScanUiState.ModelUnusable }

        instrumentation.runOnMainSync { vm.retry() }
        waitFor(5_000, "a second load attempt") { solver.events.count { it == "load" } == 2 }
        waitFor(5_000, "the unusable-model screen again") { vm.uiState is ScanUiState.ModelUnusable }
    }
}
