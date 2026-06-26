@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vagujhelyigergely.calculatorm3.R

// ---------------------------------------------------------------------------
// Shared-element plumbing
//
// The scan UI is a state machine, not a NavHost, so there's no automatic shared
// element scope. We expose the SharedTransitionScope (from the SharedTransitionLayout
// that wraps the AnimatedContent in CameraScanScreen) and the per-content
// AnimatedVisibilityScope through CompositionLocals, so the building blocks below can
// opt elements into shared-bounds morphs without every state composable having to
// thread the two scopes through its signature.
//
// When the locals are absent (e.g. an @Preview that renders a single state with no
// SharedTransitionLayout around it) the shared modifiers degrade to no-ops, so the
// same composables stay previewable.
// ---------------------------------------------------------------------------

internal val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalAiVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Morphing shared bounds keyed by [key] — used for elements whose content changes between states. */
@Composable
internal fun Modifier.aiSharedBounds(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalAiVisibilityScope.current
    return if (shared != null && visibility != null) {
        with(shared) { sharedBounds(rememberSharedContentState(key), visibility) }
    } else this
}

/** Shared element keyed by [key] — used for identical content that should stay anchored across states. */
@Composable
internal fun Modifier.aiSharedElement(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalAiVisibilityScope.current
    return if (shared != null && visibility != null) {
        with(shared) { sharedElement(rememberSharedContentState(key), visibility) }
    } else this
}

// ---------------------------------------------------------------------------
// Layout scaffolding shared by every scan state
// ---------------------------------------------------------------------------

/** The close (X) button anchored top-start on every scan screen. */
@Composable
internal fun CloseButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onDismiss, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.close),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Common full-screen container: navigation-bar inset, a shared-element close button
 * top-start, and a [content] slot (BoxScope, so callers can center their column).
 * Replaces the Box + CloseButton boilerplate every state used to repeat.
 */
@Composable
internal fun AiStateScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize().navigationBarsPadding()) {
        content()
        if (showClose) {
            CloseButton(
                onDismiss = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .zIndex(1f)
                    .aiSharedElement("close")
            )
        }
    }
}

/**
 * Centered hero header used by most states: a morphing icon-or-loader slot (shared
 * bounds keyed "hero-icon") above a shared title and an optional hint. Because the
 * slot and title keep stable keys across states, switching states animates the hero
 * smoothly instead of cutting.
 */
@Composable
internal fun AiHero(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    loading: Boolean = false,
    pulsing: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.aiSharedBounds("hero-icon"),
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> AiLoader()
                icon != null -> {
                    val pulse = if (pulsing) rememberPulseScale() else 1f
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .size(56.dp)
                            .graphicsLayer { scaleX = pulse; scaleY = pulse }
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = titleColor,
            modifier = Modifier.aiSharedBounds("hero-title")
        )
        if (subtitle != null) AiHint(subtitle)
    }
}

/** Standard centered secondary text; animates its size so phase/text swaps grow smoothly. */
@Composable
internal fun AiHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

/** ElevatedCard wrapper carrying the app's rounded-corner + tonal-surface convention. */
@Composable
internal fun AiCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = colors,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Loaders
// ---------------------------------------------------------------------------

/** The M3-Expressive morphing loading indicator, sized for the hero slot. */
@Composable
internal fun AiLoader(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier.size(52.dp))
}

/**
 * Download progress bar. [progress] in 0..1 draws a determinate wavy bar (animated so
 * it eases toward each new value); null draws the indeterminate wavy bar (used while
 * waiting for the network before any bytes arrive).
 */
@Composable
internal fun AiDownloadBar(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        LinearWavyProgressIndicator(modifier = modifier)
    } else {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(400),
            label = "downloadProgress"
        )
        LinearWavyProgressIndicator(progress = { animated }, modifier = modifier)
    }
}

// ---------------------------------------------------------------------------
// Shimmer skeletons (for first-paint loading, e.g. the model list)
// ---------------------------------------------------------------------------

/** A sweeping highlight gradient that animates across the element, clipped to [shape]. */
@Composable
internal fun Modifier.shimmer(shape: Shape = RoundedCornerShape(16.dp)): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1300, easing = LinearEasing)),
        label = "shimmerX"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .clip(shape)
        .drawBehind {
            val w = size.width
            val offset = x * 2f * w - w
            drawRect(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(offset, 0f),
                    end = Offset(offset + w, 0f)
                )
            )
        }
}

/**
 * A few shimmering placeholder lines, used as a skeleton "answer" while the vision
 * model prefills the image (the genuinely slow, token-less phase) — it then morphs
 * into the streaming text as tokens arrive.
 */
@Composable
internal fun ShimmerLines(modifier: Modifier = Modifier, lines: Int = 3) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(lines) { index ->
            val isLast = index == lines - 1
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (isLast) 0.55f else 1f)
                    .height(14.dp)
                    .shimmer(RoundedCornerShape(7.dp))
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

/** A gentle 1.0→1.12 breathing scale, for icons of in-progress states. */
@Composable
internal fun rememberPulseScale(): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    return scale
}
