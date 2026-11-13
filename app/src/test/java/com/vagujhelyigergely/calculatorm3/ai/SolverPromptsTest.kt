package com.vagujhelyigergely.calculatorm3.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SolverPromptsTest {

    // ── Last line is purely a number ─────────────────────────────────

    @Test
    fun `extract simple integer`() {
        assertEquals("42", SolverPrompts.extractAnswer("The answer is\n42"))
    }

    @Test
    fun `extract negative number`() {
        assertEquals("-7", SolverPrompts.extractAnswer("Result:\n-7"))
    }

    @Test
    fun `extract decimal`() {
        assertEquals("3.14", SolverPrompts.extractAnswer("Pi is approximately\n3.14"))
    }

    @Test
    fun `extract leading decimal`() {
        assertEquals(".5", SolverPrompts.extractAnswer("Half is\n.5"))
    }

    @Test
    fun `extract scientific notation`() {
        assertEquals("1.5e10", SolverPrompts.extractAnswer("The result is\n1.5e10"))
    }

    @Test
    fun `extract negative exponent`() {
        assertEquals("3.0E-5", SolverPrompts.extractAnswer("Very small:\n3.0E-5"))
    }

    // ── Last number on last line ─────────────────────────────────────

    @Test
    fun `extract answer from text line`() {
        assertEquals("17", SolverPrompts.extractAnswer("2 + 3 * 5 = 17"))
    }

    @Test
    fun `extract last number when multiple on last line`() {
        assertEquals("100", SolverPrompts.extractAnswer("Step 3: 50 + 50 = 100"))
    }

    // ── Fallback to last number anywhere ─────────────────────────────

    @Test
    fun `extract from multiline response`() {
        val response = """
            I see the expression: 2 + 3
            Let me calculate:
            2 + 3 = 5
            The answer is:
            5
        """.trimIndent()
        assertEquals("5", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `extract ignores step numbers when answer on last line`() {
        val response = """
            Step 1: I see 10 + 5
            Step 2: 10 + 5 = 15
            15
        """.trimIndent()
        assertEquals("15", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `extract from response with trailing whitespace`() {
        assertEquals("42", SolverPrompts.extractAnswer("The answer is 42\n  \n"))
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `empty input returns empty`() {
        assertEquals("", SolverPrompts.extractAnswer(""))
    }

    @Test
    fun `no numbers returns empty`() {
        assertEquals("", SolverPrompts.extractAnswer("I cannot read this image"))
    }

    @Test
    fun `zero is valid answer`() {
        assertEquals("0", SolverPrompts.extractAnswer("0"))
    }

    @Test
    fun `large number`() {
        assertEquals("123456789", SolverPrompts.extractAnswer("123456789"))
    }

    @Test
    fun `negative decimal`() {
        assertEquals("-0.5", SolverPrompts.extractAnswer("The result is -0.5"))
    }

    @Test
    fun `scientific notation with plus`() {
        assertEquals("2.5e+8", SolverPrompts.extractAnswer("2.5e+8"))
    }

    // ── Hardened fallbacks (avoid confidently-wrong trailing tokens) ──

    @Test
    fun `strips thousands separators on bare last line`() {
        assertEquals("12345", SolverPrompts.extractAnswer("Result:\n12,345"))
    }

    @Test
    fun `strips thousands separators in prose`() {
        assertEquals("1234", SolverPrompts.extractAnswer("The total comes to 1,234"))
    }

    @Test
    fun `a comma after a lone zero is a decimal comma, not a thousands separator`() {
        // 0,125 used to lose its comma and become 125: no thousands-separated number starts with 0.
        assertEquals("0.125", SolverPrompts.extractAnswer("The answer is 0,125"))
        assertEquals("0.125", SolverPrompts.extractAnswer("1 ÷ 8 = 0,125"))
        assertEquals("-0.5", SolverPrompts.extractAnswer("x = −0,5"))
        assertEquals("10500", SolverPrompts.extractAnswer("Total: 10,500"))
        assertEquals("1000", SolverPrompts.extractAnswer("1,000"))
    }

    @Test
    fun `number right after equals on a messy last line`() {
        // Was '5' before (last number on the line); the result after '=' is the answer.
        assertEquals("17", SolverPrompts.extractAnswer("So x = 17 (that is 12 + 5)"))
    }

    @Test
    fun `prefers the stated result over a trailing aside`() {
        // Was '5' before (last number); the leading number is the stated answer.
        assertEquals("17", SolverPrompts.extractAnswer("The answer is 17 (i.e. 12+5)"))
    }

    // ── Complete-answer validation: never return a fragment ─────────

    @Test
    fun `fraction is rejected rather than truncated to its numerator`() {
        assertEquals("", SolverPrompts.extractAnswer("1/2"))
        assertEquals("", SolverPrompts.extractAnswer("The answer is 3 / 4"))
        assertEquals("", SolverPrompts.extractAnswer("x = 1/2"))
    }

    @Test
    fun `latex fraction is rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("\\frac{1}{2}"))
        assertEquals("", SolverPrompts.extractAnswer("\$\\dfrac{3}{4}\$"))
    }

    @Test
    fun `unicode minus keeps the sign`() {
        assertEquals("-7", SolverPrompts.extractAnswer("−7"))
        assertEquals("-7", SolverPrompts.extractAnswer("Result:\n–7"))
        assertEquals("-2.5", SolverPrompts.extractAnswer("x = −2.5"))
    }

    @Test
    fun `times ten to the power becomes E notation`() {
        assertEquals("2E3", SolverPrompts.extractAnswer("2 × 10^3"))
        assertEquals("1.5E-4", SolverPrompts.extractAnswer("= 1.5 \\times 10^{-4}"))
        assertEquals("-3E8", SolverPrompts.extractAnswer("−3·10^8"))
    }

    @Test
    fun `bare power is rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("10^3"))
        assertEquals("", SolverPrompts.extractAnswer("= 2^10"))
    }

    @Test
    fun `explanation without an answer is rejected`() {
        val response = "I see 1 / 0\nDivision by zero is undefined"
        assertEquals("", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `unfinished arithmetic is rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("12 + 5"))
        assertEquals("", SolverPrompts.extractAnswer("= 6 × 7"))
        assertEquals("", SolverPrompts.extractAnswer("10-3"))
    }

    @Test
    fun `several candidate numbers are ambiguous`() {
        assertEquals("", SolverPrompts.extractAnswer("It is either 4 or 5"))
        assertEquals("", SolverPrompts.extractAnswer("1,5")) // decimal comma, not a thousands separator
    }

    @Test
    fun `percent answer is rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("50%"))
    }

    @Test
    fun `answer wrapped in markdown or latex is accepted`() {
        assertEquals("42", SolverPrompts.extractAnswer("**42**"))
        assertEquals("42", SolverPrompts.extractAnswer("\\boxed{42}"))
        assertEquals("42", SolverPrompts.extractAnswer("\$42\$"))
        assertEquals("42", SolverPrompts.extractAnswer("\\(42\\)"))
        assertEquals("-8", SolverPrompts.extractAnswer("**Answer:** (−8)"))
    }

    @Test
    fun `trailing sentence period is dropped`() {
        assertEquals("5", SolverPrompts.extractAnswer("The answer is 5."))
        assertEquals("2.5", SolverPrompts.extractAnswer("The answer is 2.5."))
    }

    @Test
    fun `answer line followed by a sign-off line`() {
        assertEquals("15", SolverPrompts.extractAnswer("10 + 5 = 15\nHope this helps!"))
    }

    @Test
    fun `unit after the answer is fine`() {
        assertEquals("5", SolverPrompts.extractAnswer("The speed is 5 km/h"))
    }

    // ── Review follow-ups ────────────────────────────────────────────

    @Test
    fun `root, pi and factorial fragments are rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("x = √2"))
        assertEquals("", SolverPrompts.extractAnswer("x = √ 2"))
        assertEquals("", SolverPrompts.extractAnswer("= 2π"))
        assertEquals("", SolverPrompts.extractAnswer("= π2"))
        assertEquals("", SolverPrompts.extractAnswer("= 5!"))
    }

    @Test
    fun `function call arguments are rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("= sqrt(2)"))
        assertEquals("", SolverPrompts.extractAnswer("The answer is ln(5)"))
        assertEquals("", SolverPrompts.extractAnswer("\\sqrt{2}"))
    }

    @Test
    fun `prose dashes are punctuation, not minus signs`() {
        assertEquals("42", SolverPrompts.extractAnswer("The answer is 42 — computed above"))
        assertEquals("42", SolverPrompts.extractAnswer("The answer is 42 – see the working"))
    }

    @Test
    fun `dash directly before a number is still a minus sign`() {
        assertEquals("-7", SolverPrompts.extractAnswer("Result:\n–7"))
        assertEquals("-2.5", SolverPrompts.extractAnswer("x = —2.5"))
        assertEquals("-7", SolverPrompts.extractAnswer("−7")) // U+2212 is always a minus
    }

    // ── "!" in prose vs factorial ────────────────────────────────────

    @Test
    fun `exclamation mark ending a sentence is punctuation`() {
        assertEquals("42", SolverPrompts.extractAnswer("The answer is 42!"))
        assertEquals("42", SolverPrompts.extractAnswer("The answer is 42! Great job."))
        assertEquals("-7", SolverPrompts.extractAnswer("So the result is −7!"))
        assertEquals("3.5", SolverPrompts.extractAnswer("Done, it's 3.5!"))
    }

    @Test
    fun `factorial in math context is still rejected`() {
        assertEquals("", SolverPrompts.extractAnswer("= 5!"))
        assertEquals("", SolverPrompts.extractAnswer("x = 5!"))
        assertEquals("", SolverPrompts.extractAnswer("5!"))
        assertEquals("", SolverPrompts.extractAnswer("The answer is 5!3"))
        assertEquals("", SolverPrompts.extractAnswer("The answer is 3!!"))
        assertEquals("", SolverPrompts.extractAnswer("The answer is 4! + 1"))
    }

    // ── The final line must agree with the working ──────────────────

    @Test
    fun `final line contradicting the working is rejected (measured on a Note 9)`() {
        // Gemma 4 E2B with a 140-token image budget: right working, final line lost its "0.".
        val response = "Expression: \$1 \\div 4\$\n" +
            "Calculation: \$1 \\div 4 = \\frac{1}{4}\$\n" +
            "As a decimal: \$1 \\div 4 = 0.25\$\n\n25"
        assertEquals("", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `final line matching the working is accepted`() {
        val response = "Expression: \$1 \\div 4\$\n" +
            "Calculation: \$1 \\div 4 = \\frac{1}{4}\$ or \$0.25\$\n\n0.25"
        assertEquals("0.25", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `same value written differently is accepted`() {
        assertEquals(".25", SolverPrompts.extractAnswer("1 / 4 = 0.25\n.25"))
        assertEquals("14", SolverPrompts.extractAnswer("3.5 × 4 = 14.0\n14"))
    }

    @Test
    fun `rounded or cut-off decimals are accepted`() {
        assertEquals("0.3333", SolverPrompts.extractAnswer("1 ÷ 3 = 0.333333...\n0.3333"))
        assertEquals("0.6667", SolverPrompts.extractAnswer("2 ÷ 3 = 0.666666\n0.6667"))
        assertEquals("0.6666", SolverPrompts.extractAnswer("2 ÷ 3 = 0.666666\n0.6666"))
    }

    @Test
    fun `rounding to fewer than two decimals is not agreement`() {
        assertEquals("", SolverPrompts.extractAnswer("1 ÷ 4 = 0.25\n0"))
        assertEquals("", SolverPrompts.extractAnswer("10 ÷ 4 = 2.5\n3"))
    }

    @Test
    fun `wrong final line after multi-step working is rejected`() {
        val response = """
            Step 1: 7 × 3 = 21
            Step 2: 12 + 21 = 33
            23
        """.trimIndent()
        assertEquals("", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `answer line with its own equals sign states the result itself`() {
        val response = """
            Step 1: 7 × 3 = 21
            12 + 21 = 33
        """.trimIndent()
        assertEquals("33", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `result stated in prose after the last equals counts`() {
        val response = """
            7 × 3 = 21
            Then add 12 to get 33.
            33
        """.trimIndent()
        assertEquals("33", SolverPrompts.extractAnswer(response))
    }

    @Test
    fun `working without a numeric result can't be checked and is accepted`() {
        assertEquals("0.25", SolverPrompts.extractAnswer("1 ÷ 4 = \\frac{1}{4}\n0.25"))
        assertEquals("42", SolverPrompts.extractAnswer("I read 6 × 7.\n42"))
    }
}
