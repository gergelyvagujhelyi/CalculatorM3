package com.vagujhelyigergely.calculatorm3

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.vagujhelyigergely.calculatorm3.camera.EndFollower
import com.vagujhelyigergely.calculatorm3.camera.rememberEndFollower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A page whose text keeps growing (like the model's streamed working) follows its end, but not
 * while the user has scrolled up to read; back at the end, it follows again.
 */
class EndFollowerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var lines by mutableIntStateOf(60)
    private lateinit var scroll: ScrollState
    private lateinit var follower: EndFollower

    private fun launch() {
        composeTestRule.setContent {
            scroll = rememberScrollState()
            follower = rememberEndFollower(scroll)
            LaunchedEffect(scroll) {
                snapshotFlow { scroll.maxValue }.collect { if (follower.following) scroll.scrollTo(it) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("page")) {
                repeat(lines) { Text("Line $it") }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun grow() {
        lines += 20
        composeTestRule.waitForIdle()
    }

    @Test
    fun followsTheEndAsTextArrives() {
        launch()
        grow()
        assertEquals(scroll.maxValue, scroll.value)
    }

    @Test
    fun stopsFollowingWhileTheUserReadsFurtherUp() {
        launch()
        composeTestRule.onNodeWithTag("page").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        val readingAt = scroll.value
        assertFalse(follower.following)
        assertTrue(readingAt < scroll.maxValue)
        grow()
        assertEquals("the page moved while the user was reading", readingAt, scroll.value)
    }

    @Test
    fun followsAgainBackAtTheEnd() {
        launch()
        composeTestRule.onNodeWithTag("page").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        repeat(4) {
            composeTestRule.onNodeWithTag("page").performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }
        assertTrue(follower.following)
        grow()
        assertEquals(scroll.maxValue, scroll.value)
    }
}
