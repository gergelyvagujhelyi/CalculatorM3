package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.LiteRTSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionException
import com.vagujhelyigergely.calculatorm3.ai.SolverPrompts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Runs the real on-device model (Gemma 4 E2B via LiteRT-LM) on generated images of simple math
 * and checks what reaches "Use answer": either the correct number or nothing — never a wrong
 * number (the guarantee of #83/#92). Raw model output is logged under the tag [TAG].
 *
 * Heavy and network-bound (downloads ~2.6 GB on first run), so it runs only with the
 * instrumentation arguments `realModel=true` and `allowDataWrites=true`, on an arm64 device
 * with the isolated `-PdeviceTest` build. CI doesn't pass `realModel`, so it's skipped there.
 */
class RealModelExtractionTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private data class Case(val shown: String, val expected: String)

    private val cases = listOf(
        Case("12 + 5", "17"),
        Case("7 × 6", "42"),
        Case("100 − 37", "63"),
        Case("8 ÷ 2", "4"),
        Case("√16", "4"),
        Case("5!", "120"),
        Case("3 − 10", "-7"),
        Case("1 ÷ 4", "0.25"),
    )

    /** A clean printed rendering of [text], sized like a prepared scan (longest edge 768 px). */
    private fun image(text: String): String {
        val bmp = Bitmap.createBitmap(768, 384, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 140f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            drawText(text, width / 2f, height / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        val file = File(context.cacheDir, "realmodel_${System.nanoTime()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return file.path
    }

    @Test
    fun realModelNeverPutsAWrongNumberIntoUseAnswer() = runBlocking {
        assumeTrue("Heavy on-device model run; pass realModel=true and allowDataWrites=true",
            args.getString("realModel") == "true" && args.getString("allowDataWrites") == "true")

        val model = AiModel.GEMMA4_E2B
        val manager = ModelManager(context)
        for (file in model.files) {
            if (!manager.isFileDownloaded(model, file)) {
                Log.i(TAG, "downloading ${file.filename} (${file.sizeDisplay})…")
                var lastPct = -1
                manager.downloadFile(model, file.url, file.filename) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct && pct % 10 == 0) { lastPct = pct; Log.i(TAG, "download $pct%") }
                }
            }
        }

        val solver = LiteRTSolver()
        val loadStart = System.currentTimeMillis()
        solver.loadModel(manager.modelPath(model))
        Log.i(TAG, "model loaded in ${System.currentTimeMillis() - loadStart} ms on ${solver.activeBackend}")

        val wrong = mutableListOf<String>()
        var correct = 0
        var declined = 0
        try {
            for (case in cases) {
                val path = image(case.shown)
                val start = System.currentTimeMillis()
                // steps=true: the step-by-step prompt (as typed and drawn problems use), to compare.
                val steps = args.getString("steps") == "true"
                val result = if (steps) solver.solveFromImageStepsStreaming(path) { }
                    else solver.solveFromImageStreaming(path) { }
                val ms = System.currentTimeMillis() - start
                File(path).delete()
                val raw = result.getOrNull()?.raw ?: (result.exceptionOrNull() as? RecognitionException)?.rawResponse
                val answer = if (steps) raw?.let { SolverPrompts.parseSteps(it, "")?.usableAnswer }.orEmpty()
                    else result.getOrNull()?.answer.orEmpty()
                val outcome = when {
                    answer.isEmpty() -> { declined++; "DECLINED (${result.exceptionOrNull()?.message})" }
                    BigDecimal(answer).compareTo(BigDecimal(case.expected)) == 0 -> { correct++; "CORRECT" }
                    else -> { wrong += "${case.shown} -> $answer (expected ${case.expected})"; "WRONG" }
                }
                Log.i(TAG, "[${case.shown}] expected=${case.expected} answer='$answer' $outcome in $ms ms")
                Log.i(TAG, "[${case.shown}] raw: ${raw?.replace("\n", " ⏎ ")?.take(600)}")
            }
        } finally {
            solver.release()
        }
        Log.i(TAG, "SUMMARY correct=$correct declined=$declined wrong=${wrong.size} of ${cases.size}")
        assertTrue("A wrong number reached Use answer: $wrong", wrong.isEmpty())
    }

    companion object {
        const val TAG = "RealModelCheck"
    }
}
