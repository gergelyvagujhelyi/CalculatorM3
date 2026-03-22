package com.vagujhelyigergely.calculatorm3

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class CalcButton(
    val label: String,
    val type: ButtonType,
    val descriptionRes: Int? = null
)

enum class ButtonType {
    NUMBER, OPERATOR, FUNCTION, EQUALS, BACKSPACE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(viewModel: CalculatorViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenHeightDp = configuration.screenHeightDp
    // Foldables/tablets in portrait have a near-square aspect ratio (e.g. Pixel 9 Pro Fold ~1.04).
    // Regular phones are typically ≥1.6. Use wide (split) layout for near-square screens so the
    // button grid doesn't overflow vertically.
    val useWideLayout = isLandscape ||
        (screenHeightDp > 0 && screenHeightDp.toFloat() / screenWidthDp.toFloat() < 1.3f)
    viewModel.maxDisplayLength = (screenWidthDp - 48) / 15

    val buttons = listOf(
        listOf(
            CalcButton("⌫", ButtonType.BACKSPACE, R.string.btn_backspace),
            CalcButton("AC", ButtonType.FUNCTION, R.string.btn_all_clear),
            CalcButton("%", ButtonType.FUNCTION, R.string.btn_percent),
            CalcButton("÷", ButtonType.OPERATOR, R.string.btn_divide),
        ),
        listOf(
            CalcButton("7", ButtonType.NUMBER),
            CalcButton("8", ButtonType.NUMBER),
            CalcButton("9", ButtonType.NUMBER),
            CalcButton("×", ButtonType.OPERATOR, R.string.btn_multiply),
        ),
        listOf(
            CalcButton("4", ButtonType.NUMBER),
            CalcButton("5", ButtonType.NUMBER),
            CalcButton("6", ButtonType.NUMBER),
            CalcButton("−", ButtonType.OPERATOR, R.string.btn_subtract),
        ),
        listOf(
            CalcButton("1", ButtonType.NUMBER),
            CalcButton("2", ButtonType.NUMBER),
            CalcButton("3", ButtonType.NUMBER),
            CalcButton("+", ButtonType.OPERATOR, R.string.btn_add),
        ),
        listOf(
            CalcButton("+/−", ButtonType.NUMBER, R.string.btn_toggle_sign),
            CalcButton("0", ButtonType.NUMBER),
            CalcButton(".", ButtonType.NUMBER, R.string.btn_decimal),
            CalcButton("=", ButtonType.EQUALS, R.string.btn_equals),
        ),
    )

    var showHistory by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showHistory) {
        HistorySheet(
            sheetState = sheetState,
            historyList = viewModel.historyList,
            onDismiss = { showHistory = false },
            onEntryClick = { entry ->
                viewModel.loadHistoryEntry(entry)
                showHistory = false
            },
            onClearHistory = { viewModel.clearHistory() }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surface
    ) {
        if (useWideLayout) {
            LandscapeLayout(
                viewModel = viewModel,
                buttons = buttons,
                onShowHistory = { showHistory = true }
            )
        } else {
            PortraitLayout(
                viewModel = viewModel,
                buttons = buttons,
                onShowHistory = { showHistory = true }
            )
        }
    }
}

@Composable
private fun PortraitLayout(
    viewModel: CalculatorViewModel,
    buttons: List<List<CalcButton>>,
    onShowHistory: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        // Top bar with history button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onShowHistory) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = stringResource(R.string.history),
                    tint = colorScheme.onSurfaceVariant
                )
            }
        }

        // Display area
        val configuration = LocalConfiguration.current
        val aspectRatio = configuration.screenHeightDp.toFloat() / configuration.screenWidthDp.toFloat()
        val showInlineHistory = aspectRatio < 1.6f && viewModel.historyList.isNotEmpty()

        DisplaySection(
            expression = viewModel.expression,
            displayExpression = viewModel.displayExpression,
            cursorPosition = viewModel.cursorPosition,
            onCursorChange = { viewModel.moveCursorTo(it) },
            result = viewModel.result,
            history = viewModel.history,
            inlineHistoryList = if (showInlineHistory) viewModel.historyList else emptyList(),
            onHistoryEntryClick = { entry -> viewModel.loadHistoryEntry(entry) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )

        // Divider
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp),
            thickness = 0.5.dp,
            color = colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Scientific row
        ScientificRow(viewModel = viewModel)

        // Button grid
        ButtonGrid(
            buttons = buttons,
            viewModel = viewModel,
            usePortraitAspect = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun LandscapeLayout(
    viewModel: CalculatorViewModel,
    buttons: List<List<CalcButton>>,
    onShowHistory: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        // Left side: display
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 16.dp, end = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onShowHistory) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = stringResource(R.string.history),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            DisplaySection(
                expression = viewModel.expression,
                displayExpression = viewModel.displayExpression,
                cursorPosition = viewModel.cursorPosition,
                onCursorChange = { viewModel.moveCursorTo(it) },
                result = viewModel.result,
                history = viewModel.history,
                inlineHistoryList = if (viewModel.historyList.isNotEmpty()) viewModel.historyList else emptyList(),
                onHistoryEntryClick = { entry -> viewModel.loadHistoryEntry(entry) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }

        // Right side: buttons
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            ScientificRow(viewModel = viewModel, compact = true)

            ButtonGrid(
                buttons = buttons,
                viewModel = viewModel,
                usePortraitAspect = false,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun ScientificRow(
    viewModel: CalculatorViewModel,
    compact: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = if (compact) 0.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sciButtons = listOf(
            "√" to stringResource(R.string.btn_square_root),
            "π" to stringResource(R.string.btn_pi),
            "^" to stringResource(R.string.btn_power),
            "!" to stringResource(R.string.btn_factorial),
        )
        sciButtons.forEach { (label, desc) ->
            TextButton(
                onClick = { viewModel.onButtonPress(label) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = desc },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = label,
                    fontSize = if (compact) 16.sp else 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ButtonGrid(
    buttons: List<List<CalcButton>>,
    viewModel: CalculatorViewModel,
    usePortraitAspect: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)
    ) {
        buttons.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!usePortraitAspect) Modifier.weight(1f) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)
            ) {
                row.forEach { button ->
                    CalcButtonView(
                        button = button,
                        onClick = { viewModel.onButtonPress(button.label) },
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (usePortraitAspect) Modifier.aspectRatio(1f) else Modifier.fillMaxHeight())
                    )
                }
            }
        }
    }
}

