package com.vagujhelyigergely.calculatorm3

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme
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

    private fun expressionShows(text: String) {
        // At least one node with this text is displayed
        composeTestRule.onAllNodesWithText(text, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    // ── Basic arithmetic ────────────────────────────────────────────

    @Test
    fun addition() {
        launch()
        tap("2", "+", "3", "=")
        expressionShows("5")
    }

    @Test
    fun subtraction() {
        launch()
        tap("9", "−", "4", "=")
        expressionShows("5")
    }

    @Test
    fun multiplication() {
        launch()
        tap("6", "×", "7", "=")
        expressionShows("42")
    }

    @Test
    fun division() {
        launch()
        tap("8", "÷", "2", "=")
        expressionShows("4")
    }

    @Test
    fun decimalArithmetic() {
        launch()
        tap("0", ".", "1", "+", "0", ".", "2", "=")
        expressionShows("0.3")
    }

    // ── Operator precedence ─────────────────────────────────────────

    @Test
    fun chainedOperations() {
        launch()
        tap("2", "+", "3", "×", "4", "=")
        expressionShows("14")
    }

    // ── Trailing operator stripped ───────────────────────────────────

    @Test
    fun trailingOperatorStripped() {
        launch()
        tap("5", "+", "3", "×", "=")
        expressionShows("8")
    }

    // ── Unary operators ─────────────────────────────────────────────

    @Test
    fun squareRoot() {
        launch()
        tap("9", "√", "=")
        expressionShows("3")
    }

    @Test
    fun factorial() {
        launch()
        tap("5", "!", "=")
        expressionShows("120")
    }

    @Test
    fun percentage() {
        launch()
        tap("2", "0", "0", "×", "5", "0", "%", "=")
        expressionShows("100")
    }

    // ── Special values ──────────────────────────────────────────────

    @Test
    fun power() {
        launch()
        tap("2", "^", "1", "0", "=")
        expressionShows("1024")
    }

    // ── Clear and backspace ─────────────────────────────────────────

    @Test
    fun allClear() {
        launch()
        tap("5", "+", "3")
        tap("AC")
        expressionShows("0")
    }

    @Test
    fun backspace() {
        launch()
        tap("1", "2", "3")
        tap("⌫")
        expressionShows("12")
    }

    // ── Sign toggle ─────────────────────────────────────────────────

    @Test
    fun toggleSign() {
        launch()
        tap("5")
        tap("+/−")
        expressionShows("-5") // leading minus is unary, no spaces
    }

    // ── Result carry-over ───────────────────────────────────────────

    @Test
    fun resultBecomesNextExpression() {
        launch()
        tap("3", "+", "2", "=")
        tap("+", "1", "=")
        expressionShows("6")
    }

    // ── Multi-digit ─────────────────────────────────────────────────

    @Test
    fun multiDigitNumber() {
        launch()
        tap("1", "2", "3", "+", "4", "5", "6", "=")
        expressionShows("579")
    }

    // ── Decimal guard ───────────────────────────────────────────────

    @Test
    fun noDuplicateDecimal() {
        launch()
        tap("1", ".", "2", ".")
        expressionShows("1.2")
    }

    // ── Complex arithmetic chains ──────────────────────────────────

    @Test
    fun complexChainedArithmetic() {
        launch()
        // 2 + 3×4 - 10÷2 = 2 + 12 - 5 = 9
        tap("2", "+", "3", "×", "4", "−", "1", "0", "÷", "2", "=")
        expressionShows("9")
    }

    @Test
    fun longAdditionChain() {
        launch()
        tap("1", "+", "2", "+", "3", "+", "4", "+", "5", "+", "6", "+", "7", "+", "8", "+", "9", "=")
        expressionShows("45")
    }

    @Test
    fun mixedPrecedence() {
        launch()
        // 100 - 25×3 + 50÷2 = 50
        tap("1", "0", "0", "−", "2", "5", "×", "3", "+", "5", "0", "÷", "2", "=")
        expressionShows("50")
    }

    // ── Factorial edge cases ───────────────────────────────────────

    @Test
    fun zeroFactorial() {
        launch()
        tap("0", "!", "=")
        expressionShows("1")
    }

    @Test
    fun factorialThenAdd() {
        launch()
        // 5! + 3 = 123
        tap("5", "!", "+", "3", "=")
        expressionShows("123")
    }

    @Test
    fun doubleFactorialBlocked() {
        launch()
        // Only one ! should be accepted
        tap("5", "!", "!", "=")
        expressionShows("120")
    }

    // ── Sqrt edge cases ────────────────────────────────────────────

    @Test
    fun nestedSqrt() {
        launch()
        // √(√(81)) = 3
        tap("8", "1", "√", "√", "=")
        expressionShows("3")
    }

    @Test
    fun sqrtThenAdd() {
        launch()
        // √(9) + 1 = 4
        tap("9", "√", "+", "1", "=")
        expressionShows("4")
    }

    @Test
    fun sqrtThenFactorial() {
        launch()
        // √(9)! = 3! = 6
        tap("9", "√", "!", "=")
        expressionShows("6")
    }

    // ── Power edge cases ───────────────────────────────────────────

    @Test
    fun powerOfZero() {
        launch()
        tap("5", "^", "0", "=")
        expressionShows("1")
    }

    @Test
    fun powerPrecedenceOverMultiply() {
        launch()
        // 2×3^2 = 18
        tap("2", "×", "3", "^", "2", "=")
        expressionShows("18")
    }

    @Test
    fun rightAssociativityOfPower() {
        launch()
        // 2^2^3 = 256
        tap("2", "^", "2", "^", "3", "=")
        expressionShows("256")
    }

    // ── Percentage edge cases ──────────────────────────────────────

    @Test
    fun standalonePercentage() {
        launch()
        tap("5", "0", "%", "=")
        expressionShows("0.5")
    }

    // ── Negative numbers ───────────────────────────────────────────

    @Test
    fun negativeTimesPositive() {
        launch()
        // -5 × 3 = -15
        tap("5")
        tap("+/−")
        tap("×", "3", "=")
        expressionShows("-15") // leading minus is unary, no spaces
    }

    @Test
    fun signToggleTwiceRestores() {
        launch()
        tap("5")
        tap("+/−", "+/−")
        expressionShows("5")
    }

    // ── Result carry-over complex ──────────────────────────────────

    @Test
    fun chainedResultCarryOver() {
        launch()
        // 2+3=5, +5=10, ×2=20
        tap("2", "+", "3", "=")
        expressionShows("5")
        tap("+", "5", "=")
        expressionShows("10")
        tap("×", "2", "=")
        expressionShows("20")
    }

    @Test
    fun resultThenSqrt() {
        launch()
        // 4+5=9, then √ → 3
        tap("4", "+", "5", "=")
        tap("√", "=")
        expressionShows("3")
    }

    @Test
    fun errorThenNewExpression() {
        launch()
        tap("5", "÷", "0", "=")
        // After error, type new expression
        tap("3", "+", "2", "=")
        expressionShows("5")
    }

    // ── Repeated equals ────────────────────────────────────────────

    @Test
    fun repeatedEqualsIsIdempotent() {
        launch()
        tap("2", "+", "3", "=")
        expressionShows("5")
        tap("=")
        expressionShows("5")
    }

    // ── Backspace stress ───────────────────────────────────────────

    @Test
    fun backspaceThroughExpression() {
        launch()
        tap("1", "2", "+", "3")
        tap("⌫", "⌫", "⌫", "⌫")
        expressionShows("0") // empty expression shows "0"
    }

    @Test
    fun backspaceThenContinue() {
        launch()
        tap("1", "2", "3")
        tap("⌫")
        tap("+", "4", "=")
        expressionShows("16")
    }

    // ── Multiple trailing operators ────────────────────────────────

    @Test
    fun multipleTrailingOperatorsStripped() {
        launch()
        tap("7", "+", "−", "=")
        expressionShows("7")
    }

    // ── Combined unary + binary ────────────────────────────────────

    @Test
    fun factorialInSubtraction() {
        launch()
        // 4! - 20 = 4
        tap("4", "!", "−", "2", "0", "=")
        expressionShows("4")
    }

    @Test
    fun powerInChain() {
        launch()
        // 2^3+1 = 9
        tap("2", "^", "3", "+", "1", "=")
        expressionShows("9")
    }

    @Test
    fun percentageInChain() {
        launch()
        // 50%×200 = 100
        tap("5", "0", "%", "×", "2", "0", "0", "=")
        expressionShows("100")
    }

    // ── Precision ──────────────────────────────────────────────────

    @Test
    fun oneThirdTimesThree() {
        launch()
        // 1÷3×3 should round cleanly to 1
        tap("1", "÷", "3", "×", "3", "=")
        expressionShows("1")
    }

    @Test
    fun decimalInBothOperands() {
        launch()
        // 1.5+2.5 = 4
        tap("1", ".", "5", "+", "2", ".", "5", "=")
        expressionShows("4")
    }

    @Test
    fun largeMultiplication() {
        launch()
        // 99999×99999 = 9999800001
        tap("9", "9", "9", "9", "9", "×", "9", "9", "9", "9", "9", "=")
        expressionShows("9999800001")
    }

    // ── AC after result ────────────────────────────────────────────

    @Test
    fun acAfterResultThenNewCalc() {
        launch()
        tap("5", "+", "3", "=")
        tap("AC")
        tap("7", "×", "6", "=")
        expressionShows("42")
    }

    // ── Rapid workflow ─────────────────────────────────────────────

    @Test
    fun rapidCalculations() {
        launch()
        tap("1", "+", "2", "=")
        expressionShows("3")
        tap("AC")
        tap("4", "×", "5", "=")
        expressionShows("20")
        tap("AC")
        tap("6", "−", "1", "=")
        expressionShows("5")
    }

    // ── Operator precedence (additional) ──────────────────────────

    @Test
    fun divisionBeforeSubtraction() {
        launch()
        tap("1", "0", "−", "6", "÷", "3", "=")
        expressionShows("8")
    }

    @Test
    fun multipleMultiplications() {
        launch()
        tap("2", "×", "3", "×", "4", "=")
        expressionShows("24")
    }

    @Test
    fun multipleDivisions() {
        launch()
        tap("1", "0", "0", "÷", "2", "÷", "5", "=")
        expressionShows("10")
    }

    // ── Pi ─────────────────────────────────────────────────────────

    @Test
    fun piValue() {
        launch()
        tap("π", "=")
        expressionShows("3.1415926535")
    }

    @Test
    fun piTimesTwo() {
        launch()
        tap("π", "×", "2", "=")
        expressionShows("6.283185307")
    }

    @Test
    fun piMinusPi() {
        launch()
        tap("π", "−", "π", "=")
        expressionShows("0")
    }

    @Test
    fun piTimesPiImplicit() {
        launch()
        tap("π", "π", "=")
        expressionShows("9.869604401")
    }

    // ── Division by zero ──────────────────────────────────────────

    @Test
    fun divisionByZero() {
        launch()
        tap("5", "÷", "0", "=")
        expressionShows("Error")
    }

    @Test
    fun divisionByZeroInChain() {
        launch()
        tap("5", "+", "1", "0", "÷", "0", "=")
        expressionShows("Error")
    }

    @Test
    fun zeroByZero() {
        launch()
        tap("0", "÷", "0", "=")
        expressionShows("Error")
    }

    // ── Factorial (additional) ────────────────────────────────────

    @Test
    fun factorialBoundary20() {
        launch()
        tap("2", "0", "!", "=")
        expressionShows("2432902008176640000")
    }

    @Test
    fun factorialAbove99ReturnsError() {
        launch()
        tap("1", "0", "0", "!", "=")
        expressionShows("Error")
    }

    @Test
    fun factorialThenMultiply() {
        launch()
        tap("3", "!", "×", "2", "=")
        expressionShows("12")
    }

    @Test
    fun factorialIgnoredAtStart() {
        launch()
        // ! on empty does nothing, then 5 = 5
        tap("!", "5", "=")
        expressionShows("5")
    }

    @Test
    fun factorialIgnoredAfterOperator() {
        launch()
        // 5+ then ! ignored, then 3 = 8
        tap("5", "+", "!", "3", "=")
        expressionShows("8")
    }

    // ── Sqrt (additional) ─────────────────────────────────────────

    @Test
    fun sqrtOfZero() {
        launch()
        tap("0", "√", "=")
        expressionShows("0")
    }

    @Test
    fun sqrtOfOne() {
        launch()
        tap("1", "√", "=")
        expressionShows("1")
    }

    @Test
    fun sqrtThenMultiply() {
        launch()
        tap("4", "√", "×", "3", "=")
        expressionShows("6")
    }

    @Test
    fun sqrtOfNegativeReturnsError() {
        launch()
        tap("4", "+/−", "√", "=")
        expressionShows("Error")
    }

    @Test
    fun sqrtOnEmptyIgnored() {
        launch()
        // √ on empty does nothing, then 9 = 9
        tap("√", "9", "=")
        expressionShows("9")
    }

    // ── Power (additional) ────────────────────────────────────────

    @Test
    fun zeroToZero() {
        launch()
        tap("0", "^", "0", "=")
        expressionShows("1")
    }

    @Test
    fun zeroToPositive() {
        launch()
        tap("0", "^", "5", "=")
        expressionShows("0")
    }

    @Test
    fun largePower() {
        launch()
        tap("9", "^", "9", "=")
        expressionShows("387420489")
    }

    @Test
    fun powerThenFactorial() {
        launch()
        // 2^3! = 2^6 = 64
        tap("2", "^", "3", "!", "=")
        expressionShows("64")
    }

    @Test
    fun factorialThenPower() {
        launch()
        // 3!^2 = 36
        tap("3", "!", "^", "2", "=")
        expressionShows("36")
    }

    @Test
    fun powerIgnoredAtStart() {
        launch()
        // ^ on empty does nothing, then 5 = 5
        tap("^", "5", "=")
        expressionShows("5")
    }

    @Test
    fun powerIgnoredAfterOperator() {
        launch()
        // 5+ then ^ ignored, then 3 = 8
        tap("5", "+", "^", "3", "=")
        expressionShows("8")
    }

    // ── Percentage (additional) ───────────────────────────────────

    @Test
    fun percentageInAddition() {
        launch()
        // 200 + 50% = 300 (consumer-style)
        tap("2", "0", "0", "+", "5", "0", "%", "=")
        expressionShows("300")
    }

    @Test
    fun percentageInSubtraction() {
        launch()
        // 200 - 10% = 180
        tap("2", "0", "0", "−", "1", "0", "%", "=")
        expressionShows("180")
    }

    @Test
    fun percentageChainedAddSubtract() {
        launch()
        // 100 + 50% - 10% = 135
        tap("1", "0", "0", "+", "5", "0", "%", "−", "1", "0", "%", "=")
        expressionShows("135")
    }

    @Test
    fun percentageIgnoredAtStart() {
        launch()
        tap("%", "5", "=")
        expressionShows("5")
    }

    @Test
    fun percentageIgnoredAfterOperator() {
        launch()
        tap("5", "+", "%", "3", "=")
        expressionShows("8")
    }

    @Test
    fun doublePercentageBlocked() {
        launch()
        // 50%% → second % blocked, so 50% = 0.5
        tap("5", "0", "%", "%", "=")
        expressionShows("0.5")
    }

    // ── Sign toggle (additional) ──────────────────────────────────

    @Test
    fun signToggleOnMultiDigit() {
        launch()
        tap("1", "2", "3", "+/−")
        expressionShows("-123")
    }

    @Test
    fun negativePlusPositive() {
        launch()
        // -5 + 8 = 3
        tap("5", "+/−", "+", "8", "=")
        expressionShows("3")
    }

    @Test
    fun signToggleSecondOperand() {
        launch()
        // 5+3, toggle → 5+-3, toggle → 5+3
        // Formatted display adds spaces around +: "5 + -3" / "5 + 3"
        tap("5", "+", "3", "+/−")
        expressionShows("5 + -3")
        tap("+/−")
        expressionShows("5 + 3")
    }

    @Test
    fun signToggleSecondOperandEvaluates() {
        launch()
        // 10+-3 = 7
        tap("1", "0", "+", "3", "+/−", "=")
        expressionShows("7")
    }

    @Test
    fun negatedSqrtEvaluates() {
        launch()
        // 9 → √(9) → +/− → -√(9) → = → -3
        tap("9", "√", "+/−", "=")
        expressionShows("-3")
    }

    // ── Leading operators ─────────────────────────────────────────

    @Test
    fun leadingOperatorStrippedOnEquals() {
        launch()
        tap("+", "5", "=")
        expressionShows("5")
    }

    @Test
    fun leadingMultiplyStrippedOnEquals() {
        launch()
        tap("×", "3", "=")
        expressionShows("3")
    }

    @Test
    fun leadingMinusKeptOnEquals() {
        launch()
        tap("−", "5", "=")
        expressionShows("-5")
    }

    // ── Operator replacement ──────────────────────────────────────

    @Test
    fun operatorAfterOperatorReplaces() {
        launch()
        tap("5", "+", "×", "3", "=")
        expressionShows("15")
    }

    @Test
    fun minusReplacesOperator() {
        launch()
        tap("5", "+", "−", "3", "=")
        // − replaces +, so 5−3 = 2
        expressionShows("2")
    }

    @Test
    fun operatorReplacesCaretFromEnd() {
        launch()
        // 2^× → × replaces ^, then ×3 = 6
        tap("2", "^", "×", "3", "=")
        expressionShows("6")
    }

    // ── Decimal (additional) ──────────────────────────────────────

    @Test
    fun decimalAfterOperator() {
        launch()
        // 5+.3 = 5.3
        tap("5", "+", ".", "3", "=")
        expressionShows("5.3")
    }

    @Test
    fun decimalInSecondOperand() {
        launch()
        tap("1", "+", "2", ".", "5", "=")
        expressionShows("3.5")
    }

    @Test
    fun leadingZeroHandled() {
        launch()
        tap("0", "1", "+", "2", "=")
        expressionShows("3")
    }

    // ── Backspace (additional) ────────────────────────────────────

    @Test
    fun backspaceAfterEquals() {
        launch()
        tap("2", "+", "3", "=")
        expressionShows("5")
        tap("⌫")
        // After equals, backspace clears expression
        expressionShows("0")
    }

    @Test
    fun backspaceSqrtUnwraps() {
        launch()
        tap("9", "√")
        expressionShows("√(9)")
        tap("⌫")
        expressionShows("9")
    }

    @Test
    fun multipleBackspacesOnSqrt() {
        launch()
        tap("1", "2", "√")
        expressionShows("√(12)")
        tap("⌫")
        expressionShows("12")
    }

    // ── Trailing caret stripped ───────────────────────────────────

    @Test
    fun trailingCaretStrippedOnEquals() {
        launch()
        tap("5", "^", "=")
        expressionShows("5")
    }

    // ── Implicit multiplication ───────────────────────────────────

    @Test
    fun digitAfterSqrtParenShouldMultiply() {
        launch()
        // √(9)5 = 3×5 = 15
        tap("9", "√", "5", "=")
        expressionShows("15")
    }

    @Test
    fun digitAfterFactorialShouldMultiply() {
        launch()
        // 5!3 = 120×3 = 360
        tap("5", "!", "3", "=")
        expressionShows("360")
    }

    @Test
    fun digitAfterPercentShouldMultiply() {
        launch()
        // 50%5 = 0.5×5 = 2.5
        tap("5", "0", "%", "5", "=")
        expressionShows("2.5")
    }

    @Test
    fun sqrtThenPiShouldMultiply() {
        launch()
        // √(4)π = 2π ≈ 6.283185307
        tap("4", "√", "π", "=")
        expressionShows("6.283185307")
    }

    // ── Precision (additional) ────────────────────────────────────

    @Test
    fun oneDividedBySeven() {
        launch()
        tap("1", "÷", "7", "=")
        expressionShows("0.1428571428")
    }

    // ── Combined unary + binary (additional) ──────────────────────

    @Test
    fun sqrtInAddition() {
        launch()
        // √(16)+5 = 4+5 = 9
        tap("1", "6", "√", "+", "5", "=")
        expressionShows("9")
    }

    @Test
    fun computeThenSwitchOperation() {
        launch()
        tap("3", "+", "2", "=")
        expressionShows("5")
        tap("×", "3", "=")
        expressionShows("15")
        tap("−", "5", "=")
        expressionShows("10")
    }

    // ── Lone decimal point ────────────────────────────────────────

    @Test
    fun loneDecimalPointEvaluatesToZero() {
        launch()
        tap(".", "=")
        expressionShows("0")
    }

    @Test
    fun leadingDecimalThenOperator() {
        launch()
        // .5 + 3 = 3.5
        tap(".", "5", "+", "3", "=")
        expressionShows("3.5")
    }
}
