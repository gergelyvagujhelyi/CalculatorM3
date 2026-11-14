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

/**
 * Follow-up questions on the real model: a typed problem is solved, then two questions are asked
 * about it, each as one fresh message ([SolverPrompts.followUpMessage]). Logs the replies and
 * timings under [TAG]; a reply must not be empty.
 *
 * Opt-in like [RealModelTextTest]: `realModel=true` and `allowDataWrites=true`, with the
 * `-PdeviceTest` build, and the model already in that app's storage.
 */
class RealModelFollowUpTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    @Test
    fun twoQuestionsAboutAnAnswerGetShortReplies() = runBlocking {
        assumeTrue("Heavy on-device model run; pass realModel=true and allowDataWrites=true",
            args.getString("realModel") == "true" && args.getString("allowDataWrites") == "true")
        val model = AiModel.GEMMA4_E2B
        val manager = ModelManager(context)
        assumeTrue("Model not in this app's storage", manager.areModelsAvailable(model))

        val solver = LiteRTSolver()
        try {
            solver.loadModel(manager.modelPath(model))
            val problem = "What is 15% of 80?"
            val raw = solver.solveFromTextStreaming(problem) { }.getOrThrow().raw
            val steps = SolverPrompts.parseSteps(raw, problem)!!
            Log.i(TAG, "answer: ${steps.answer}")
            val earlier = mutableListOf<Pair<String, String>>()
            for (question in listOf("Why do we multiply by 0.15?", "Is there another way to work it out?")) {
                val message = SolverPrompts.followUpMessage(steps.problem, steps.answer, steps.steps, earlier, question)
                val start = System.currentTimeMillis()
                var firstTokenMs = -1L
                val reply = solver.followUpStreaming(message) {
                    if (firstTokenMs < 0 && it.isNotEmpty()) firstTokenMs = System.currentTimeMillis() - start
                }.getOrThrow().raw.trim()
                val ms = System.currentTimeMillis() - start
                Log.i(TAG, "[$question] ${reply.length} chars in $ms ms (first token $firstTokenMs ms)")
                Log.i(TAG, "[$question] reply: ${reply.replace("\n", " ⏎ ").take(700)}")
                assertTrue("empty reply to: $question", reply.isNotEmpty())
                earlier += question to reply
            }
        } finally {
            solver.release()
        }
    }

    companion object {
        const val TAG = "RealModelFollowUp"
    }
}