@Composable
fun DisplaySection(
    expression: String,
    displayExpression: String,
    cursorPosition: Int,
    onCursorChange: (Int) -> Unit,
    result: String,
    history: String,
    inlineHistoryList: List<HistoryEntry> = emptyList(),
    onHistoryEntryClick: (HistoryEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val formatted = formatExpression(displayExpression)
    val cursorInFormatted = mapCursorToFormatted(expression, cursorPosition)

    Column(
        modifier = modifier.clipToBounds(),
        horizontalAlignment = Alignment.End
    ) {
        // Upper area: history + expression (takes remaining space)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            // Inline history for foldable devices with extra vertical space
            if (inlineHistoryList.isNotEmpty()) {
                val scrollState = rememberScrollState()
                // Auto-scroll to bottom when history changes
                LaunchedEffect(inlineHistoryList.size) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.End
                ) {
                    inlineHistoryList.asReversed().forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHistoryEntryClick(entry) }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = formatExpression(entry.expression),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.outline,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "= ${entry.result}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // History (last calculation label, e.g. "5+3 =")
            AnimatedVisibility(
                visible = history.isNotEmpty(),
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut()
            ) {
                Text(
                    text = history,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.outline,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expression with auto-sizing font
            val displayText = formatted.ifEmpty { "0" }

            val fontSizeSteps = remember { listOf(56f, 46f, 38f, 32f, 28f) }
            var fontStepIndex by remember { mutableIntStateOf(0) }
            var readyToDraw by remember { mutableStateOf(true) }

            // When expression gets shorter, try growing back to largest font
            val prevExprLength = remember { mutableIntStateOf(0) }
            if (expression.length < prevExprLength.intValue && fontStepIndex > 0) {
                fontStepIndex = 0
                readyToDraw = false
            }
            prevExprLength.intValue = expression.length

            val currentFontSize = fontSizeSteps[fontStepIndex]
            val cursorVisible = cursorPosition < expression.length && expression.isNotEmpty()

            val textLayoutResult = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            val blinkVisible = if (cursorVisible) {
                val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 1000
                            1f at 0
                            1f at 500
                            0f at 501
                            0f at 1000
                        },
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "cursorAlpha"
                )
                alpha > 0.5f
            } else false

            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = displayText,
                    fontSize = currentFontSize.sp,
                    fontWeight = FontWeight.Light,
                    color = if (expression.isEmpty()) colorScheme.outlineVariant else colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = (currentFontSize * 1.15).sp,
                    letterSpacing = (-0.5).sp,
                    onTextLayout = { result ->
                        textLayoutResult.value = result
                        if (result.hasVisualOverflow && fontStepIndex < fontSizeSteps.lastIndex) {
                            fontStepIndex++
                            readyToDraw = false
                        } else {
                            readyToDraw = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (readyToDraw) 1f else 0f }
                        .pointerInput(displayText) {
                            detectTapGestures { offset ->
                                textLayoutResult.value?.let { layout ->
                                    val tappedOffset = layout.getOffsetForPosition(offset)
                                    val rawCursor = mapCursorFromFormatted(expression, tappedOffset)
                                    onCursorChange(rawCursor)
                                }
                            }
                        }
                )

                if (cursorVisible && blinkVisible && readyToDraw) {
                    textLayoutResult.value?.let { layout ->
                        // Coerce to the layout's actual text length to avoid crash
                        // when the layout is stale (not yet updated for the new expression)
                        val layoutLen = layout.layoutInput.text.length
                        val cursorOffset = cursorInFormatted.coerceIn(0, layoutLen)
                        val cursorRect = layout.getCursorRect(cursorOffset)
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(LocalDensity.current) { cursorRect.left.toDp() },
                                    y = with(LocalDensity.current) { cursorRect.top.toDp() }
                                )
                                .width(2.dp)
                                .height(with(LocalDensity.current) { (cursorRect.bottom - cursorRect.top).toDp() })
                                .background(colorScheme.primary)
                        )
                    }
                }
            }
        }

        // Lower area: preview (fixed height, always visible above buttons)
        val showPreview = result.isNotEmpty() && expression != result
        AnimatedVisibility(
            visible = showPreview,
            enter = fadeIn(animationSpec = tween(200)) +
                    slideInVertically(animationSpec = tween(200)) { it / 3 },
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Text(
                text = if (result.startsWith("Error")) result else "= $result",
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary.copy(alpha = 0.65f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun CalcButtonView(
    button: CalcButton,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val a11yDescription = button.descriptionRes?.let { stringResource(it) } ?: button.label

    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    val containerColor = when (button.type) {
        ButtonType.NUMBER -> colorScheme.surfaceContainerHigh
        ButtonType.OPERATOR -> colorScheme.secondaryContainer
        ButtonType.FUNCTION -> colorScheme.tertiaryContainer
        ButtonType.EQUALS -> colorScheme.primary
        ButtonType.BACKSPACE -> colorScheme.tertiaryContainer
    }

    val contentColor = when (button.type) {
        ButtonType.NUMBER -> colorScheme.onSurface
        ButtonType.OPERATOR -> colorScheme.onSecondaryContainer
        ButtonType.FUNCTION -> colorScheme.onTertiaryContainer
        ButtonType.EQUALS -> colorScheme.onPrimary
        ButtonType.BACKSPACE -> colorScheme.onTertiaryContainer
    }

    val fontSize = if (compact) {
        when (button.type) {
            ButtonType.EQUALS -> 24.sp
            ButtonType.OPERATOR -> 24.sp
            ButtonType.NUMBER -> 22.sp
            ButtonType.BACKSPACE -> 20.sp
            else -> 20.sp
        }
    } else {
        when (button.type) {
            ButtonType.EQUALS -> 36.sp
            ButtonType.OPERATOR -> 36.sp
            ButtonType.NUMBER -> 32.sp
            ButtonType.BACKSPACE -> 28.sp
            else -> 28.sp
        }
    }

    val shape = if (compact) RoundedCornerShape(16.dp) else CircleShape

    Surface(
        onClick = onClick,
        modifier = modifier
            .semantics { contentDescription = a11yDescription }
            .then(
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        interactionSource = interactionSource,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (button.label == "⌫") {
                // Backspace icon drawn with shapes
                Text(
                    text = "⌫",
                    fontSize = fontSize,
                    fontWeight = FontWeight.Normal,
                    color = contentColor,
                    letterSpacing = 0.sp
                )
            } else {
                Text(
                    text = button.label,
                    fontSize = fontSize,
                    fontWeight = when (button.type) {
                        ButtonType.NUMBER -> FontWeight.Medium
                        ButtonType.EQUALS -> FontWeight.SemiBold
                        else -> FontWeight.SemiBold
                    },
                    color = contentColor,
                    letterSpacing = if (button.label == "+/−") (-0.5).sp else 0.2.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    sheetState: SheetState,
    historyList: List<HistoryEntry>,
    onDismiss: () -> Unit,
    onEntryClick: (HistoryEntry) -> Unit,
    onClearHistory: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
                if (historyList.isNotEmpty()) {
                    IconButton(onClick = onClearHistory) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.clear_history),
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (historyList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_history_yet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(historyList) { entry ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent
                        ) {
                            Column(
                                modifier = Modifier
                                    .clickable { onEntryClick(entry) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = formatExpression(entry.expression),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.outline,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "= ${entry.result}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurface,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatExpression(expr: String): String {
    // Add spaces around binary operators (Unicode −, ×, ÷, and ASCII +).
    // ASCII '-' (from +/− toggle or history load) is always a unary sign prefix,
    // so it must NOT get spaces — otherwise cursor mapping breaks.
    // Also skip the sign in E notation (e.g. E+30).
    return expr.replace(Regex("(?<=.)(?<![Ee])[+×÷−]")) { " ${it.value} " }
}

private fun mapCursorToFormatted(raw: String, rawCursor: Int): Int {
    var formattedPos = 0
    for (i in 0 until rawCursor.coerceAtMost(raw.length)) {
        if (raw[i] in listOf('+', '−', '×', '÷')) {
            formattedPos += 3 // " X "
        } else {
            formattedPos += 1
        }
    }
    return formattedPos
}

private fun mapCursorFromFormatted(raw: String, formattedCursor: Int): Int {
    var fPos = 0
    var rPos = 0
    while (rPos < raw.length && fPos < formattedCursor) {
        if (raw[rPos] in listOf('+', '−', '×', '÷')) {
            fPos += 3
        } else {
            fPos += 1
        }
        rPos++
    }
    return rPos
}
