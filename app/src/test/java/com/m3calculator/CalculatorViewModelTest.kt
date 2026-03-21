package com.m3calculator

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CalculatorViewModelTest {

    private lateinit var vm: CalculatorViewModel

    @Before
    fun setup() {
        vm = CalculatorViewModel()
    }

    private fun tap(vararg labels: String) {
        labels.forEach { vm.onButtonPress(it) }
    }

    private fun tapEquals() = vm.onButtonPress("=")

    private fun assertExpression(expected: String) {
        assertEquals(expected, vm.expression)
    }

    private fun assertResult(expected: String) {
        assertEquals(expected, vm.result)
    }

    // ── Basic arithmetic ────────────────────────────────────────────

    @Test
    fun addition() {
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun subtraction() {
        tap("9", "−", "4")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun multiplication() {
        tap("6", "×", "7")
        tapEquals()
        assertExpression("42")
    }

    @Test
    fun division() {
        tap("8", "÷", "2")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun decimalArithmetic() {
        tap("0", ".", "1", "+", "0", ".", "2")
        tapEquals()
        assertExpression("0.3")
    }

    // ── Operator precedence ─────────────────────────────────────────

    @Test
    fun multiplicationBeforeAddition() {
        tap("2", "+", "3", "×", "4")
        tapEquals()
        assertExpression("14")
    }

    @Test
    fun divisionBeforeSubtraction() {
        tap("1", "0", "−", "6", "÷", "3")
        tapEquals()
        assertExpression("8")
    }

    // ── Trailing operator stripped on equals ─────────────────────────

    @Test
    fun trailingOperatorStripped() {
        tap("5", "+", "3", "×")
        tapEquals()
        assertExpression("8")
    }

    @Test
    fun multipleTrailingOperatorsStripped() {
        tap("7", "+", "−")
        tapEquals()
        assertExpression("7")
    }

    // ── Unary operators ─────────────────────────────────────────────

    @Test
    fun squareRoot() {
        tap("9", "√")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun factorial() {
        tap("5", "!")
        tapEquals()
        assertExpression("120")
    }

    @Test
    fun percentage() {
        tap("2", "0", "0", "×", "5", "0", "%")
        tapEquals()
        assertExpression("100")
    }

    // ── Special values ──────────────────────────────────────────────

    @Test
    fun piValue() {
        tap("π")
        tapEquals()
        assertExpression("3.141592654")
    }

    @Test
    fun power() {
        tap("2", "^", "1", "0")
        tapEquals()
        assertExpression("1024")
    }

    @Test
    fun negativePower() {
        tap("2", "^", "3")
        tapEquals()
        assertExpression("8")
    }

    // ── Clear and backspace ─────────────────────────────────────────

    @Test
    fun allClear() {
        tap("5", "+", "3")
        tap("AC")
        assertExpression("")
        assertResult("")
    }

    @Test
    fun backspace() {
        tap("1", "2", "3")
        tap("⌫")
        assertExpression("12")
    }

    @Test
    fun backspaceEmptyExpression() {
        tap("⌫")
        assertExpression("")
    }

    // ── Sign toggle ─────────────────────────────────────────────────

    @Test
    fun toggleSign() {
        tap("5")
        tap("+/−")
        assertExpression("-5")
    }

    @Test
    fun toggleSignTwice() {
        tap("5")
        tap("+/−", "+/−")
        assertExpression("5")
    }

    // ── Division by zero ────────────────────────────────────────────

    @Test
    fun divisionByZero() {
        tap("5", "÷", "0")
        tapEquals()
        assertResult("Error")
        assertExpression("")
    }

    // ── Result carry-over ───────────────────────────────────────────

    @Test
    fun resultBecomesNextExpression() {
        tap("3", "+", "2")
        tapEquals()
        assertExpression("5")
        tap("+", "1")
        tapEquals()
        assertExpression("6")
    }

    // ── Multi-digit numbers ─────────────────────────────────────────

    @Test
    fun multiDigitNumber() {
        tap("1", "2", "3", "+", "4", "5", "6")
        tapEquals()
        assertExpression("579")
    }

    // ── Decimal handling ────────────────────────────────────────────

    @Test
    fun noDuplicateDecimal() {
        tap("1", ".", "2", ".")
        assertExpression("1.2")
    }

    @Test
    fun decimalInSecondOperand() {
        tap("1", "+", "2", ".", "5")
        tapEquals()
        assertExpression("3.5")
    }

    // ── Operator guards ─────────────────────────────────────────────

    @Test
    fun noConsecutiveOperators() {
        tap("5", "+", "×")
        assertExpression("5+")
    }

    @Test
    fun noLeadingOperator() {
        tap("+")
        assertExpression("")
    }

    // ── Preview ─────────────────────────────────────────────────────

    @Test
    fun previewUpdatesOnDigit() {
        tap("2", "+", "3")
        assertResult("5")
    }

    @Test
    fun previewUpdatesOnFactorial() {
        tap("5", "!")
        assertResult("120")
    }

    // ── History ─────────────────────────────────────────────────────

    @Test
    fun historyEntryCreated() {
        tap("2", "+", "3")
        tapEquals()
        assertEquals(1, vm.historyList.size)
        assertEquals("2+3", vm.historyList[0].expression)
        assertEquals("5", vm.historyList[0].result)
    }

    @Test
    fun errorNotAddedToHistory() {
        tap("5", "÷", "0")
        tapEquals()
        assertEquals(0, vm.historyList.size)
    }

    @Test
    fun loadHistoryEntry() {
        tap("2", "+", "3")
        tapEquals()
        tap("AC")
        vm.loadHistoryEntry(vm.historyList[0])
        assertExpression("5")
    }

    @Test
    fun clearHistory() {
        tap("1", "+", "1")
        tapEquals()
        tap("AC")
        tap("2", "+", "2")
        tapEquals()
        assertEquals(2, vm.historyList.size)
        vm.clearHistory()
        assertEquals(0, vm.historyList.size)
    }

    // ── Cursor ──────────────────────────────────────────────────────

    @Test
    fun cursorAtEnd() {
        tap("1", "2", "3")
        assertEquals(3, vm.cursorPosition)
    }

    @Test
    fun moveCursor() {
        tap("1", "2", "3")
        vm.moveCursorTo(1)
        assertEquals(1, vm.cursorPosition)
    }

    @Test
    fun cursorClampedToLength() {
        tap("1", "2")
        vm.moveCursorTo(99)
        assertEquals(2, vm.cursorPosition)
    }
}
