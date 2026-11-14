package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vagujhelyigergely.calculatorm3.R
import com.vagujhelyigergely.calculatorm3.ai.SolvedSteps

/**
 * The calculator's own answer to typed arithmetic: [value] in full (what Use puts into the
 * calculator) and [display], fitted to the screen.
 */
data class InstantAnswer(val value: String, val display: String)

/** Math symbols the phone keyboard lacks or hides, inserted at the cursor. */
private val TYPE_SYMBOLS = listOf("+", "−", "×", "÷", "^", "√", "π", "(", ")", "=", "x", "%")

/**
 * The Type screen: a typed problem, a row of math symbols, and Solve with AI. When the text is
 * plain arithmetic the calculator itself can answer ([instantAnswer], already formatted), that
 * answer is shown straight away with Use; Solve still asks the AI for the steps.
 */
@Composable
internal fun TypeContent(
    text: String,
    onTextChange: (String) -> Unit,
    instantAnswer: String?,
    onUseInstant: () -> Unit,
    onSolve: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The field keeps its cursor locally; the text itself lives in the ViewModel (Retry/Edit).
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    LaunchedEffect(text) {
        if (text != field.text) field = TextFieldValue(text, TextRange(text.length))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.type_title),
                style = MaterialTheme.typography.headlineSmall
            )
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    onTextChange(it.text)
                },
                placeholder = { Text(stringResource(R.string.type_placeholder)) },
                textStyle = MaterialTheme.typography.titleLarge,
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TYPE_SYMBOLS.forEach { symbol ->
                    FilledTonalButton(
                        onClick = {
                            val sel = field.selection
                            val newText = field.text.replaceRange(sel.min, sel.max, symbol)
                            field = TextFieldValue(newText, TextRange(sel.min + symbol.length))
                            onTextChange(newText)
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(symbol, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (instantAnswer != null) {
                AiCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.type_instant_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "= $instantAnswer",
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        Button(onClick = onUseInstant) {
                            Text(stringResource(R.string.use_expression))
                        }
                    }
                }
            }
            Button(
                onClick = onSolve,
                enabled = field.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Assistant, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (instantAnswer != null) R.string.type_show_steps else R.string.ai_menu_title
                    )
                )
            }
        }
    }
}

/**
 * A step-by-step answer (typed or drawn problem): the problem as the model read it, the answer,
 * and the numbered steps. Use appears only for an answer that's a single number agreeing with the
 * working ([SolvedSteps.usableAnswer]); otherwise a note says why it can't go into the calculator.
 * [onEdit] is the secondary button: Edit for a typed problem, Draw again for a drawing
 * ([drawn]). A drawing also gets ✎ next to "Read as" ([onFixAsText]) to correct a misread as text.
 */
@Composable
internal fun StepsSuccessContent(
    steps: SolvedSteps,
    elapsedText: String,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    /** Where the problem came from: a picture (photo, drawing) shows "Read as" with Fix as text. */
    source: AiEntry = AiEntry.TYPE,
    onFixAsText: () -> Unit = {},
    /** Shown under the answer: questions about it ([FollowUpSection]). */
    extra: @Composable () -> Unit = {},
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fromPicture = source != AiEntry.TYPE
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionLabel(stringResource(if (fromPicture) R.string.steps_read_as else R.string.steps_problem))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarkdownLatexText(text = steps.problem, color = onSurface, modifier = Modifier.weight(1f))
                if (fromPicture) {
                    IconButton(onClick = onFixAsText) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.steps_fix_as_text))
                    }
                }
            }

            AiCard(modifier = Modifier.fillMaxWidth().aiSharedBounds("answer-card")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.answer_found),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    MarkdownLatexText(
                        text = steps.answer,
                        color = onSurface,
                        textSizeSp = 26f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (steps.steps.isNotBlank()) {
                SectionLabel(stringResource(R.string.steps_title))
                MarkdownLatexText(text = steps.steps, color = onSurface, modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = elapsedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (steps.usableAnswer.isBlank()) {
                AiHint(text = stringResource(R.string.steps_not_usable))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onEdit) {
                    Icon(
                        when (source) {
                            AiEntry.WRITE -> Icons.Filled.Draw
                            AiEntry.CAMERA, AiEntry.GALLERY -> Icons.Filled.Refresh
                            else -> Icons.Filled.Edit
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(when (source) {
                        AiEntry.WRITE -> R.string.write_again
                        AiEntry.CAMERA, AiEntry.GALLERY -> R.string.retry
                        else -> R.string.steps_edit
                    }))
                }
                if (steps.usableAnswer.isNotBlank()) {
                    Button(onClick = onUse) {
                        Text(stringResource(R.string.use_answer, steps.usableAnswer))
                    }
                }
            }
            extra()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
