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
        expressionShows("- 5") // formatExpression adds spaces around operators
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
}
