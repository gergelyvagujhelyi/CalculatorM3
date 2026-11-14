package com.vagujhelyigergely.calculatorm3.camera

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vagujhelyigergely.calculatorm3.R
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel.FollowUpUi

/**
 * Questions about an answer, under it: the questions asked so far with their replies, the one
 * being answered (streamed, with Stop), and an ask field with suggestions and a counter of the
 * questions [left] ([MAX_FOLLOW_UPS] per answer). With none left, it offers [onSolveAgain]:
 * a fresh answer with its own questions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FollowUpSection(
    thread: List<FollowUp>,
    state: FollowUpUi,
    left: Int,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onSolveAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val bottom = remember { BringIntoViewRequester() }
    // Keep the newest part (a new question, the reply as it streams, then the field and counter)
    // in view. Wait a frame first, so what just changed is laid out before scrolling to it.
    LaunchedEffect(state, thread.size) {
        withFrameNanos { }
        bottom.bringIntoView()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.follow_up_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        thread.forEach { (question, reply) ->
            QuestionBubble(question)
            MarkdownLatexText(reply, color = onSurface, modifier = Modifier.fillMaxWidth())
        }
        when (state) {
            is FollowUpUi.LoadingModel -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.model_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            is FollowUpUi.Answering -> {
                QuestionBubble(state.question)
                if (state.partial.isBlank()) {
                    Text(stringResource(R.string.follow_up_thinking), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MarkdownLatexText(state.partial, color = onSurface, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
            is FollowUpUi.Failed -> Text(state.message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            is FollowUpUi.Idle -> Unit
        }
        val busy = state is FollowUpUi.Answering || state is FollowUpUi.LoadingModel
        if (!busy) {
            if (left > 0) AskField(firstQuestion = thread.isEmpty(), left = left, onAsk = onAsk)
            else {
                Text(stringResource(R.string.follow_up_none), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onSolveAgain) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.ai_history_solve_again), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(1.dp).bringIntoViewRequester(bottom))
    }
}

/** The question field, with suggestions before the first question, and the counter. */
@Composable
private fun AskField(firstQuestion: Boolean, left: Int, onAsk: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onAsk(text)
            text = ""
        }
    }
    if (firstQuestion) {
        val suggestions = listOf(
            stringResource(R.string.follow_up_chip_simpler),
            stringResource(R.string.follow_up_chip_another),
            stringResource(R.string.follow_up_chip_why),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { s -> SuggestionChip(onClick = { onAsk(s) }, label = { Text(s) }) }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.follow_up_hint)) },
        maxLines = 3,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(onClick = send, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.follow_up_send))
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource(R.string.follow_up_left, left),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun QuestionBubble(question: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = question,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
