package com.vagujhelyigergely.calculatorm3

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AnswerTranslator
import com.vagujhelyigergely.calculatorm3.ai.TranslationPacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real translator: downloads Mozilla packs and translates sample answer steps, logging the
 * results under [TAG] for reading. Opt-in (it downloads 15–38 MB per language):
 * `translation=true allowDataWrites=true`, optionally `langs=hu,de` (pack codes; all by default).
 */
class AnswerTranslatorDeviceTest {

    private val samples = listOf(
        "A shirt costs 40 euros and is 15 percent off. How much do I pay?",
        "1. Find the discount: \$40 \\times 0.15 = 6\$ euros.\n" +
            "2. Subtract the discount from the original price: 40 - 6 = 34.\n" +
            "3. So you pay 34 euros.",
        "1. Multiply 8 by 6: 8 × 6 = 48.\n2. Subtract 9 from the result: 48 - 9 = 39.",
        "Solve for x: 2x + 3 = 11",
        "1. Subtract 3 from both sides: \$2x = 8\$.\n2. Divide both sides by 2. This gives x = 4.",
    )

    private val rawSamples = listOf(
        "How much do I pay?",
        "Multiply 8 by 6: 8 × 6 = 48.",
        "Find the discount: X1 euros.",
        "Divide both sides by 2.",
    )

    @Test
    fun translatesSampleSteps(): Unit = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Downloads translation packs; pass translation=true and allowDataWrites=true",
            args.getString("translation") == "true" && args.getString("allowDataWrites") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val translator = AnswerTranslator(context)
        val codes = args.getString("langs")?.split(",") ?: TranslationPacks.all.map { it.code }
        try {
            for (code in codes) {
                val pack = TranslationPacks.all.first { it.code == code }
                var started = System.currentTimeMillis()
                translator.download(pack)
                Log.i(TAG, "$code downloaded ${pack.downloadSize / 1000} kB in ${System.currentTimeMillis() - started} ms")
                assertTrue(translator.isInstalled(pack))
                val raw = translator.translateSentences(rawSamples, pack)
                raw.forEachIndexed { i, text -> Log.i(TAG, "$code raw [$i] ${rawSamples[i]} => $text") }
                // A model loaded after another must not see the first one's weights.
                assertTrue("$code gave no translation", raw.none { it.isBlank() })
                started = System.currentTimeMillis()
                val out = translator.translate(samples, pack)
                val ms = System.currentTimeMillis() - started
                assertEquals(samples.size, out.size)
                out.forEachIndexed { i, text -> Log.i(TAG, "$code ${ms}ms [$i] ${text.replace("\n", " ⏎ ")}") }
            }
        } finally {
            translator.release()
        }
    }

    companion object {
        const val TAG = "AnswerTranslatorTest"
    }
}
