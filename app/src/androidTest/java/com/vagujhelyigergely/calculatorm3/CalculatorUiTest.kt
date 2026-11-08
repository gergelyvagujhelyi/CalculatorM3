package com.vagujhelyigergely.calculatorm3

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests — require an emulator or device running API 34 or lower.
 * API 35 has a known AndroidX Test incompatibility (InputManager.getInstance removal).
 */
class CalculatorUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun launch() {
        composeTestRule.setContent {
            CalculatorM3Theme {
                CalculatorScreen(viewModel = CalculatorViewModel())
            }
        }
    }

    private fun tap(vararg labels: String) {
        labels.forEach {
            // Match only clickable buttons, not the expression display
            composeTestRule.onNode(hasText(it) and hasClickAction()).performClick()
        }
    }

    // Assertions target one tagged display and compare its exact text, so a keypad button,
    // the history label or the preview can't satisfy a check meant for the expression.

    /** The expression line shows exactly [text] (as formatted on screen, e.g. "1,024"). */
    private fun displayShows(text: String) {
        composeTestRule.onNodeWithTag(CalculatorTestTags.EXPRESSION).assertTextEquals(text)
    }

    /**
     * The expression line starts with [prefix]. Only for long decimals (π, 1/7), whose visible
     * digit count depends on the screen width.
     */
    private fun displayStartsWith(prefix: String) {
        val shown = composeTestRule.onNodeWithTag(CalculatorTestTags.EXPRESSION)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("expected display to start with '$prefix' but was '$shown'", shown.startsWith(prefix))
    }

    /** The preview / error line shows the localized error [messageRes], and the expression is cleared. */
    private fun errorShows(@StringRes messageRes: Int) {
        val message = InstrumentationRegistry.getInstrumentation().targetContext.getString(messageRes)
        composeTestRule.onNodeWithTag(CalculatorTestTags.PREVIEW).assertTextEquals(message)
        displayShows("0")
    }

    // ── Basic arithmetic ────────────────────────────────────────────

    @Test
    fun addition() {
        launch()
        tap("2", "+", "3", "=")
        displayShows("5")
    }

    @Test
    fun subtraction() {
        launch()
        tap("9", "−", "4", "=")
        displayShows("5")
    }

    @Test
    fun multiplication() {
        launch()
        tap("6", "×", "7", "=")
        displayShows("42")
    }

    @Test
    fun division() {
        launch()
        tap("8", "÷", "2", "=")
        displayShows("4")
    }

    @Test
    fun decimalArithmetic() {
        launch()
        tap("0", ".", "1", "+", "0", ".", "2", "=")
        displayShows("0.3")
    }

    // ── Operator precedence ─────────────────────────────────────────

    @Test
    fun chainedOperations() {
        launch()
        tap("2", "+", "3", "×", "4", "=")
        displayShows("14")
    }

    // ── Trailing operator stripped ───────────────────────────────────

    @Test
    fun trailingOperatorStripped() {
        launch()
        tap("5", "+", "3", "×", "=")
        displayShows("8")
    }

    // ── Unary operators ─────────────────────────────────────────────

    @Test
    fun squareRoot() {
        launch()
        tap("9", "√", "=")
        displayShows("3")
    }

    @Test
    fun factorial() {
        launch()
        tap("5", "!", "=")
        displayShows("120")
    }

    @Test
    fun percentage() {
        launch()
        tap("2", "0", "0", "×", "5", "0", "%", "=")
        displayShows("100")
    }

    // ── Special values ──────────────────────────────────────────────

    @Test
    fun power() {
        launch()
        tap("2", "^", "1", "0", "=")
        displayShows("1,024")
    }

    // ── Clear and backspace ─────────────────────────────────────────

    @Test
    fun allClear() {
        launch()
        tap("5", "+", "3")
        tap("AC")
        displayShows("0")
    }

    @Test
    fun backspace() {
        launch()
        tap("1", "2", "3")
        tap("⌫")
        displayShows("12")
    }

    // ── Sign toggle ─────────────────────────────────────────────────

    @Test
    fun toggleSign() {
        launch()
        tap("5")
        tap("+/−")
        displayShows("-5") // leading minus is unary, no spaces
    }

    // ── Result carry-over ───────────────────────────────────────────

    @Test
    fun resultBecomesNextExpression() {
        launch()
        tap("3", "+", "2", "=")
        tap("+", "1", "=")
        displayShows("6")
    }

    // ── Multi-digit ─────────────────────────────────────────────────

    @Test
    fun multiDigitNumber() {
        launch()
        tap("1", "2", "3", "+", "4", "5", "6", "=")
        displayShows("579")
    }

    // ── Decimal guard ───────────────────────────────────────────────

    @Test
    fun noDuplicateDecimal() {
        launch()
        tap("1", ".", "2", ".")
        displayShows("1.2")
    }

    // ── Complex arithmetic chains ──────────────────────────────────

    @Test
    fun complexChainedArithmetic() {
        launch()
        // 2 + 3×4 - 10÷2 = 2 + 12 - 5 = 9
        tap("2", "+", "3", "×", "4", "−", "1", "0", "÷", "2", "=")
        displayShows("9")
    }

    @Test
    fun longAdditionChain() {
        launch()
        tap("1", "+", "2", "+", "3", "+", "4", "+", "5", "+", "6", "+", "7", "+", "8", "+", "9", "=")
        displayShows("45")
    }

    @Test
    fun mixedPrecedence() {
        launch()
        // 100 - 25×3 + 50÷2 = 50
        tap("1", "0", "0", "−", "2", "5", "×", "3", "+", "5", "0", "÷", "2", "=")
        displayShows("50")
    }

    // ── Factorial edge cases ───────────────────────────────────────

    @Test
    fun zeroFactorial() {
        launch()
        tap("0", "!", "=")
        displayShows("1")
    }

    @Test
    fun factorialThenAdd() {
        launch()
        // 5! + 3 = 123
        tap("5", "!", "+", "3", "=")
        displayShows("123")
    }

    @Test
    fun doubleFactorialBlocked() {
        launch()
        // Only one ! should be accepted
        tap("5", "!", "!", "=")
        displayShows("120")
    }

    // ── Sqrt edge cases ────────────────────────────────────────────

    @Test
    fun nestedSqrt() {
        launch()
        // √(√(81)) = 3
        tap("8", "1", "√", "√", "=")
        displayShows("3")
    }

    @Test
    fun sqrtThenAdd() {
        launch()
        // √(9) + 1 = 4
        tap("9", "√", "+", "1", "=")
        displayShows("4")
    }

    @Test
    fun sqrtThenFactorial() {
        launch()
        // √(9)! = 3! = 6
        tap("9", "√", "!", "=")
        displayShows("6")
    }

    // ── Power edge cases ───────────────────────────────────────────

    @Test
    fun powerOfZero() {
        launch()
        tap("5", "^", "0", "=")
        displayShows("1")
    }

    @Test
    fun powerPrecedenceOverMultiply() {
        launch()
        // 2×3^2 = 18
        tap("2", "×", "3", "^", "2", "=")
        displayShows("18")
    }

    @Test
    fun rightAssociativityOfPower() {
        launch()
        // 2^2^3 = 256
        tap("2", "^", "2", "^", "3", "=")
        displayShows("256")
    }

    // ── Percentage edge cases ──────────────────────────────────────

    @Test
    fun standalonePercentage() {
        launch()
        tap("5", "0", "%", "=")
        displayShows("0.5")
    }

    // ── Negative numbers ───────────────────────────────────────────

    @Test
    fun negativeTimesPositive() {
        launch()
        // -5 × 3 = -15
        tap("5")
        tap("+/−")
        tap("×", "3", "=")
        displayShows("-15") // leading minus is unary, no spaces
    }

    @Test
    fun signToggleTwiceRestores() {
        launch()
        tap("5")
        tap("+/−", "+/−")
        displayShows("5")
    }

    // ── Result carry-over complex ──────────────────────────────────

    @Test
    fun chainedResultCarryOver() {
        launch()
        // 2+3=5, +5=10, ×2=20
        tap("2", "+", "3", "=")
        displayShows("5")
        tap("+", "5", "=")
        displayShows("10")
        tap("×", "2", "=")
        displayShows("20")
    }

    @Test
    fun resultThenSqrt() {
        launch()
        // 4+5=9, then √ → 3
        tap("4", "+", "5", "=")
        tap("√", "=")
        displayShows("3")
    }

    @Test
    fun errorThenNewExpression() {
        launch()
        tap("5", "÷", "0", "=")
        // After error, type new expression
        tap("3", "+", "2", "=")
        displayShows("5")
    }

    // ── Repeated equals ────────────────────────────────────────────

    @Test
    fun repeatedEqualsIsIdempotent() {
        launch()
        tap("2", "+", "3", "=")
        displayShows("5")
        tap("=")
        displayShows("5")
    }

    // ── Backspace stress ───────────────────────────────────────────

    @Test
    fun backspaceThroughExpression() {
        launch()
        tap("1", "2", "+", "3")
        tap("⌫", "⌫", "⌫", "⌫")
        displayShows("0") // empty expression shows "0"
    }

    @Test
    fun backspaceThenContinue() {
        launch()
        tap("1", "2", "3")
        tap("⌫")
        tap("+", "4", "=")
        displayShows("16")
    }

    // ── Multiple trailing operators ────────────────────────────────

    @Test
    fun multipleTrailingOperatorsStripped() {
        launch()
        tap("7", "+", "−", "=")
        displayShows("7")
    }

    // ── Combined unary + binary ────────────────────────────────────

    @Test
    fun factorialInSubtraction() {
        launch()
        // 4! - 20 = 4
        tap("4", "!", "−", "2", "0", "=")
        displayShows("4")
    }

    @Test
    fun powerInChain() {
        launch()
        // 2^3+1 = 9
        tap("2", "^", "3", "+", "1", "=")
        displayShows("9")
    }

    @Test
    fun percentageInChain() {
        launch()
        // 50%×200 = 100
        tap("5", "0", "%", "×", "2", "0", "0", "=")
        displayShows("100")
    }

    // ── Precision ──────────────────────────────────────────────────

    @Test
    fun oneThirdTimesThree() {
        launch()
        // 1÷3×3 should round cleanly to 1
        tap("1", "÷", "3", "×", "3", "=")
        displayShows("1")
    }

    @Test
    fun decimalInBothOperands() {
        launch()
        // 1.5+2.5 = 4
        tap("1", ".", "5", "+", "2", ".", "5", "=")
        displayShows("4")
    }

    @Test
    fun largeMultiplication() {
        launch()
        // 99999×99999 = 9999800001
        tap("9", "9", "9", "9", "9", "×", "9", "9", "9", "9", "9", "=")
        displayShows("9,999,800,001")
    }

    // ── AC after result ────────────────────────────────────────────

    @Test
    fun acAfterResultThenNewCalc() {
        launch()
        tap("5", "+", "3", "=")
        tap("AC")
        tap("7", "×", "6", "=")
        displayShows("42")
    }

    // ── Rapid workflow ─────────────────────────────────────────────

    @Test
    fun rapidCalculations() {
        launch()
        tap("1", "+", "2", "=")
        displayShows("3")
        tap("AC")
        tap("4", "×", "5", "=")
        displayShows("20")
        tap("AC")
        tap("6", "−", "1", "=")
        displayShows("5")
    }

    // ── Operator precedence (additional) ──────────────────────────

    @Test
    fun divisionBeforeSubtraction() {
        launch()
        tap("1", "0", "−", "6", "÷", "3", "=")
        displayShows("8")
    }

    @Test
    fun multipleMultiplications() {
        launch()
        tap("2", "×", "3", "×", "4", "=")
        displayShows("24")
    }

    @Test
    fun multipleDivisions() {
        launch()
        tap("1", "0", "0", "÷", "2", "÷", "5", "=")
        displayShows("10")
    }

    // ── Pi ─────────────────────────────────────────────────────────

    @Test
    fun piValue() {
        launch()
        tap("π", "=")
        displayStartsWith("3.1415926535")
    }

    @Test
    fun piTimesTwo() {
        launch()
        tap("π", "×", "2", "=")
        displayStartsWith("6.283185307")
    }

    @Test
    fun piMinusPi() {
        launch()
        tap("π", "−", "π", "=")
        displayShows("0")
    }

    @Test
    fun piTimesPiImplicit() {
        launch()
        tap("π", "π", "=")
        displayStartsWith("9.869604401")
    }

    // ── IEEE 754 error messages ─────────────────────────────────────

    @Test
    fun divisionByZero() {
        launch()
        tap("5", "÷", "0", "=")
        errorShows(R.string.error_division_by_zero)
    }

    @Test
    fun divisionByZeroInChain() {
        launch()
        tap("5", "+", "1", "0", "÷", "0", "=")
        errorShows(R.string.error_division_by_zero)
    }

    @Test
    fun zeroByZero() {
        launch()
        tap("0", "÷", "0", "=")
        errorShows(R.string.error_division_by_zero)
    }

    // ── Factorial (additional) ────────────────────────────────────

    @Test
    fun factorialBoundary20() {
        launch()
        tap("2", "0", "!", "=")
        displayShows("2,432,902,008,176,640,000")
    }

    @Test
    fun factorialAbove99ReturnsInvalidOperation() {
        launch()
        tap("1", "0", "0", "!", "=")
        errorShows(R.string.error_invalid_operation)
    }

    @Test
    fun factorialThenMultiply() {
        launch()
        tap("3", "!", "×", "2", "=")
        displayShows("12")
    }

    @Test
    fun factorialIgnoredAtStart() {
        launch()
        // ! on empty does nothing, then 5 = 5
        tap("!", "5", "=")
        displayShows("5")
    }

    @Test
    fun factorialIgnoredAfterOperator() {
        launch()
        // 5+ then ! ignored, then 3 = 8
        tap("5", "+", "!", "3", "=")
        displayShows("8")
    }

    // ── Sqrt (additional) ─────────────────────────────────────────

    @Test
    fun sqrtOfZero() {
        launch()
        tap("0", "√", "=")
        displayShows("0")
    }

    @Test
    fun sqrtOfOne() {
        launch()
        tap("1", "√", "=")
        displayShows("1")
    }

    @Test
    fun sqrtThenMultiply() {
        launch()
        tap("4", "√", "×", "3", "=")
        displayShows("6")
    }

    @Test
    fun sqrtOfNegativeReturnsInvalidOperation() {
        launch()
        tap("4", "+/−", "√", "=")
        errorShows(R.string.error_invalid_operation)
    }

    @Test
    fun sqrtOnEmptyIgnored() {
        launch()
        // √ on empty does nothing, then 9 = 9
        tap("√", "9", "=")
        displayShows("9")
    }

    // ── Power (additional) ────────────────────────────────────────

    @Test
    fun zeroToZero() {
        launch()
        tap("0", "^", "0", "=")
        displayShows("1")
    }

    @Test
    fun zeroToPositive() {
        launch()
        tap("0", "^", "5", "=")
        displayShows("0")
    }

    @Test
    fun largePower() {
        launch()
        tap("9", "^", "9", "=")
        displayShows("387,420,489")
    }

    @Test
    fun powerThenFactorial() {
        launch()
        // 2^3! = 2^6 = 64
        tap("2", "^", "3", "!", "=")
        displayShows("64")
    }

    @Test
    fun factorialThenPower() {
        launch()
        // 3!^2 = 36
        tap("3", "!", "^", "2", "=")
        displayShows("36")
    }

    @Test
    fun powerIgnoredAtStart() {
        launch()
        // ^ on empty does nothing, then 5 = 5
        tap("^", "5", "=")
        displayShows("5")
    }

    @Test
    fun powerIgnoredAfterOperator() {
        launch()
        // 5+ then ^ ignored, then 3 = 8
        tap("5", "+", "^", "3", "=")
        displayShows("8")
    }

    // ── Percentage (additional) ───────────────────────────────────

    @Test
    fun percentageInAddition() {
        launch()
        // 200 + 50% = 300 (consumer-style)
        tap("2", "0", "0", "+", "5", "0", "%", "=")
        displayShows("300")
    }

    @Test
    fun percentageInSubtraction() {
        launch()
        // 200 - 10% = 180
        tap("2", "0", "0", "−", "1", "0", "%", "=")
        displayShows("180")
    }

    @Test
    fun percentageChainedAddSubtract() {
        launch()
        // 100 + 50% - 10% = 135
        tap("1", "0", "0", "+", "5", "0", "%", "−", "1", "0", "%", "=")
        displayShows("135")
    }

    @Test
    fun percentageIgnoredAtStart() {
        launch()
        tap("%", "5", "=")
        displayShows("5")
    }

    @Test
    fun percentageIgnoredAfterOperator() {
        launch()
        tap("5", "+", "%", "3", "=")
        displayShows("8")
    }

    @Test
    fun doublePercentageBlocked() {
        launch()
        // 50%% → second % blocked, so 50% = 0.5
        tap("5", "0", "%", "%", "=")
        displayShows("0.5")
    }

    // ── Sign toggle (additional) ──────────────────────────────────

    @Test
    fun signToggleOnMultiDigit() {
        launch()
        tap("1", "2", "3", "+/−")
        displayShows("-123")
    }

    @Test
    fun negativePlusPositive() {
        launch()
        // -5 + 8 = 3
        tap("5", "+/−", "+", "8", "=")
        displayShows("3")
    }

    @Test
    fun signToggleSecondOperand() {
        launch()
        // 5+3, toggle → 5+-3, toggle → 5+3
        // Formatted display adds spaces around +: "5 + -3" / "5 + 3"
        tap("5", "+", "3", "+/−")
        displayShows("5 + -3")
        tap("+/−")
        displayShows("5 + 3")
    }

    @Test
    fun signToggleSecondOperandEvaluates() {
        launch()
        // 10+-3 = 7
        tap("1", "0", "+", "3", "+/−", "=")
        displayShows("7")
    }

    @Test
    fun negatedSqrtEvaluates() {
        launch()
        // 9 → √(9) → +/− → -√(9) → = → -3
        tap("9", "√", "+/−", "=")
        displayShows("-3")
    }

    // ── Leading operators ─────────────────────────────────────────

    @Test
    fun leadingOperatorStrippedOnEquals() {
        launch()
        tap("+", "5", "=")
        displayShows("5")
    }

    @Test
    fun leadingMultiplyStrippedOnEquals() {
        launch()
        tap("×", "3", "=")
        displayShows("3")
    }

    @Test
    fun leadingMinusKeptOnEquals() {
        launch()
        tap("−", "5", "=")
        displayShows("-5")
    }

    // ── Operator replacement ──────────────────────────────────────

    @Test
    fun operatorAfterOperatorReplaces() {
        launch()
        tap("5", "+", "×", "3", "=")
        displayShows("15")
    }

    @Test
    fun minusReplacesOperator() {
        launch()
        tap("5", "+", "−", "3", "=")
        // − replaces +, so 5−3 = 2
        displayShows("2")
    }

    @Test
    fun operatorReplacesCaretFromEnd() {
        launch()
        // 2^× → × replaces ^, then ×3 = 6
        tap("2", "^", "×", "3", "=")
        displayShows("6")
    }

    // ── Decimal (additional) ──────────────────────────────────────

    @Test
    fun decimalAfterOperator() {
        launch()
        // 5+.3 = 5.3
        tap("5", "+", ".", "3", "=")
        displayShows("5.3")
    }

    @Test
    fun decimalInSecondOperand() {
        launch()
        tap("1", "+", "2", ".", "5", "=")
        displayShows("3.5")
    }

    @Test
    fun leadingZeroHandled() {
        launch()
        tap("0", "1", "+", "2", "=")
        displayShows("3")
    }

    // ── Backspace (additional) ────────────────────────────────────

    @Test
    fun backspaceAfterEquals() {
        launch()
        tap("2", "+", "3", "=")
        displayShows("5")
        tap("⌫")
        // After equals, backspace clears expression
        displayShows("0")
    }

    @Test
    fun backspaceSqrtUnwraps() {
        launch()
        tap("9", "√")
        displayShows("√(9)")
        tap("⌫")
        displayShows("9")
    }

    @Test
    fun multipleBackspacesOnSqrt() {
        launch()
        tap("1", "2", "√")
        displayShows("√(12)")
        tap("⌫")
        displayShows("12")
    }

    // ── Trailing caret stripped ───────────────────────────────────

    @Test
    fun trailingCaretStrippedOnEquals() {
        launch()
        tap("5", "^", "=")
        displayShows("5")
    }

    // ── Implicit multiplication ───────────────────────────────────

    @Test
    fun digitAfterSqrtParenShouldMultiply() {
        launch()
        // √(9)5 = 3×5 = 15
        tap("9", "√", "5", "=")
        displayShows("15")
    }

    @Test
    fun digitAfterFactorialShouldMultiply() {
        launch()
        // 5!3 = 120×3 = 360
        tap("5", "!", "3", "=")
        displayShows("360")
    }

    @Test
    fun digitAfterPercentShouldMultiply() {
        launch()
        // 50%5 = 0.5×5 = 2.5
        tap("5", "0", "%", "5", "=")
        displayShows("2.5")
    }

    @Test
    fun sqrtThenPiShouldMultiply() {
        launch()
        // √(4)π = 2π ≈ 6.283185307
        tap("4", "√", "π", "=")
        displayStartsWith("6.283185307")
    }

    // ── Precision (additional) ────────────────────────────────────

    @Test
    fun oneDividedBySeven() {
        launch()
        tap("1", "÷", "7", "=")
        displayStartsWith("0.1428571428")
    }

    // ── Combined unary + binary (additional) ──────────────────────

    @Test
    fun sqrtInAddition() {
        launch()
        // √(16)+5 = 4+5 = 9
        tap("1", "6", "√", "+", "5", "=")
        displayShows("9")
    }

    @Test
    fun computeThenSwitchOperation() {
        launch()
        tap("3", "+", "2", "=")
        displayShows("5")
        tap("×", "3", "=")
        displayShows("15")
        tap("−", "5", "=")
        displayShows("10")
    }

    // ── Lone decimal point ────────────────────────────────────────

    @Test
    fun loneDecimalPointEvaluatesToZero() {
        launch()
        tap(".", "=")
        displayShows("0")
    }

    @Test
    fun leadingDecimalThenOperator() {
        launch()
        // .5 + 3 = 3.5
        tap(".", "5", "+", "3", "=")
        displayShows("3.5")
    }

    // ── IEEE 754 overflow ────────────────────────────────────────────

    @Test
    fun overflowOnLargePower() {
        launch()
        // 9^9999 overflows Double → Error: overflow
        tap("9", "^", "9", "9", "9", "9", "=")
        errorShows(R.string.error_overflow)
    }

    // ── IEEE 754 error recovery ──────────────────────────────────────

    @Test
    fun newExpressionAfterDivisionByZero() {
        launch()
        tap("5", "÷", "0", "=")
        errorShows(R.string.error_division_by_zero)
        tap("3", "+", "2", "=")
        displayShows("5")
    }

    @Test
    fun newExpressionAfterInvalidOperation() {
        launch()
        tap("4", "+/−", "√", "=")
        errorShows(R.string.error_invalid_operation)
        tap("9", "√", "=")
        displayShows("3")
    }

    @Test
    fun newExpressionAfterOverflow() {
        launch()
        tap("9", "^", "9", "9", "9", "9", "=")
        errorShows(R.string.error_overflow)
        tap("2", "+", "3", "=")
        displayShows("5")
    }

    // ── Preview and history label ───────────────────────────────────

    @Test
    fun previewShowsLiveResultWhileTyping() {
        launch()
        tap("5", "+", "3")
        displayShows("5 + 3")
        composeTestRule.onNodeWithTag(CalculatorTestTags.PREVIEW).assertTextEquals("= 8")
    }

    @Test
    fun historyLabelShowsCompletedExpression() {
        launch()
        tap("1", "2", "0", "0", "+", "3", "=")
        displayShows("1,203")
        composeTestRule.onNodeWithTag(CalculatorTestTags.HISTORY_LABEL).assertTextEquals("1,200 + 3 =")
        // The preview is hidden once the answer is on the expression line.
        composeTestRule.onNodeWithTag(CalculatorTestTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun keypadDigitDoesNotSatisfyDisplayAssertion() {
        // Regression guard for the old substring helper: a visible "7" button used to make
        // any check for "7" pass. The display really shows "0" here.
        launch()
        composeTestRule.onNodeWithTag(CalculatorTestTags.EXPRESSION).assertTextEquals("0")
        composeTestRule.onNode(hasText("7") and hasClickAction()).assertIsDisplayed()
    }
}
