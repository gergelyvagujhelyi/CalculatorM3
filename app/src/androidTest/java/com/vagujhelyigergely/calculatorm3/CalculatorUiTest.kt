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

    // ── Keypad and display formatting ─────────────────────────────────────

    @Test
    fun addition() {
        launch()
        tap("2", "+", "3", "=")
        displayShows("5")
    }

    @Test
    fun decimalArithmetic() {
        launch()
        tap("0", ".", "1", "+", "0", ".", "2", "=")
        displayShows("0.3")
    }

    @Test
    fun chainedOperations() {
        launch()
        tap("2", "+", "3", "×", "4", "=")
        displayShows("14")
    }

    @Test
    fun power() {
        launch()
        tap("2", "^", "1", "0", "=")
        displayShows("1,024")
    }

    @Test
    fun largeMultiplication() {
        launch()
        // 99999×99999 = 9999800001
        tap("9", "9", "9", "9", "9", "×", "9", "9", "9", "9", "9", "=")
        displayShows("9,999,800,001")
    }

    @Test
    fun piValue() {
        launch()
        tap("π", "=")
        displayStartsWith("3.1415926535")
    }

    @Test
    fun oneDividedBySeven() {
        launch()
        tap("1", "÷", "7", "=")
        displayStartsWith("0.1428571428")
    }

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

    @Test
    fun backspaceSqrtUnwraps() {
        launch()
        tap("9", "√")
        displayShows("√(9)")
        tap("⌫")
        displayShows("9")
    }

    @Test
    fun toggleSign() {
        launch()
        tap("5")
        tap("+/−")
        displayShows("-5") // leading minus is unary, no spaces
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

    // ── Error messages (localized, on the preview line) ───────────────────

    @Test
    fun divisionByZero() {
        launch()
        tap("5", "÷", "0", "=")
        errorShows(R.string.error_division_by_zero)
    }

    @Test
    fun sqrtOfNegativeReturnsInvalidOperation() {
        launch()
        tap("4", "+/−", "√", "=")
        errorShows(R.string.error_invalid_operation)
    }

    @Test
    fun overflowOnLargePower() {
        launch()
        // 9^9999 overflows Double → Error: overflow
        tap("9", "^", "9", "9", "9", "9", "=")
        errorShows(R.string.error_overflow)
    }

    @Test
    fun newExpressionAfterDivisionByZero() {
        launch()
        tap("5", "÷", "0", "=")
        errorShows(R.string.error_division_by_zero)
        tap("3", "+", "2", "=")
        displayShows("5")
    }

    // ── Preview, history label, assertion guard ───────────────────────────

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

    // ── Regressions: #81 (new entry after =), #41 (unary minus) ───────────

    /** Exact-text match on non-clickable (display) nodes, so keypad buttons can't satisfy it. */
    private fun displayNodes(text: String) =
        composeTestRule.onAllNodes(hasText(text) and !hasClickAction())

    @Test
    fun digitAfterEqualsStartsNewCalculation() {
        launch()
        tap("1", "2", "+", "3", "=")
        tap("4")
        displayNodes("4").onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("154", substring = true).assertCountEquals(0)
    }

    @Test
    fun operatorAfterEqualsContinuesFromAnswer() {
        launch()
        tap("1", "2", "+", "3", "=")
        tap("+", "4", "=")
        displayNodes("19").onFirst().assertIsDisplayed()
    }

    @Test
    fun signTogglePowerAppliesSignAfterExponent() {
        launch()
        tap("8", "+/−", "^", "2", "+/−", "=")
        // displayNodes, not expressionShows: #85 replaces that helper, and this test must
        // compile whichever of the two PRs merges first.
        displayNodes("-0.015625").onFirst().assertIsDisplayed()
    }
}
