package com.vagujhelyigergely.calculatorm3

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Launches the real [MainActivity] (edge-to-edge, its own ViewModel factory with SavedStateHandle
 * and storage) instead of the bare test host the other UI tests use.
 *
 * Read-only on purpose: these tests never press "=" or the AC unlock, so on a personal phone
 * they don't add history or change settings; the expression lives only in memory/saved state.
 */
class MainActivitySmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun tap(vararg labels: String) = labels.forEach {
        composeTestRule.onNode(hasText(it) and hasClickAction()).performClick()
    }

    private fun expression() = composeTestRule.onNodeWithTag(CalculatorTestTags.EXPRESSION)
    private fun preview() = composeTestRule.onNodeWithTag(CalculatorTestTags.PREVIEW)

    @Test
    fun realAppShowsTheCalculatorAndEvaluatesInThePreview() {
        expression().assertIsDisplayed()
        tap("1", "2", "+", "3")
        expression().assertTextEquals("12 + 3")
        preview().assertTextEquals("= 15")
    }

    @Test
    fun expressionAndPreviewSurviveActivityRecreation() {
        tap("1", "2", "+", "3")
        expression().assertTextEquals("12 + 3")

        // What a rotation does: the Activity is destroyed and recreated; the ViewModel survives.
        composeTestRule.activityRule.scenario.recreate()

        expression().assertTextEquals("12 + 3")
        preview().assertTextEquals("= 15")
    }
}
