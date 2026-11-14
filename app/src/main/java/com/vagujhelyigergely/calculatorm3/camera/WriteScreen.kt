package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vagujhelyigergely.calculatorm3.R

/** Test tag of the drawing surface, for UI tests that draw on it. */
const val WRITE_PAD_TAG = "write-pad"

/** An eraser (a tilted block on a baseline): the icon set has none. */
private val EraserIcon: ImageVector by lazy {
    materialIcon(name = "Filled.InkEraserCustom") {
        materialPath {
            // The block, tilted 45°.
            moveTo(15.1f, 3.6f)
            lineTo(20.4f, 8.9f)
            lineTo(11.3f, 18.0f)
            lineTo(6.0f, 12.7f)
            close()
            // Its rubber tip, split off by a gap.
            moveTo(5.3f, 13.4f)
            lineTo(10.6f, 18.7f)
            lineTo(9.3f, 20.0f)
            lineTo(5.6f, 20.0f)
            lineTo(3.0f, 17.4f)
            lineTo(3.0f, 15.7f)
            close()
            // The baseline it rubs on.
            moveTo(12.0f, 20.0f)
            lineTo(21.0f, 20.0f)
            lineTo(21.0f, 21.5f)
            lineTo(12.0f, 21.5f)
            close()
        }
    }
}

/**
 * The handwriting pad: write the problem with a finger or stylus, then Solve with AI. Once a
 * stylus touches the pad only the stylus draws ([stylusOnly]), so a resting palm leaves no
 * marks; a stylus's eraser end always erases. The eraser removes whole strokes. Ink is drawn in
 * the theme's text colour here; the picture sent to the model is always black on white.
 */
@Composable
internal fun WriteContent(
    strokes: List<InkStroke>,
    canRedo: Boolean,
    stylusOnly: Boolean,
    onStroke: (InkStroke) -> Unit,
    onErase: (InkPoint, radius: Float) -> Unit,
    onStylusSeen: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onSolve: () -> Unit,
    onDismiss: () -> Unit,
) {
    var erasing by rememberSaveable { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onSurface

    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 72.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.write_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            InkPad(
                strokes = strokes,
                erasing = erasing,
                stylusOnly = stylusOnly,
                ink = ink,
                onStroke = onStroke,
                onErase = onErase,
                onStylusSeen = onStylusSeen,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndo, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.write_undo))
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.write_redo))
                }
                // Plain when off, filled only when on, so it's clear whether the next stroke erases.
                IconToggleButton(
                    checked = erasing,
                    onCheckedChange = { erasing = it },
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(EraserIcon, contentDescription = stringResource(R.string.write_eraser))
                }
                IconButton(
                    onClick = {
                        onClear()
                        erasing = false
                    },
                    enabled = strokes.isNotEmpty()
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.write_clear))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSolve, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.Filled.Assistant, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_menu_title))
                }
            }
        }
    }
}

/**
 * The drawing surface. Points are stored in pad units (fractions of the pad's width), so the
 * drawing keeps its shape when the pad changes size. The stroke being drawn is shown live and
 * handed to [onStroke] when the pen lifts.
 */
@Composable
private fun InkPad(
    strokes: List<InkStroke>,
    erasing: Boolean,
    stylusOnly: Boolean,
    ink: Color,
    onStroke: (InkStroke) -> Unit,
    onErase: (InkPoint, radius: Float) -> Unit,
    onStylusSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var padWidth by remember { mutableIntStateOf(1) }
    val current = remember { mutableStateListOf<InkPoint>() }
    // The gesture loop outlives recompositions; read the latest values through these.
    val latestErasing by rememberUpdatedState(erasing)
    val latestStylusOnly by rememberUpdatedState(stylusOnly)
    val latestOnStroke by rememberUpdatedState(onStroke)
    val latestOnErase by rememberUpdatedState(onErase)
    val latestOnStylusSeen by rememberUpdatedState(onStylusSeen)
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .onSizeChanged { padWidth = it.width.coerceAtLeast(1) }
            .testTag(WRITE_PAD_TAG)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isPen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if (isPen) latestOnStylusSeen()
                    // After a stylus has been used, a finger (often a palm) doesn't draw.
                    if (!isPen && latestStylusOnly) return@awaitEachGesture
                    val erase = latestErasing || down.type == PointerType.Eraser
                    val w = padWidth.toFloat()
                    fun toInk(o: Offset) = InkPoint(o.x / w, o.y / w)
                    // An eraser about a fingertip wide.
                    val eraseRadius = 12.dp.toPx() / w
                    down.consume()
                    if (erase) latestOnErase(toInk(down.position), eraseRadius)
                    else { current.clear(); current += toInk(down.position) }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // The lift's own position isn't a point of the line (it can be off
                        // the last one); the samples before it are.
                        val positions = change.historical.map { it.position } +
                            listOfNotNull(change.position.takeIf { change.pressed })
                        positions.forEach { p ->
                            if (erase) latestOnErase(toInk(p), eraseRadius) else current += toInk(p)
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                    if (!erase && current.isNotEmpty()) latestOnStroke(InkStroke(current.toList()))
                    current.clear()
                }
            }
    ) {
        if (strokes.isEmpty() && current.isEmpty()) {
            Text(
                text = stringResource(R.string.write_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = size.width
            strokes.forEach { drawInk(it.points, scale, ink) }
            drawInk(current, scale, ink)
        }
    }
}

private fun DrawScope.drawInk(points: List<InkPoint>, scale: Float, color: Color) {
    if (points.isEmpty()) return
    val width = InkGeometry.STROKE_WIDTH * scale
    if (points.size == 1) {
        drawCircle(color, radius = width / 2, center = Offset(points[0].x * scale, points[0].y * scale))
        return
    }
    val path = Path()
    points.forEachIndexed { i, p ->
        if (i == 0) path.moveTo(p.x * scale, p.y * scale) else path.lineTo(p.x * scale, p.y * scale)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
