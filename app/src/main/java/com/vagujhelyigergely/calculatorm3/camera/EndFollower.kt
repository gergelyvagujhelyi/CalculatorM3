package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * Whether a scrolling page should keep following its end as text streams in (the model's
 * working, a follow-up reply). Starts true. A drag by the user turns it off, so the page stops
 * pulling itself down under their finger; coming to rest at the end turns it on again.
 */
@Stable
internal class EndFollower(private val scroll: ScrollState) {
    var following by mutableStateOf(true)

    /** Jump to the end of the page if following. Instant, so updates arriving fast don't stutter. */
    suspend fun followEnd() {
        if (following) scroll.scrollTo(scroll.maxValue)
    }

    internal val atEnd: Boolean get() = scroll.value >= scroll.maxValue - END_SLOP_PX
}

/** The [EndFollower] of the page around a section that streams (e.g. [FollowUpSection]). */
internal val LocalEndFollower = compositionLocalOf<EndFollower?> { null }

/** An [EndFollower] for a page scrolled by [scroll]. */
@Composable
internal fun rememberEndFollower(scroll: ScrollState): EndFollower {
    val follower = remember(scroll) { EndFollower(scroll) }
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> follower.following = false
                // A drag that ends at the end (or on a page too short to scroll) follows again.
                is DragInteraction.Stop, is DragInteraction.Cancel -> if (follower.atEnd) follower.following = true
            }
        }
    }
    LaunchedEffect(scroll) {
        // A fling that comes to rest at the end follows again too.
        snapshotFlow { !scroll.isScrollInProgress && follower.atEnd }
            .collect { atRestAtEnd -> if (atRestAtEnd) follower.following = true }
    }
    return follower
}

/** Close enough to the end to count as there, in pixels. */
private const val END_SLOP_PX = 8
