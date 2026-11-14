package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.LiteRTSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.SolverPrompts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Typed problems on the real on-device model (Gemma 4 E2B): the reply must be readable by
 * [SolverPrompts.parseSteps], and what reaches "Use" must be the right number or nothing.
 * Raw output and timings are logged under [TAG].
 *
 * Opt-in like [RealModelExtractionTest]: `realModel=true` and `allowDataWrites=true`, with the
 * `-PdeviceTest` build, and the model already in that app's storage (this test doesn't download).
 */
class RealModelTextTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    /** [expected]: the number "Use" may offer, or null when it must offer none (x = 5, fractions). */
    private data class Case(val typed: String, val expected: String?)

    private val cases = listOf(
        Case("3x + 5 = 20", null),
        Case("What is 15% of 80?", "12"),
        Case("1/2 + 3/4", null),
        Case("sqrt(144) + 2^5", "44"),
        Case("Pens cost 2 euros for 3. How much do 12 pens cost in euros?", "8"),
        Case("A rectangle is 7 cm by 4 cm. What is its area in square cm?", "28"),
    )

    @Test
    fun typedProblemsComeBackInTheStepFormatAndNeverOfferAWrongNumber() = runBlocking {
        assumeTrue("Heavy on-device model run; pass realModel=true and allowDataWrites=true",
            args.getString("realModel") == "true" && args.getString("allowDataWrites") == "true")
        val model = AiModel.GEMMA4_E2B
        val manager = ModelManager(context)
        assumeTrue("Model not in this app's storage", manager.areModelsAvailable(model))
        val path = manager.modelPath(model)

        val solver = LiteRTSolver()
        var start = System.currentTimeMillis()
        solver.loadModel(path)
        Log.i(TAG, "load ${System.currentTimeMillis() - start} ms on ${solver.activeBackend}")

        val wrong = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        try {
            for (case in cases) {
                start = System.currentTimeMillis()
                var firstTokenMs = -1L
                val result = solver.solveFromTextStreaming(case.typed) {
                    if (firstTokenMs < 0 && it.isNotEmpty()) firstTokenMs = System.currentTimeMillis() - start
                }
                val ms = System.currentTimeMillis() - start
                val raw = result.getOrNull()?.raw
                val solved = raw?.let { SolverPrompts.parseSteps(it, case.typed) }
                val usable = solved?.usableAnswer.orEmpty()
                val outcome = when {
                    solved == null -> { unparsed += case.typed; "UNPARSED (${result.exceptionOrNull()?.message})" }
                    usable.isEmpty() -> "NO USE (answer '${solved.answer}')"
                    case.expected != null &&
                        BigDecimal(usable).compareTo(BigDecimal(case.expected)) == 0 -> "CORRECT"
                    else -> { wrong += "${case.typed} -> $usable (expected ${case.expected})"; "WRONG" }
                }
                Log.i(TAG, "[${case.typed}] $outcome in $ms ms (first token $firstTokenMs ms)")
                Log.i(TAG, "[${case.typed}] raw: ${raw?.replace("\n", " ⏎ ")?.take(800)}")
            }
        } finally {
            solver.release()
        }
        Log.i(TAG, "SUMMARY wrong=${wrong.size} unparsed=${unparsed.size} of ${cases.size}")
        assertTrue("A wrong number reached Use: $wrong", wrong.isEmpty())
        assertTrue("Replies not in the step format: $unparsed", unparsed.isEmpty())
    }

    companion object {
        const val TAG = "RealModelText"
    }
}
