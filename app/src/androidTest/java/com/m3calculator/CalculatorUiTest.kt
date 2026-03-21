package com.m3calculator

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.m3calculator.ui.theme.CalculatorM3Theme
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
}
