package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.LiteRTSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
import com.vagujhelyigergely.calculatorm3.ai.SolverPrompts
import com.vagujhelyigergely.calculatorm3.camera.InkRasterizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * The handwriting pad end to end on the real model: strokes ([InkGlyphs]) → [InkRasterizer] →
 * Gemma 4 E2B with the step-by-step prompt, at each speed setting. What reaches "Use" must be
 * the right number or nothing. Logs answers and timings under [TAG].
 *
 * Opt-in like [RealModelTextTest]: `realModel=true` and `allowDataWrites=true`, with the
 * `-PdeviceTest` build, and the model already in that app's storage.
 */
class RealModelInkTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private data class Case(val written: String, val expected: String)

    private val cases = listOf(
        Case("7+5", "12"),
        Case("8*6", "48"),
        Case("34-9", "25"),
        Case("12+7=", "19"),
        Case("96-48", "48"),
        Case("5*4+3", "23"),
    )

    @Test
    fun handwrittenInkIsReadAndSolvedAtEachSpeed() = runBlocking {
        assumeTrue("Heavy on-device model run; pass realModel=true and allowDataWrites=true",
            args.getString("realModel") == "true" && args.getString("allowDataWrites") == "true")
        val model = AiModel.GEMMA4_E2B
        val manager = ModelManager(context)
        assumeTrue("Model not in this app's storage", manager.areModelsAvailable(model))

        val wrong = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        for (quality in listOf(ScanQuality.ACCURATE, ScanQuality.FAST)) {
            val solver = LiteRTSolver()
            solver.setVisualTokenBudget(quality.visualTokenBudget)
            solver.loadModel(manager.modelPath(model))
            var correct = 0
            var totalMs = 0L
            try {
                cases.forEachIndexed { i, case ->
                    val file = InkRasterizer.writeJpeg(InkGlyphs.write(case.written, seed = i), context.cacheDir, 768)!!
                    val start = System.currentTimeMillis()
                    var firstTokenMs = -1L
                    val result = solver.solveFromImageStepsStreaming(file.path) {
                        if (firstTokenMs < 0 && it.isNotEmpty()) firstTokenMs = System.currentTimeMillis() - start
                    }
                    val ms = System.currentTimeMillis() - start
                    totalMs += ms
                    file.delete()
                    val raw = result.getOrNull()?.raw
                    val solved = raw?.let { SolverPrompts.parseSteps(it, "") }
                    val usable = solved?.usableAnswer.orEmpty()
                    val outcome = when {
                        solved == null -> { unparsed += "$quality ${case.written}"; "UNPARSED (${result.exceptionOrNull()?.message})" }
                        usable.isEmpty() -> "NO USE (answer '${solved.answer}')"
                        BigDecimal(usable).compareTo(BigDecimal(case.expected)) == 0 -> { correct++; "CORRECT" }
                        else -> { wrong += "$quality ${case.written} -> $usable"; "WRONG" }
                    }
                    Log.i(TAG, "$quality [${case.written}] read '${solved?.problem}' $outcome in $ms ms (first token $firstTokenMs ms)")
                    Log.i(TAG, "$quality [${case.written}] raw: ${raw?.replace("\n", " ⏎ ")?.take(500)}")
                }
            } finally {
                solver.release()
            }
            Log.i(TAG, "SUMMARY $quality correct=$correct of ${cases.size}, ${totalMs / cases.size} ms per problem")
        }
        assertTrue("A wrong number reached Use: $wrong", wrong.isEmpty())
        assertTrue("Replies not in the step format: $unparsed", unparsed.isEmpty())
    }

    /** Save the pictures the model sees, for a look by eye (pass saveInk=true). */
    @Test
    fun saveTheRenderedInk() {
        assumeTrue(args.getString("saveInk") == "true")
        cases.forEachIndexed { i, case ->
            val file = InkRasterizer.writeJpeg(InkGlyphs.write(case.written, seed = i), context.cacheDir, 768)!!
            // Cache is internal storage; copy (a rename can't cross into external storage).
            file.copyTo(File(context.getExternalFilesDir(null), "ink_$i.jpg"), overwrite = true)
            file.delete()
        }
    }

    companion object {
        const val TAG = "RealModelInk"
    }
}
