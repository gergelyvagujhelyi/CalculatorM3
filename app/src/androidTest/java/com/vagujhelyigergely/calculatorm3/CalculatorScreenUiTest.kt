package com.vagujhelyigergely.calculatorm3

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme
import org.junit.Rule
import org.junit.Test

/**
 * Screen-level behaviour the keypad tests in [CalculatorUiTest] don't reach: the history sheet,
 * tapping the display to place the cursor, the landscape layout, large font scales and state
 * restoration. Every test uses a [CalculatorViewModel] without storage, so running the suite on
 * a real phone never touches the app's saved history or settings.
 */
class CalculatorScreenUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(@StringRes id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun launch(wrap: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        composeTestRule.setContent {
            wrap { CalculatorM3Theme { CalculatorScreen(viewModel = CalculatorViewModel()) } }
        }
    }

    private fun tap(vararg labels: String) = labels.forEach {
        composeTestRule.onNode(hasText(it) and hasClickAction()).performClick()
    }

    private fun expression() = composeTestRule.onNodeWithTag(CalculatorTestTags.EXPRESSION)

    private fun displayShows(text: String) {
        expression().assertTextEquals(text)
    }

    private fun openHistory() {
        composeTestRule.onNodeWithContentDescription(str(R.string.history)).performClick()
    }

    // ── History sheet ───────────────────────────────────────────────────────

    @Test
    fun historySheetShowsEmptyState() {
        launch()
        openHistory()
        composeTestRule.onNodeWithText(str(R.string.no_history_yet)).assertIsDisplayed()
    }

    @Test
    fun historySheetListsACalculationAndRecallsIt() {
        launch()
        tap("1", "2", "+", "3", "=")
        tap("AC")
        displayShows("0")
        openHistory()
        composeTestRule.onNodeWithText("12 + 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("= 15").assertIsDisplayed()

        composeTestRule.onNodeWithText("12 + 3").performClick()

        // The sheet closes and the recalled answer is ready to edit.
        composeTestRule.onNodeWithContentDescription(str(R.string.clear_history)).assertDoesNotExist()
        displayShows("15")
        composeTestRule.onNodeWithTag(CalculatorTestTags.HISTORY_LABEL).assertTextEquals("12 + 3 =")
    }

    @Test
    fun clearHistoryEmptiesTheSheet() {
        launch()
        tap("7", "×", "6", "=")
        openHistory()
        composeTestRule.onNodeWithText("7 × 6").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.clear_history)).performClick()
        composeTestRule.onNodeWithText(str(R.string.no_history_yet)).assertIsDisplayed()
        composeTestRule.onNodeWithText("7 × 6").assertDoesNotExist()
    }

    // ── Tapping the display to place the cursor ─────────────────────────────

    /** The laid-out text of the expression line, for tapping on a specific character. */
    private fun expressionLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        expression().fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    @Test
    fun tapBeforeTheNumberMovesTheCursorToTheStart() {
        launch()
        tap("1", "2", "3", "4")
        displayShows("1,234")
        // The line is right-aligned, so its left edge is before the first digit.
        expression().performTouchInput { click(centerLeft) }
        tap("5")
        displayShows("51,234")
    }

    @Test
    fun tapJustAfterAThousandsSeparatorInsertsAtTheRawPosition() {
        launch()
        tap("1", "2", "3", "4")
        displayShows("1,234")
        // Displayed "1,234": tap the left part of '2' (formatted offset 2, after "1,"), which
        // maps to raw offset 1 in "1234". Typing must land between the 1 and the 2.
        val box = expressionLayout().getBoundingBox(2)
        expression().performTouchInput {
            click(Offset(box.left + box.width * 0.25f, box.center.y))
        }
        tap("9")
        displayShows("19,234")
    }

    // ── Landscape layout ────────────────────────────────────────────────────

    /** Renders in an 800×380dp landscape window: the wide layout with inline history. */
    private val landscape: @Composable (@Composable () -> Unit) -> Unit = { content ->
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 380.dp))) {
            val base = LocalConfiguration.current
            val landscapeConfig = Configuration(base).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfig) { content() }
        }
    }

    @Test
    fun landscapeLayoutCalculatesAndRecallsFromInlineHistory() {
        launch(wrap = landscape)
        tap("2", "^", "1", "0", "=")
        displayShows("1,024")
        tap("AC")
        displayShows("0")
        // The wide layout lists history next to the keypad; tapping an entry recalls it.
        composeTestRule.onNodeWithText("2^10").assertIsDisplayed().performClick()
        displayShows("1,024")
        composeTestRule.onNodeWithTag(CalculatorTestTags.HISTORY_LABEL).assertTextEquals("2^10 =")
    }

    // ── Accessibility: largest font scale ───────────────────────────────────

    @Test
    fun keypadStaysOnScreenAtTheLargestFontScale() {
        launch(wrap = { content ->
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) { content() }
        })
        (listOf("AC", "⌫", "%", "÷", "×", "−", "+", "=", ".", "+/−") + (0..9).map { "$it" })
            .forEach { composeTestRule.onNode(hasText(it) and hasClickAction()).assertIsDisplayed() }
        tap("2", "+", "3", "=")
        displayShows("5")
    }

    // ── State restoration (rotation / recreation) ───────────────────────────

    @Test
    fun openHistorySheetSurvivesRecreation() {
        val restorationTester = StateRestorationTester(composeTestRule)
        // The ViewModel outlives the recreation, as an Activity-scoped ViewModel does.
        val viewModel = CalculatorViewModel()
        restorationTester.setContent { CalculatorM3Theme { CalculatorScreen(viewModel = viewModel) } }
        tap("7", "×", "6", "=")
        openHistory()
        composeTestRule.onNodeWithText("7 × 6").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("7 × 6").assertIsDisplayed()
    }

    @Test
    fun expressionSurvivesRecreation() {
        val restorationTester = StateRestorationTester(composeTestRule)
        val viewModel = CalculatorViewModel()
        restorationTester.setContent { CalculatorM3Theme { CalculatorScreen(viewModel = viewModel) } }
        tap("1", "2", "+", "3")
        restorationTester.emulateSavedInstanceStateRestore()
        displayShows("12 + 3")
        composeTestRule.onNodeWithTag(CalculatorTestTags.PREVIEW).assertTextEquals("= 15")
    }
}
