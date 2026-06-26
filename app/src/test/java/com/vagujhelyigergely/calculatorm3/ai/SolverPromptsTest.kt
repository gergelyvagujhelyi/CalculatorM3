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
    fun `number right after equals on a messy last line`() {
        // Was '5' before (last number on the line); the result after '=' is the answer.
        assertEquals("17", SolverPrompts.extractAnswer("So x = 17 (that is 12 + 5)"))
    }

    @Test
    fun `prefers the stated result over a trailing aside`() {
        // Was '5' before (last number); the leading number is the stated answer.
        assertEquals("17", SolverPrompts.extractAnswer("The answer is 17 (i.e. 12+5)"))
    }
}
