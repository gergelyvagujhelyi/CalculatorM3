package com.vagujhelyigergely.calculatorm3.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers in the app's language: only the words are translated ([TranslationText]). */
class TranslationTest {

    /** What the fake translator was given, call by call. */
    private val calls = mutableListOf<List<String>>()

    /** "Translates" by upper-casing the words, leaving the placeholders as a translator would. */
    private fun upper(sentences: List<String>): List<String> {
        calls += sentences
        return sentences.map { s -> s.replace(Regex("[a-z]+")) { it.value.uppercase() } }
    }

    @Test
    fun linesListMarkersAndMathsSurvive() {
        val steps = "1. Find the discount: \$40 \\times 0.15 = 6\$ euros.\n" +
            "2. Subtract it: 40 - 6 = 34.\n" +
            "3. So you pay 34 euros."
        val out = TranslationText.translate(listOf(steps), translateSentences = ::upper).single()
        assertEquals(
            "1. FIND THE DISCOUNT: \$40 \\times 0.15 = 6\$ EUROS.\n" +
                "2. SUBTRACT IT: 40 - 6 = 34.\n" +
                "3. SO YOU PAY 34 EUROS.",
            out
        )
        // The translator never sees the maths, and gets one sentence at a time without the marker.
        assertEquals(listOf("Find the discount: MATH1 euros.", "Subtract it: MATH1.", "So you pay 34 euros."), calls.single())
    }

    @Test
    fun aLineIsSplitIntoSentencesButNotInsideNumbersOrMaths() {
        val out = TranslationText.translate(
            listOf("Divide both sides by 2. This gives x = 4. The answer is 0.25 or 1/4."),
            translateSentences = ::upper
        ).single()
        assertEquals("DIVIDE BOTH SIDES BY 2. THIS GIVES x = 4. THE ANSWER IS 0.25 OR 1/4.", out)
        assertEquals(3, calls.single().size)
    }

    @Test
    fun aSentenceThatLosesItsMathsIsTriedWithOnlyTexMaskedThenKeptInEnglish() {
        val steps = "1. Multiply 8 by 6: 8 × 6 = 48.\n2. Subtract 9: 48 - 9 = 39."
        // First pass drops the placeholder of the first sentence; second pass changes a number.
        var pass = 0
        val out = TranslationText.translate(listOf(steps)) { sentences ->
            pass++
            sentences.map { s ->
                when {
                    pass == 1 && s.startsWith("Multiply") -> "MULTIPLY 8 BY 6."
                    pass == 2 -> s.replace("48", "47").uppercase()
                    else -> s.replace(Regex("[a-z]+")) { it.value.uppercase() }
                }
            }
        }.single()
        assertEquals(2, pass)
        assertEquals("1. Multiply 8 by 6: 8 × 6 = 48.\n2. SUBTRACT 9: 48 - 9 = 39.", out)
    }

    @Test
    fun numbersOutsideTheMathsMustSurvive() {
        val out = TranslationText.translate(listOf("A shirt costs 40 euros.")) { s -> s.map { "UN T-SHIRT COÛTE 41 EUROS." } }
        assertEquals("A shirt costs 40 euros.", out.single())
        // A decimal comma is the same number.
        val comma = TranslationText.translate(listOf("It is 2.5 kg.")) { s -> s.map { "ES SIND 2,5 KG." } }
        assertEquals("ES SIND 2,5 KG.", comma.single())
    }

    @Test
    fun englishFallbacksKeepTheirSpaceInChineseAndJapanese() {
        var pass = 0
        val out = TranslationText.translate(listOf("First step. Second step."), joiner = "") { s ->
            pass++
            s.map { if (it.startsWith("First")) "" else "第二步。" }
        }.single()
        assertEquals("First step. 第二步。", out)
        assertTrue(pass <= 2)
    }

    @Test
    fun severalTextsAreTranslatedTogether() {
        val out = TranslationText.translate(listOf("6 × 7", "1. 6 × 7 = 42", ""), translateSentences = ::upper)
        assertEquals(listOf("6 × 7", "1. 6 × 7 = 42", ""), out)
        assertEquals("one call for everything", 1, calls.size)
    }

    @Test
    fun everyAppLanguageHasAPinnedPack() {
        val names = listOf(
            "German", "Spanish", "French", "Hindi", "Hungarian", "Indonesian", "Italian", "Japanese",
            "Korean", "Dutch", "Polish", "Brazilian Portuguese", "Russian", "Turkish", "Ukrainian",
            "Vietnamese", "Simplified Chinese",
        )
        for (name in names) {
            val pack = TranslationPacks.forLanguageName(name)
            requireNotNull(pack) { "no pack for $name" }
            assertTrue(name, pack.files.any { it.kind == PackFile.Kind.MODEL })
            assertTrue(name, pack.files.any { it.kind == PackFile.Kind.VOCAB })
            assertTrue(name, pack.files.any { it.kind == PackFile.Kind.SHORTLIST })
            assertTrue(name, pack.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size in 1 until it.rawSize })
        }
        assertEquals(names.size, TranslationPacks.all.size)
    }
}
