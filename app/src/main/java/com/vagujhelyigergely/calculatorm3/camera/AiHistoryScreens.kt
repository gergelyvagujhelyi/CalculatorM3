package com.vagujhelyigergely.calculatorm3.camera

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vagujhelyigergely.calculatorm3.R
import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

// ── Shared bits ─────────────────────────────────────────────────────────────

/** A one-line, LaTeX-free version of model text for list rows ("$7+5$" → "7+5"). */
private fun plain(text: String): String =
    text.replace(Regex("\\\\boxed\\{([^{}]*)\\}"), "$1")
        .replace(Regex("\\\\[()\\[\\]]|\\$|\\*\\*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

/** What the entry was about, for its row and detail title. */
@Composable
private fun entryTitle(entry: AiHistoryEntry): String =
    entry.steps?.problem?.let(::plain)?.takeIf { it.isNotBlank() }
        ?: entry.typedText?.trim()
        ?: stringResource(if (entry.mode == AiEntry.WRITE) R.string.ai_history_drawing else R.string.ai_history_photo)

/** The answer, or how it ended. */
@Composable
private fun entryResult(entry: AiHistoryEntry): String = when (entry.outcome) {
    AiHistoryOutcome.SOLVED -> plain(entry.steps?.answer ?: entry.answer).ifBlank { entry.answer }
    AiHistoryOutcome.CHECK -> stringResource(R.string.ai_history_outcome_check)
    AiHistoryOutcome.FAILED -> stringResource(R.string.ai_history_outcome_failed)
}

@Composable
private fun formatWhen(timestamp: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(
        context, timestamp,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    )
}

/** The saved picture, decoded off the main thread and scaled down to about [maxPx]. */
@Composable
private fun rememberPicture(file: File?, maxPx: Int): ImageBitmap? {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }
    }
    return bitmap
}

