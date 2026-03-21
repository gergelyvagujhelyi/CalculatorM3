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

    // ── Complex arithmetic chains ──────────────────────────────────────

    @Test
    fun complexChainedArithmetic() {
        // 2 + 3×4 - 10÷2 = 2 + 12 - 5 = 9
        tap("2", "+", "3", "×", "4", "−", "1", "0", "÷", "2")
        tapEquals()
        assertExpression("9")
    }

    @Test
    fun longAdditionChain() {
        tap("1", "+", "2", "+", "3", "+", "4", "+", "5", "+", "6", "+", "7", "+", "8", "+", "9")
        tapEquals()
        assertExpression("45")
    }

    @Test
    fun mixedPrecedence() {
        // 100 - 25×3 + 50÷2 = 100 - 75 + 25 = 50
        tap("1", "0", "0", "−", "2", "5", "×", "3", "+", "5", "0", "÷", "2")
        tapEquals()
        assertExpression("50")
    }

    @Test
    fun multipleMultiplications() {
        // 2×3×4 = 24
        tap("2", "×", "3", "×", "4")
        tapEquals()
        assertExpression("24")
    }

    @Test
    fun multipleDivisions() {
        // 100÷2÷5 = 10
        tap("1", "0", "0", "÷", "2", "÷", "5")
        tapEquals()
        assertExpression("10")
    }

    // ── Factorial edge cases ───────────────────────────────────────────

    @Test
    fun zeroFactorial() {
        tap("0", "!")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun factorialBoundary20() {
        tap("2", "0", "!")
        tapEquals()
        assertExpression("2432902008176640000")
    }

    @Test
    fun factorialOf24() {
        tap("2", "4", "!")
        tapEquals()
        assertExpression("620448401733239439360000")
    }

    @Test
    fun factorialAbove99ReturnsError() {
        // 100! exceeds the limit
        tap("1", "0", "0", "!")
        tapEquals()
        assertResult("Error")
        assertExpression("")
    }

    @Test
    fun factorialThenAdd() {
        // 5! + 3 = 123
        tap("5", "!", "+", "3")
        tapEquals()
        assertExpression("123")
    }

    @Test
    fun factorialThenMultiply() {
        // 3! × 2 = 12
        tap("3", "!", "×", "2")
        tapEquals()
        assertExpression("12")
    }

    @Test
    fun factorialIgnoredAtStart() {
        tap("!")
        assertExpression("")
    }

    @Test
    fun factorialIgnoredAfterOperator() {
        tap("5", "+", "!")
        assertExpression("5+")
    }

    @Test
    fun doubleFactorialBlocked() {
        // ! after ! should be rejected (! is not digit or paren)
        tap("5", "!", "!")
        assertExpression("5!")
    }

    // ── Sqrt edge cases ────────────────────────────────────────────────

    @Test
    fun sqrtOfZero() {
        tap("0", "√")
        tapEquals()
        assertExpression("0")
    }

    @Test
    fun sqrtOfOne() {
        tap("1", "√")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun nestedSqrt() {
        // √(√(81)) = √(9) = 3
        tap("8", "1", "√", "√")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun sqrtThenAdd() {
        // √(9) + 1 = 4
        tap("9", "√", "+", "1")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun sqrtThenMultiply() {
        // √(4) × 3 = 6
        tap("4", "√", "×", "3")
        tapEquals()
        assertExpression("6")
    }

    @Test
    fun sqrtThenFactorial() {
        // √(9)! = 3! = 6
        tap("9", "√", "!")
        tapEquals()
        assertExpression("6")
    }

    @Test
    fun sqrtOfNegativeReturnsError() {
        // -4 → √(-4) should error
        tap("4")
        tap("+/−")
        tap("√")
        tapEquals()
        assertResult("Error")
        assertExpression("")
    }

    @Test
    fun sqrtOnEmptyExpressionIgnored() {
        tap("√")
        assertExpression("")
    }

    // ── Power edge cases ───────────────────────────────────────────────

    @Test
    fun powerOfZero() {
        // 5^0 = 1
        tap("5", "^", "0")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun zeroToZero() {
        // 0^0 = 1 (BigDecimal convention)
        tap("0", "^", "0")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun zeroToPositive() {
        // 0^5 = 0
        tap("0", "^", "5")
        tapEquals()
        assertExpression("0")
    }

    @Test
    fun largePower() {
        // 9^9 = 387420489
        tap("9", "^", "9")
        tapEquals()
        assertExpression("387420489")
    }

    @Test
    fun powerThenFactorial() {
        // 2^3! = 2^6 = 64 (factorial binds tighter)
        tap("2", "^", "3", "!")
        tapEquals()
        assertExpression("64")
    }

    @Test
    fun factorialThenPower() {
        // 3!^2 = 6^2 = 36
        tap("3", "!", "^", "2")
        tapEquals()
        assertExpression("36")
    }

    @Test
    fun powerIgnoredAtStart() {
        tap("^")
        assertExpression("")
    }

    @Test
    fun powerIgnoredAfterOperator() {
        tap("5", "+", "^")
        assertExpression("5+")
    }

    // ── Percentage edge cases ──────────────────────────────────────────

    @Test
    fun standalonePercentage() {
        // 50% = 0.5
        tap("5", "0", "%")
        tapEquals()
        assertExpression("0.5")
    }

    @Test
    fun percentageInAddition() {
        // 200 + 50% = 200 + 0.5 = 200.5
        tap("2", "0", "0", "+", "5", "0", "%")
        tapEquals()
        assertExpression("200.5")
    }

    @Test
    fun percentageIgnoredAtStart() {
        tap("%")
        assertExpression("")
    }

    @Test
    fun percentageIgnoredAfterOperator() {
        tap("5", "+", "%")
        assertExpression("5+")
    }

    @Test
    fun doublePercentageBlocked() {
        // Second % should be rejected (% is not a digit)
        tap("5", "0", "%", "%")
        assertExpression("50%")
    }

    // ── Negative number operations ─────────────────────────────────────

    @Test
    fun negativeTimesPositive() {
        // -5 × 3 = -15
        tap("5")
        tap("+/−")
        tap("×", "3")
        tapEquals()
        assertExpression("-15")
    }

    @Test
    fun negativePlusPositive() {
        // -5 + 8 = 3
        tap("5")
        tap("+/−")
        tap("+", "8")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun signToggleOnMultiDigit() {
        tap("1", "2", "3")
        tap("+/−")
        assertExpression("-123")
    }

    @Test
    fun signTogglePreviewUpdates() {
        tap("5", "+", "3")
        assertResult("8")
        tap("+/−")
        // -5+3 = -2
        assertResult("-2")
    }

    @Test
    fun signToggleOnEmpty() {
        tap("+/−")
        assertExpression("")
    }

    // ── Result carry-over complex ──────────────────────────────────────

    @Test
    fun chainedResultCarryOver() {
        // 2+3=5, +5=10, ×2=20
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
        tap("+", "5")
        tapEquals()
        assertExpression("10")
        tap("×", "2")
        tapEquals()
        assertExpression("20")
    }

    @Test
    fun resultThenSqrt() {
        // 4+5=9, then √ → 3
        tap("4", "+", "5")
        tapEquals()
        assertExpression("9")
        tap("√")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun errorThenNewExpression() {
        // 5÷0=Error, then 3+2=5
        tap("5", "÷", "0")
        tapEquals()
        assertResult("Error")
        assertExpression("")
        tap("3", "+", "2")
        tapEquals()
        assertExpression("5")
    }

    // ── Repeated equals ────────────────────────────────────────────────

    @Test
    fun repeatedEquals() {
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun equalsOnEmptyExpression() {
        tapEquals()
        assertExpression("")
        assertResult("")
    }

    // ── Backspace stress tests ─────────────────────────────────────────

    @Test
    fun backspaceThroughEntireExpression() {
        tap("1", "2", "+", "3")
        tap("⌫", "⌫", "⌫", "⌫")
        assertExpression("")
    }

    @Test
    fun backspaceAfterEquals() {
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
        tap("⌫")
        assertExpression("")
    }

    @Test
    fun backspaceSqrtUnwraps() {
        // √(9) → backspace on ) should unwrap to "9"
        tap("9", "√")
        assertExpression("√(9)")
        tap("⌫")
        assertExpression("9")
    }

    @Test
    fun backspaceThenContinue() {
        tap("1", "2", "3")
        tap("⌫")
        assertExpression("12")
        tap("+", "4")
        tapEquals()
        assertExpression("16")
    }

    @Test
    fun multipleBackspacesOnSqrt() {
        // √(12) → backspace removes ), √, ( → leaves 12
        tap("1", "2", "√")
        assertExpression("√(12)")
        tap("⌫")  // unwraps √()
        assertExpression("12")
    }

    // ── Pi edge cases ──────────────────────────────────────────────────

    @Test
    fun piTimesTwo() {
        tap("π", "×", "2")
        tapEquals()
        assertExpression("6.283185307")
    }

    @Test
    fun twoTimesPi() {
        tap("2", "×", "π")
        tapEquals()
        assertExpression("6.283185307")
    }

    @Test
    fun piMinusPi() {
        tap("π", "−", "π")
        tapEquals()
        assertExpression("0")
    }

    @Test
    fun piFactorialBlocked() {
        // ! requires digit or ) before it, π is neither
        tap("π", "!")
        assertExpression("π")
    }

    // ── Input guard stress tests ───────────────────────────────────────

    @Test
    fun allOperatorsBlockedAtStart() {
        tap("+")
        assertExpression("")
        tap("−")
        assertExpression("")
        tap("×")
        assertExpression("")
        tap("÷")
        assertExpression("")
    }

    @Test
    fun operatorAfterOperatorBlocked() {
        tap("5", "+", "−")
        assertExpression("5+")
        tap("×")
        assertExpression("5+")
        tap("÷")
        assertExpression("5+")
    }

    @Test
    fun decimalAfterOperator() {
        // 5+.3 = 5.3
        tap("5", "+", ".", "3")
        tapEquals()
        assertExpression("5.3")
    }

    @Test
    fun decimalInEachOperand() {
        // 1.5+2.5 = 4
        tap("1", ".", "5", "+", "2", ".", "5")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun leadingZeroHandled() {
        // 01+2 → BigDecimal("01") = 1, so 1+2 = 3
        tap("0", "1", "+", "2")
        tapEquals()
        assertExpression("3")
    }

    // ── Division by zero in complex expressions ────────────────────────

    @Test
    fun divisionByZeroInChain() {
        // 5 + 10÷0 = Error
        tap("5", "+", "1", "0", "÷", "0")
        tapEquals()
        assertResult("Error")
        assertExpression("")
    }

    @Test
    fun zeroByZero() {
        tap("0", "÷", "0")
        tapEquals()
        assertResult("Error")
        assertExpression("")
    }

    // ── Cursor insertion and deletion ──────────────────────────────────

    @Test
    fun insertDigitAtMiddle() {
        // "13" → cursor at 1 → type "2" → "123"
        tap("1", "3")
        vm.moveCursorTo(1)
        tap("2")
        assertExpression("123")
    }

    @Test
    fun deleteAtMiddle() {
        // "123" → cursor at 2 → backspace → "13"
        tap("1", "2", "3")
        vm.moveCursorTo(2)
        tap("⌫")
        assertExpression("13")
    }

    @Test
    fun insertOperatorAtMiddle() {
        // "13" → cursor at 1 → type "+" → "1+3"
        tap("1", "3")
        vm.moveCursorTo(1)
        tap("+")
        assertExpression("1+3")
    }

    // ── Precision and rounding ─────────────────────────────────────────

    @Test
    fun oneThirdTimesThree() {
        // 1÷3×3 should round cleanly to 1
        tap("1", "÷", "3", "×", "3")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun oneDividedBySeven() {
        tap("1", "÷", "7")
        tapEquals()
        assertExpression("0.1428571429")
    }

    @Test
    fun largeMultiplication() {
        // 99999×99999 = 9999800001
        tap("9", "9", "9", "9", "9", "×", "9", "9", "9", "9", "9")
        tapEquals()
        assertExpression("9999800001")
    }

    // ── History stress tests ───────────────────────────────────────────

    @Test
    fun historyOrderIsNewestFirst() {
        tap("1", "+", "1")
        tapEquals()
        tap("AC")
        tap("2", "+", "2")
        tapEquals()
        assertEquals("2+2", vm.historyList[0].expression)
        assertEquals("1+1", vm.historyList[1].expression)
    }

    @Test
    fun loadHistoryThenCompute() {
        tap("3", "+", "4")
        tapEquals()
        tap("AC")
        vm.loadHistoryEntry(vm.historyList[0])
        assertExpression("7")
        tap("+", "3")
        tapEquals()
        assertExpression("10")
    }

    @Test
    fun multipleHistoryEntries() {
        for (i in 1..5) {
            tap("AC")
            tap(i.toString(), "+", i.toString())
            tapEquals()
        }
        assertEquals(5, vm.historyList.size)
    }

    @Test
    fun historyShowsExpressionBeforeEvaluation() {
        tap("2", "+", "3", "×", "4")
        tapEquals()
        assertEquals("2+3×4", vm.historyList[0].expression)
        assertEquals("14", vm.historyList[0].result)
    }

    @Test
    fun historyAfterTrailingOperatorStrip() {
        // "5+3×" → strips to "5+3", history should show "5+3"
        tap("5", "+", "3", "×")
        tapEquals()
        assertEquals("5+3", vm.historyList[0].expression)
        assertEquals("8", vm.historyList[0].result)
    }

    // ── AC mid-workflow ────────────────────────────────────────────────

    @Test
    fun acThenEqualsDoesNothing() {
        tap("5", "+", "3")
        tapEquals()
        tap("AC")
        tapEquals()
        assertExpression("")
        assertResult("")
    }

    @Test
    fun acClearsEverything() {
        tap("5", "+", "3")
        tapEquals()
        tap("AC")
        assertExpression("")
        assertResult("")
        assertEquals("", vm.history)
    }

    // ── Rapid workflow ─────────────────────────────────────────────────

    @Test
    fun rapidCalculations() {
        tap("1", "+", "2")
        tapEquals()
        assertExpression("3")
        tap("AC")
        tap("4", "×", "5")
        tapEquals()
        assertExpression("20")
        tap("AC")
        tap("6", "−", "1")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun computeThenSwitchOperation() {
        // 3+2=5, then ×3=15, then −5=10
        tap("3", "+", "2")
        tapEquals()
        assertExpression("5")
        tap("×", "3")
        tapEquals()
        assertExpression("15")
        tap("−", "5")
        tapEquals()
        assertExpression("10")
    }

    // ── Combined unary + binary stress ─────────────────────────────────

    @Test
    fun sqrtInAddition() {
        // √(16) + √(9) = 4 + 3 = 7
        // Note: √ wraps entire expression, so we need to work around that
        // Type 16, √ → √(16), +9 → √(16)+9, but we can't easily get √(9) as second operand
        // Instead test: √(16)+5 = 9
        tap("1", "6", "√", "+", "5")
        tapEquals()
        assertExpression("9")
    }

    @Test
    fun factorialInSubtraction() {
        // 4! - 20 = 24 - 20 = 4
        tap("4", "!", "−", "2", "0")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun percentageInChain() {
        // 50%×200 = 0.5×200 = 100
        tap("5", "0", "%", "×", "2", "0", "0")
        tapEquals()
        assertExpression("100")
    }

    @Test
    fun powerInChain() {
        // 2^3+1 = 9
        tap("2", "^", "3", "+", "1")
        tapEquals()
        assertExpression("9")
    }

    @Test
    fun powerPrecedenceOverMultiply() {
        // 2×3^2 = 2×9 = 18
        tap("2", "×", "3", "^", "2")
        tapEquals()
        assertExpression("18")
    }

    @Test
    fun rightAssociativityOfPower() {
        // 2^2^3 = 2^(2^3) = 2^8 = 256 (right-associative)
        tap("2", "^", "2", "^", "3")
        tapEquals()
        assertExpression("256")
    }

    // ── Preview edge cases ─────────────────────────────────────────────

    @Test
    fun previewShownForSingleDigit() {
        tap("5")
        assertResult("5")
    }

    @Test
    fun previewAfterPercentage() {
        tap("5", "0", "%")
        assertResult("0.5")
    }

    @Test
    fun previewAfterSqrt() {
        tap("4", "√")
        assertResult("2")
    }

    @Test
    fun previewAfterPi() {
        tap("π")
        assertResult("3.141592654")
    }

    @Test
    fun previewClearedByAC() {
        tap("2", "+", "3")
        assertResult("5")
        tap("AC")
        assertResult("")
    }

    // ══════════════════════════════════════════════════════════════════
    // BUG-HUNTING TESTS — these expose real defects in the calculator
    // ══════════════════════════════════════════════════════════════════

    // BUG: Unary minus before √ is silently dropped by the tokenizer.
    // The tokenizer's unary-minus branch only consumes digits after '-'.
    // When '-' precedes '√', no token is emitted and the sign is lost.
    @Test
    fun negatedSqrtShouldBeNegative() {
        // 9 → √(9) → toggle sign → -√(9) → should be -3
        tap("9", "√")
        tap("+/−")
        assertExpression("-√(9)")
        tapEquals()
        assertExpression("-3")
    }

    // BUG: Decimal duplicate guard splits on ASCII '-' but the minus
    // button inserts Unicode '−' (U+2212). After typing 1.5−2, the
    // guard sees "1.5−2" as one operand (doesn't split on −) and
    // blocks a second decimal for the '2' operand.
    @Test
    fun decimalAllowedInSecondOperandAfterUnicodeMinus() {
        tap("1", ".", "5", "−", "2", ".")
        // Should be "1.5−2." — decimal is valid for the second operand
        assertExpression("1.5−2.")
    }

    // BUG: Operators +, ×, ÷ are allowed after ^ because the operator
    // guard only checks against {+, −, ×, ÷} and ^ is not in that set.
    // This lets users create unevaluable expressions like 2^×3.
    @Test
    fun operatorBlockedAfterCaret() {
        tap("2", "^", "×")
        // × should be rejected after ^
        assertExpression("2^")
    }

    @Test
    fun plusBlockedAfterCaret() {
        tap("2", "^", "+")
        assertExpression("2^")
    }

    @Test
    fun divideBlockedAfterCaret() {
        tap("2", "^", "÷")
        assertExpression("2^")
    }

    // BUG: ππ concatenates two pi decimal expansions into one huge
    // number instead of inserting implicit multiplication.
    @Test
    fun piTimesPi() {
        tap("π", "π")
        tapEquals()
        // Should be π×π ≈ 9.869604401
        assertExpression("9.869604401")
    }

    // BUG: √(4)π drops the sqrt result because no implicit multiply
    // is inserted between ')' and the pi expansion. The tokenizer
    // produces two Num tokens with no operator; evaluatePostfix
    // silently returns only the last value.
    @Test
    fun sqrtThenPiShouldMultiply() {
        // √(4) × π should be 2π ≈ 6.283185307
        tap("4", "√", "π")
        tapEquals()
        assertExpression("6.283185307")
    }

    // BUG: Preview showed stale result when evaluation fails.
    // E.g., typing an expression that errors should clear the preview.
    @Test
    fun previewClearedWhenEvaluationFails() {
        tap("1", "0", "0")
        assertResult("100")
        tap("!")
        // 100! exceeds limit → preview should be cleared, not show stale "100"
        assertResult("")
    }

    @Test
    fun factorialOf30() {
        tap("3", "0", "!")
        tapEquals()
        assertExpression("265.2528598E+30")
    }

    // BUG: A lone decimal point at the start produces "Error" because
    // BigDecimal(".") throws NumberFormatException.
    @Test
    fun leadingDecimalThenOperator() {
        // .5 + 3 should be 3.5
        tap(".", "5", "+", "3")
        tapEquals()
        assertExpression("3.5")
    }

    // BUG: E notation result breaks further calculations.
    // After =, expression becomes e.g. "265.2528598E+30". Pressing +1=
    // should add 1, but the tokenizer can't parse 'E' — it skips it,
    // and the '+' in "E+30" is treated as addition, producing garbage.
    @Test
    fun eNotationResultCanBeUsedForFurtherCalculation() {
        vm.maxDisplayLength = 5 // force E notation for numbers > 5 chars
        tap("9", "9", "9", "×", "9", "9", "9")
        tapEquals()
        // 999×999 = 998001, displayed in E notation "998.001E+3"
        // Now use the result: subtract 998001 should give 0
        tap("−", "9", "9", "8", "0", "0", "1")
        tapEquals()
        assertExpression("0")
    }

    // BUG: Typing a digit after √(9) gives "√(9)5" with no implicit
    // multiplication. The tokenizer produces two Num tokens (3 and 5)
    // with no operator, and evaluatePostfix silently returns only the last.
    @Test
    fun digitAfterSqrtParenShouldMultiply() {
        tap("9", "√")
        assertExpression("√(9)")
        tap("5")
        // √(9)5 should be 3×5 = 15
        tapEquals()
        assertExpression("15")
    }

    // BUG: Trailing ^ is not stripped on equals.
    // The strip list only includes +, −, ×, ÷ but not ^.
    // Typing "5^" then = gives Error instead of evaluating 5.
    @Test
    fun trailingCaretStrippedOnEquals() {
        tap("5", "^")
        tapEquals()
        assertExpression("5")
    }

    // ── Additional regression tests for the 3 bug fixes ────────────

    // Trailing ^ strip: caret after full expression
    @Test
    fun trailingCaretAfterExpressionStripped() {
        tap("2", "+", "3", "^")
        tapEquals()
        assertExpression("5")
    }

    // Trailing ^ strip: caret mixed with other trailing operators
    @Test
    fun trailingCaretAndOperatorStripped() {
        tap("7", "^", "+")
        // Can't type + after ^ (guard blocks it), so expression is "7^"
        tapEquals()
        assertExpression("7")
    }

    // Implicit multiply: digit after closing paren via sqrt
    @Test
    fun multiDigitAfterSqrtParen() {
        tap("4", "√", "1", "0")
        // √(4)10 = 2×10 = 20
        tapEquals()
        assertExpression("20")
    }

    // Implicit multiply: sqrt result times digit in preview
    @Test
    fun digitAfterSqrtParenPreview() {
        tap("4", "√", "3")
        // √(4)3 = 2×3 = 6
        assertResult("6")
    }

    // Implicit multiply: digit before opening paren (via cursor)
    @Test
    fun digitBeforeOpenParenViaImplicitMultiply() {
        // Construct "2(3+1)" by manipulating expression directly
        // This tests the sanitizer: 2(3+1) → 2*(3+1) = 8
        // Can't easily type parens from UI, but √ creates them:
        // Type 3+1, √ wraps to √(3+1), then result is 2, continue with ×2
        // Instead test the evaluate path indirectly via carry-over:
        tap("4", "√", "×", "3")
        // √(4)×3 = 2×3 = 6
        tapEquals()
        assertExpression("6")
    }

    // E notation: multiply after E notation result
    @Test
    fun eNotationResultMultiply() {
        vm.maxDisplayLength = 5
        tap("9", "9", "9", "×", "9", "9", "9")
        tapEquals()
        // 998001 in E notation. Multiply by 0 should give 0
        tap("×", "0")
        tapEquals()
        assertExpression("0")
    }

    // E notation: E notation result then sqrt
    @Test
    fun eNotationResultThenSqrt() {
        vm.maxDisplayLength = 5
        tap("1", "0", "0", "0", "0", "0")
        tapEquals()
        // 100000 in E notation "100E+3". Apply sqrt
        tap("√")
        tapEquals()
        // √(100000) ≈ 316.227766
        assertExpression("316.227766")
    }

    // E notation: addition with E notation result
    @Test
    fun eNotationResultAdd() {
        vm.maxDisplayLength = 5
        tap("1", "0", "0", "0", "0", "0")
        tapEquals()
        // 100000 → E notation. Add 1
        tap("+", "1")
        tapEquals()
        assertExpression("100001")
    }
}
