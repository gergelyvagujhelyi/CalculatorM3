package com.vagujhelyigergely.calculatorm3

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    // ── formatExpression ────────────────────────────────────────────

    @Test
    fun `separators added to 4+ digit integers`() {
        assertEquals("1,234", formatExpression("1234"))
        assertEquals("12,345", formatExpression("12345"))
        assertEquals("123,456", formatExpression("123456"))
        assertEquals("1,234,567", formatExpression("1234567"))
        assertEquals("1,234,567,890", formatExpression("1234567890"))
    }

    @Test
    fun `no separator for 3 or fewer digits`() {
        assertEquals("0", formatExpression("0"))
        assertEquals("1", formatExpression("1"))
        assertEquals("12", formatExpression("12"))
        assertEquals("123", formatExpression("123"))
    }

    @Test
    fun `separators only in integer part of decimals`() {
        assertEquals("1,234.56", formatExpression("1234.56"))
        assertEquals("1,000.5", formatExpression("1000.5"))
        assertEquals("1.23456", formatExpression("1.23456"))
        assertEquals("0.123456", formatExpression("0.123456"))
    }

    @Test
    fun `operator spacing preserved with separators`() {
        assertEquals("12,345 + 67,890", formatExpression("12345+67890"))
        assertEquals("1,000 × 2,000", formatExpression("1000×2000"))
        assertEquals("1,000 ÷ 500", formatExpression("1000÷500"))
        assertEquals("1,000 − 500", formatExpression("1000−500"))
    }

    @Test
    fun `no spacing for operator at position 0`() {
        assertEquals("+5,000", formatExpression("+5000"))
        assertEquals("×1,234", formatExpression("×1234"))
    }

    @Test
    fun `ascii minus from negate not spaced`() {
        assertEquals("-12,345", formatExpression("-12345"))
        assertEquals("5 + -1,000", formatExpression("5+-1000"))
    }

    @Test
    fun `E notation passed through without separators`() {
        assertEquals("1E+30", formatExpression("1E+30"))
        assertEquals("1.23E+30", formatExpression("1.23E+30"))
        assertEquals("369.7E+195", formatExpression("369.7E+195"))
        assertEquals("5E-3", formatExpression("5E-3"))
    }

    @Test
    fun `special characters pass through`() {
        assertEquals("√(12,345)", formatExpression("√(12345)"))
        assertEquals("π", formatExpression("π"))
        assertEquals("12,345!", formatExpression("12345!"))
        assertEquals("1,000%", formatExpression("1000%"))
        assertEquals("2,000^3", formatExpression("2000^3"))
    }

    @Test
    fun `empty and dot-start strings`() {
        assertEquals("", formatExpression(""))
        assertEquals(".", formatExpression("."))
        assertEquals(".5", formatExpression(".5"))
        assertEquals(".12345", formatExpression(".12345"))
    }

    @Test
    fun `history strings formatted correctly`() {
        assertEquals("1,000 + 2,000 =", formatExpression("1000+2000 ="))
        assertEquals("12,345 =", formatExpression("12345 ="))
    }

    // ── formatResultNumber ──────────────────────────────────────────

    @Test
    fun `result separators for large numbers`() {
        assertEquals("1,234", formatResultNumber("1234"))
        assertEquals("12,345", formatResultNumber("12345"))
        assertEquals("1,234,567", formatResultNumber("1234567"))
    }

    @Test
    fun `result no separator for small numbers`() {
        assertEquals("0", formatResultNumber("0"))
        assertEquals("123", formatResultNumber("123"))
        assertEquals("999", formatResultNumber("999"))
    }

    @Test
    fun `result handles decimals`() {
        assertEquals("1,000.5", formatResultNumber("1000.5"))
        assertEquals("1,234.5678", formatResultNumber("1234.5678"))
    }

    @Test
    fun `result handles negatives`() {
        assertEquals("-1,234", formatResultNumber("-1234"))
        assertEquals("-1,000.5", formatResultNumber("-1000.5"))
    }

    @Test
    fun `result skips E notation`() {
        assertEquals("1.23E+30", formatResultNumber("1.23E+30"))
        assertEquals("1e10", formatResultNumber("1e10"))
    }

    @Test
    fun `result skips errors and empty`() {
        assertEquals("Error: divisionByZero", formatResultNumber("Error: divisionByZero"))
        assertEquals("", formatResultNumber(""))
    }

    // ── Cursor mapping round-trips ──────────────────────────────────

    @Test
    fun `cursor round-trip simple number`() {
        assertCursorRoundTrip("12345")
    }

    @Test
    fun `cursor round-trip expression with operators`() {
        assertCursorRoundTrip("12345+67890")
    }

    @Test
    fun `cursor round-trip decimal number`() {
        assertCursorRoundTrip("1234.5678")
    }

    @Test
    fun `cursor round-trip negative number`() {
        assertCursorRoundTrip("-12345")
    }

    @Test
    fun `cursor round-trip small number`() {
        assertCursorRoundTrip("123")
    }

    @Test
    fun `cursor round-trip with special chars`() {
        assertCursorRoundTrip("√(12345)")
    }

    @Test
    fun `cursor round-trip complex expression`() {
        assertCursorRoundTrip("12345+67890×100")
    }

    @Test
    fun `cursor round-trip empty string`() {
        assertCursorRoundTrip("")
    }

    // ── Cursor mapping specific positions ────────────────────────────

    @Test
    fun `cursor at end maps to formatted length`() {
        // "12,345" has length 6
        assertEquals(6, mapCursorToFormatted("12345", 5))
    }

    @Test
    fun `cursor before separator maps correctly`() {
        // raw "12345" → formatted "12,345"
        // raw pos 2 (between '2' and '3') → formatted pos 2 (before ',')
        assertEquals(2, mapCursorToFormatted("12345", 2))
    }

    @Test
    fun `tap on separator maps to before next digit`() {
        // formatted "12,345" pos 3 (after ',') → raw pos 2 (before '3')
        assertEquals(2, mapCursorFromFormatted("12345", 3))
    }

    @Test
    fun `cursor at position 0`() {
        assertEquals(0, mapCursorToFormatted("12345", 0))
        assertEquals(0, mapCursorFromFormatted("12345", 0))
    }

    @Test
    fun `operator at position 0 counts as 1 char`() {
        assertEquals(1, mapCursorToFormatted("+5000", 1))
    }

    @Test
    fun `operator at non-zero position counts as 3 chars`() {
        // "5" = 1 char, "+" = 3 chars (" + "), total = 4
        assertEquals(4, mapCursorToFormatted("5+3", 2))
    }

    // ── Helper ──────────────────────────────────────────────────────

    private fun assertCursorRoundTrip(raw: String) {
        for (pos in 0..raw.length) {
            val formatted = mapCursorToFormatted(raw, pos)
            val back = mapCursorFromFormatted(raw, formatted)
            assertEquals("Round-trip failed for \"$raw\" at pos=$pos", pos, back)
        }
    }
}