/** A drawing scaled to fit its box, centred. */
@Composable
internal fun InkPreview(strokes: List<InkStroke>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        val bounds = InkGeometry.bounds(strokes) ?: return@Canvas
        val scale = min(size.width / bounds.width, size.height / bounds.height) * 0.85f
        val dx = (size.width - bounds.width * scale) / 2 - bounds.left * scale
        val dy = (size.height - bounds.height * scale) / 2 - bounds.top * scale
        val width = (InkGeometry.STROKE_WIDTH * scale).coerceIn(1.5f, 10f)
        strokes.forEach { stroke ->
            val pts = stroke.points
            if (pts.size == 1) {
                drawCircle(color, width / 2, Offset(pts[0].x * scale + dx, pts[0].y * scale + dy))
            } else {
                val path = Path()
                pts.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x * scale + dx, p.y * scale + dy) else path.lineTo(p.x * scale + dx, p.y * scale + dy)
                }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The entry's input, small: its picture, its drawing, or a keyboard for typed text. */
@Composable
private fun Thumbnail(entry: AiHistoryEntry, image: File?) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        val picture = rememberPicture(image, maxPx = 160)
        when {
            picture != null -> Image(picture, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            entry.strokes != null -> InkPreview(entry.strokes, Modifier.fillMaxSize().padding(4.dp))
            else -> Icon(Icons.Filled.Keyboard, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── List ────────────────────────────────────────────────────────────────────

/** Saved AI solves, newest first. Tap to open; swipe a row away to delete it. */
@Composable
internal fun AiHistoryList(
    entries: List<AiHistoryEntry>,
    imageOf: (AiHistoryEntry) -> File?,
    onOpen: (AiHistoryEntry) -> Unit,
    onDelete: (AiHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.ai_history_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(entries, key = { it.id }) { entry ->
            val dismiss = rememberSwipeToDismissBoxState()
            LaunchedEffect(dismiss.currentValue) {
                if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) onDelete(entry)
            }
            SwipeToDismissBox(
                state = dismiss,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                },
                enableDismissFromStartToEnd = false,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                AiHistoryRow(entry, imageOf(entry), onClick = { onOpen(entry) })
            }
        }
    }
}

@Composable
private fun AiHistoryRow(entry: AiHistoryEntry, image: File?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("ai-history-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Thumbnail(entry, image)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entryTitle(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entryResult(entry),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (entry.outcome == AiHistoryOutcome.SOLVED) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.model} · ${formatWhen(entry.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        }
    }
}

// ── Detail ──────────────────────────────────────────────────────────────────

/**
 * One saved solve in full: its input, what the model read, the answer and steps, the model's
 * complete output (open by default), and how it was solved. Use puts the number into the
 * calculator; Solve again runs the same input with the model and speed set now.
 */
@Composable
internal fun AiHistoryDetail(
    entry: AiHistoryEntry,
    image: File?,
    onBack: () -> Unit,
    onUse: (String) -> Unit,
    onSolveAgain: () -> Unit,
    onDelete: () -> Unit,
    /** Open the answer to ask about it; offered while it still takes questions. */
    onAskAbout: () -> Unit = {},
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var outputOpen by rememberSaveable(entry.id) { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ai_history_back))
            }
            Text(formatWhen(entry.timestamp), style = MaterialTheme.typography.titleMedium)
        }

        // The input as it was given.
        val picture = rememberPicture(image, maxPx = 900)
        when {
            picture != null -> Image(
                picture, contentDescription = stringResource(R.string.ai_history_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
            )
            entry.strokes != null -> InkPreview(
                entry.strokes,
                Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            )
            entry.typedText != null -> AiCard(Modifier.fillMaxWidth()) {
                Text(entry.typedText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
        }

        entry.steps?.let { steps ->
            if (entry.mode == AiEntry.WRITE || entry.mode == AiEntry.CAMERA || entry.mode == AiEntry.GALLERY) {
                Label(stringResource(R.string.steps_read_as))
                MarkdownLatexText(steps.problem, color = onSurface, modifier = Modifier.fillMaxWidth())
            }
        }

        AiCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.outcome == AiHistoryOutcome.SOLVED) {
                    Text(stringResource(R.string.answer_found), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    MarkdownLatexText(entry.steps?.answer ?: entry.answer, color = onSurface, textSizeSp = 24f,
                        modifier = Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(entryResult(entry), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                    entry.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        entry.steps?.steps?.takeIf { it.isNotBlank() }?.let {
            Label(stringResource(R.string.steps_title))
            MarkdownLatexText(it, color = onSurface, modifier = Modifier.fillMaxWidth())
        }

        // Questions asked about it, and how many are left.
        if (entry.followUps.isNotEmpty()) {
            Label(stringResource(R.string.ai_history_questions))
            entry.followUps.forEach { (question, reply) ->
                Text(question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                MarkdownLatexText(reply, color = onSurface, modifier = Modifier.fillMaxWidth())
            }
        }
        val questionsLeft = MAX_FOLLOW_UPS - entry.followUps.size
        if (entry.outcome != AiHistoryOutcome.FAILED && questionsLeft > 0) {
            OutlinedButton(onClick = onAskAbout) {
                Text(stringResource(R.string.ai_history_ask))
            }
            Text(
                stringResource(R.string.follow_up_left, questionsLeft),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The model's complete output, word for word.
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { outputOpen = !outputOpen },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label(stringResource(R.string.ai_history_full_output), Modifier.weight(1f))
            Icon(if (outputOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (outputOpen) {
            AiCard(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        entry.output,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Text(
            text = listOfNotNull(
                entry.model,
                entry.speed?.let { stringResource(it.label) },
                stringResource(R.string.recognized_in, formatElapsed(entry.elapsedMs)),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.ai_history_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSolveAgain) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ai_history_solve_again))
            }
            if (entry.answer.isNotBlank()) {
                Button(onClick = { onUse(entry.answer) }) {
                    Text(stringResource(R.string.use_answer, entry.answer))
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** The Fast / Balanced / Accurate label. */
private val ScanQuality.label: Int
    get() = when (this) {
        ScanQuality.FAST -> R.string.scan_quality_fast
        ScanQuality.BALANCED -> R.string.scan_quality_balanced
        ScanQuality.ACCURATE -> R.string.scan_quality_accurate
    }
