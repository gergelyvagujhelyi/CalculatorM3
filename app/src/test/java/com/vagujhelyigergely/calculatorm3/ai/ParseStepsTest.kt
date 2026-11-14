package com.vagujhelyigergely.calculatorm3.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading a typed problem's step-by-step reply ([SolverPrompts.parseSteps]). */
class ParseStepsTest {

    private fun parse(raw: String, typed: String = "typed") = SolverPrompts.parseSteps(raw, typed)

    @Test
    fun `a reply in the requested shape is split into problem, steps and answer`() {
        val solved = parse(
            """
            Problem: What is 15% of 80?
            1. 15% = 0.15
            2. 0.15 × 80 = 12
            Answer: 12
            """.trimIndent()
        )!!
        assertEquals("What is 15% of 80?", solved.problem)
        assertEquals("1. 15% = 0.15\n2. 0.15 × 80 = 12", solved.steps)
        assertEquals("12", solved.answer)
        assertEquals("12", solved.usableAnswer)
    }

    @Test
    fun `an equation's solution is shown but not offered for Use`() {
        val solved = parse("Problem: 3x + 5 = 20\n1. 3x = 15\n2. x = 5\nAnswer: x = 5")!!
        assertEquals("x = 5", solved.answer)
        assertEquals("", solved.usableAnswer)
    }

    @Test
    fun `fractions and units are not numbers to Use`() {
        assertEquals("", parse("Problem: 1/2 + 3/4\n1. 2/4 + 3/4 = 5/4\nAnswer: 5/4")!!.usableAnswer)
        assertEquals("", parse("Problem: speed\n1. 120 / 2 = 60\nAnswer: 60 km/h")!!.usableAnswer)
        assertEquals("", parse("Problem: half\nAnswer: \\frac{1}{2}")!!.usableAnswer)
    }

    @Test
    fun `an answer that contradicts the working is not offered for Use`() {
        // The #116 slip: the working says 0.25, the final line 25.
        assertEquals("", parse("Problem: 1 ÷ 4\n1. 1 ÷ 4 = 0.25\nAnswer: 25")!!.usableAnswer)
    }

    @Test
    fun `markdown and LaTeX around the labels and the answer are tolerated`() {
        val solved = parse("**Problem:** 7 × 6\n1. 7 × 6 = 42\n**Answer:** \$\\boxed{42}\$")!!
        assertEquals("7 × 6", solved.problem)
        assertEquals("42", solved.usableAnswer)
        assertEquals("42", parse("Problem: 6 × 7\nFinal answer: 42")!!.usableAnswer)
        assertEquals("42", parse("## Problem: 6 × 7\n**Answer: 42**")!!.answer)
    }

    @Test
    fun `a times star in the maths is kept`() {
        val solved = parse("**Problem:** 5*4+3\n1. 5*4 = 20\n2. 20 + 3 = 23\n**Answer:** 23")!!
        assertEquals("5*4+3", solved.problem)
        assertEquals("23", solved.usableAnswer)
    }

    @Test
    fun `exponent notation is a number, not a letter`() {
        assertEquals("1.5E+3", parse("Problem: big\n1. 1500 = 1.5E+3\nAnswer: 1.5E+3")!!.usableAnswer)
    }

    @Test
    fun `without a Problem line the typed text is the problem`() {
        val solved = parse("1. 2 + 2 = 4\nAnswer: 4", typed = "  2 + 2  ")!!
        assertEquals("2 + 2", solved.problem)
        assertEquals("1. 2 + 2 = 4", solved.steps)
    }

    @Test
    fun `without an Answer line there is nothing to show as the answer`() {
        assertNull(parse("Problem: 2 + 2\n1. 2 + 2 = 4"))
        assertNull(parse("Problem: 2 + 2\nAnswer:"))
    }

    @Test
    fun `the last Answer line wins`() {
        val solved = parse("Problem: p\nAnswer: 3\n1. actually 2 + 2 = 4\nAnswer: 4")!!
        assertEquals("4", solved.answer)
        assertEquals("Answer: 3\n1. actually 2 + 2 = 4", solved.steps)
    }

    @Test
    fun `a restated picture prompt is dropped from the problem`() {
        assertEquals("5*4+3", parse("Problem: Solve the handwritten math problem 5*4+3\nAnswer: 23")!!.problem)
        assertEquals("\$7+5\$", parse("Problem: Solve the math problem \$7+5\$\nAnswer: 12")!!.problem)
        assertEquals("Solve for x: 3x = 15", parse("Problem: Solve for x: 3x = 15\nAnswer: x = 5")!!.problem)
    }

    @Test
    fun `the same value written two ways offers its plain number`() {
        // Seen on a photo of 1 ÷ 4 with the step prompt.
        assertEquals("0.25", parse("Problem: 1 ÷ 4\n1. 1 ÷ 4 = 0.25\nAnswer: \$\\frac{1}{4}\$ or 0.25")!!.usableAnswer)
        assertEquals("1.25", parse("Problem: 5/4\n1. 5 ÷ 4 = 1.25\nAnswer: 5/4 or 1.25")!!.usableAnswer)
    }

    @Test
    fun `alternatives that aren't one plain number offer nothing`() {
        assertEquals("", parse("Problem: p\nAnswer: 4 or 5")!!.usableAnswer)
        assertEquals("", parse("Problem: x^2 = 4\nAnswer: x = 2 or x = -2")!!.usableAnswer)
        assertEquals("", parse("Problem: p\nAnswer: 5/4 or 1 1/4")!!.usableAnswer)
        // The plain one must still agree with the working.
        assertEquals("", parse("Problem: 1 ÷ 4\n1. 1 ÷ 4 = 0.25\nAnswer: \\frac{1}{4} or 25")!!.usableAnswer)
    }

    @Test
    fun `a mixed number is never read as its whole part`() {
        // "1 1/4" isn't 1, even when there's no working to check it against.
        assertEquals("", parse("Problem: 5/4 as a mixed number\nAnswer: 1 1/4")!!.usableAnswer)
        assertEquals("12", parse("Problem: 7 + 5\nAnswer: 12.")!!.usableAnswer)
    }
}
