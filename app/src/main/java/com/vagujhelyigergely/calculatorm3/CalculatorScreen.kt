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
import com.vagujhelyigergely.calculatorm3.camera.AiEntry
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryDetail
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryEntry
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryList
import com.vagujhelyigergely.calculatorm3.camera.AiMenuButton
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val android.content.Context.dataStore by preferencesDataStore(name = "settings")
/** The user turned off "Show AI button" (AI settings); AI is shown by default. */
private val AI_BUTTON_HIDDEN = booleanPreferencesKey("ai_button_hidden")

/** Show or hide the AI button (AI settings, and 7 quick AC taps). Best effort: a failed write keeps it as it was. */
internal suspend fun android.content.Context.setAiButtonHidden(hidden: Boolean) {
    try {
        dataStore.edit { it[AI_BUTTON_HIDDEN] = hidden }
    } catch (e: java.io.IOException) {
    }
}

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
fun CalculatorScreen(
    viewModel: CalculatorViewModel,
    scanViewModel: com.vagujhelyigergely.calculatorm3.camera.ScanViewModel? = null
) {
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

    // Saveable so an open history sheet stays open when the screen rotates (the Activity is
    // recreated); plain remember reset it and closed the sheet.
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // The open AI window and what it was opened for from the AI menu (null = closed).
    // Saveable so the window survives the activity being recreated while the
    // system camera is foreground (memory pressure) — otherwise it would close and
    // drop the user back on the calculator mid-scan.
    var aiEntry by rememberSaveable { mutableStateOf<AiEntry?>(null) }
    // An AI history entry to solve again when the AI window opens (Solve again), by id.
    var aiRerunId by rememberSaveable { mutableStateOf<String?>(null) }
    // A saved AI answer to open in the AI window, to ask about it (Ask about this), by id.
    var aiAnswerId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val context = LocalContext.current
    // Strings via LocalResources (not LocalContext) so a configuration change such as a
    // language switch is picked up; the Toasts below still need the Context itself.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // The menu choice waiting behind the one-time "experimental feature" dialog (null = no dialog).

    // The AI button shows on devices that can run a model, unless the user turned it off in AI
    // settings. A device short of RAM never shows it (it couldn't run any model).
    val canRunAi = remember(scanViewModel) { scanViewModel?.canRunAnyModel == true }
    val aiButtonHidden by remember(context) {
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[AI_BUTTON_HIDDEN] ?: false }
    }.collectAsState(initial = false)
    val aiVisible = scanViewModel != null && canRunAi && !aiButtonHidden

    // Hidden AI button: hammering "AC" brings it back, like Android's "tap Build number
    // 7 times" — countdown toasts and all. The streak only survives genuinely rapid taps: pause
    // longer than the window below and it resets, so everyday "AC" presses (always spaced out by
    // other input between them) can never drift toward bringing it back by accident.
    val tapsToUnlockAi = 7
    val rapidPressWindowMs = 800L
    var allClearPressCount by remember { mutableIntStateOf(0) }
    var lastAllClearPressTime by remember { mutableLongStateOf(0L) }
    val unlockToast = remember { arrayOfNulls<android.widget.Toast>(1) }

    val onAllClearPressed: () -> Unit = {
        if (aiButtonHidden && canRunAi) {
            val now = System.currentTimeMillis()
            if (now - lastAllClearPressTime > rapidPressWindowMs) allClearPressCount = 0
            lastAllClearPressTime = now
            allClearPressCount++
            val remaining = tapsToUnlockAi - allClearPressCount
            when {
                remaining <= 0 -> {
                    allClearPressCount = 0
                    scope.launch { context.setAiButtonHidden(false) }
                    unlockToast[0]?.cancel()
                    unlockToast[0] = android.widget.Toast.makeText(
                        context,
                        resources.getString(R.string.ai_unlocked),
                        android.widget.Toast.LENGTH_LONG
                    ).also { it.show() }
                }
                // Like AOSP, only start the countdown once you're a few taps in.
                remaining < tapsToUnlockAi - 2 -> {
                    unlockToast[0]?.cancel()
                    unlockToast[0] = android.widget.Toast.makeText(
                        context,
                        resources.getQuantityString(
                            R.plurals.ai_unlock_countdown, remaining, remaining
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).also { it.show() }
                }
            }
        }
    }

    val handleShowAi: (AiEntry) -> Unit = remember(scanViewModel, context, resources, scope) {
        { entry ->
            when {
                // The button isn't shown when scanViewModel is null, but guard anyway.
                scanViewModel == null -> Unit
                !scanViewModel.canRunAnyModel -> {
                    android.widget.Toast.makeText(
                        context,
                        resources.getString(R.string.ai_feature_low_ram),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                // The first open explains the download on its own first-run screen.
                else -> aiEntry = entry
            }
        }
    }

    if (showHistory) {
        HistorySheet(
            sheetState = sheetState,
            historyList = viewModel.historyList,
            formatHistoryResult = viewModel::formatForDisplay,
            onDismiss = { showHistory = false },
            onEntryClick = { entry ->
                viewModel.loadHistoryEntry(entry)
                showHistory = false
            },
            onClearHistory = { viewModel.clearHistory() },
            // Kept reachable with the button hidden, as long as there's something in it.
            aiHistory = if (scanViewModel != null && (aiVisible || scanViewModel.aiHistory.isNotEmpty()))
                scanViewModel.aiHistory else null,
            aiImageOf = { scanViewModel?.historyImage(it) },
            onUseAiAnswer = { answer ->
                viewModel.setExpressionFromScan(answer)
                showHistory = false
            },
            onSolveAgain = { entry ->
                showHistory = false
                aiRerunId = entry.id
                handleShowAi(entry.mode)
            },
            onDeleteAiEntry = { scanViewModel?.deleteHistoryEntry(it.id) },
            onClearAiHistory = { scanViewModel?.clearAiHistory() },
            onAskAboutAi = { entry ->
                showHistory = false
                aiAnswerId = entry.id
                handleShowAi(entry.mode)
            }
        )
    }

    val openAiEntry = aiEntry
    if (openAiEntry != null && scanViewModel != null) {
        com.vagujhelyigergely.calculatorm3.camera.CameraScanScreen(
            viewModel = scanViewModel,
            entry = openAiEntry,
            rerunId = aiRerunId,
            answerId = aiAnswerId,
            showAiButton = !aiButtonHidden,
            onShowAiButtonChange = { show -> scope.launch { context.setAiButtonHidden(!show) } },
            onExpressionRecognized = { expr ->
                viewModel.setExpressionFromScan(expr)
            },
            instantAnswer = { text ->
                viewModel.evaluateStandalone(text)?.let {
                    com.vagujhelyigergely.calculatorm3.camera.InstantAnswer(it, viewModel.formatForDisplay(it))
                }
            },
            // Stop any in-flight inference before closing — the ViewModel is Activity-scoped, so
            // without this the scan coroutine would keep generating in the background after dismiss.
            onDismiss = {
                scanViewModel.stopInference()
                aiEntry = null
                aiRerunId = null
                aiAnswerId = null
            }
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
                onShowHistory = { showHistory = true },
                onAiSelect = if (aiVisible) handleShowAi else null,
                onAllClearPressed = onAllClearPressed
            )
        } else {
            PortraitLayout(
                viewModel = viewModel,
                buttons = buttons,
                onShowHistory = { showHistory = true },
                onAiSelect = if (aiVisible) handleShowAi else null,
                onAllClearPressed = onAllClearPressed
            )
        }
    }
}

@Composable
private fun PortraitLayout(
    viewModel: CalculatorViewModel,
    buttons: List<List<CalcButton>>,
    onShowHistory: () -> Unit,
    onAiSelect: ((AiEntry) -> Unit)? = null,
    onAllClearPressed: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        // Top bar with the AI menu (left) and history (right) buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = if (onAiSelect != null) Arrangement.SpaceBetween else Arrangement.End
        ) {
            if (onAiSelect != null) {
                AiMenuButton(onSelect = onAiSelect)
            }
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
            formatHistoryResult = viewModel::formatForDisplay,
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
            onAllClearPressed = onAllClearPressed,
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
    onShowHistory: () -> Unit,
    onAiSelect: ((AiEntry) -> Unit)? = null,
    onAllClearPressed: () -> Unit = {}
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
                horizontalArrangement = if (onAiSelect != null) Arrangement.SpaceBetween else Arrangement.End
            ) {
                if (onAiSelect != null) {
                    AiMenuButton(onSelect = onAiSelect)
                }
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
                formatHistoryResult = viewModel::formatForDisplay,
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
                onAllClearPressed = onAllClearPressed,
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
    onAllClearPressed: () -> Unit = {},
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
                        onClick = {
                            viewModel.onButtonPress(button.label)
                            if (button.label == "AC") onAllClearPressed()
                        },
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
    // History stores full-precision results; fit them to the screen only when drawn.
    formatHistoryResult: (String) -> String = { it },
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val formatted = formatExpression(displayExpression)
    // When E notation is active, display differs from raw expression —
    // cursor mapping is invalid, so disable cursor interaction
    val eNotationActive = displayExpression != expression
    val cursorInFormatted = if (eNotationActive) formatted.length
        else mapCursorToFormatted(expression, cursorPosition)

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
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "= ${formatResultNumber(formatHistoryResult(entry.result))}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
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
                    text = formatExpression(history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.outline,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CalculatorTestTags.HISTORY_LABEL)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expression with auto-sizing font — uses TextMeasurer to compute
            // correct font size and front-trim in a single pass (no flicker)
            val fontSizeSteps = remember { listOf(56f, 46f, 38f, 32f, 28f) }
            val textMeasurer = rememberTextMeasurer()

            val cursorVisible = cursorPosition < expression.length && expression.isNotEmpty() && !eNotationActive

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

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val rawText = formatted.ifEmpty { "0" }
                val maxWidthPx = constraints.maxWidth

                // Cache measurement results — only recompute when text or width changes
                val (estimatedStep, bestTrim) = remember(rawText, maxWidthPx) {
                    val measureConstraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = maxWidthPx
                    )

                    fun makeStyle(step: Int) = androidx.compose.ui.text.TextStyle(
                        fontSize = fontSizeSteps[step].sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = (fontSizeSteps[step] * 1.15f).sp,
                        letterSpacing = (-0.5).sp,
                    )

                    // Find largest font that fits in 2 lines (best-effort estimate)
                    var step = fontSizeSteps.lastIndex
                    for (i in fontSizeSteps.indices) {
                        val result = textMeasurer.measure(
                            text = androidx.compose.ui.text.AnnotatedString(rawText),
                            style = makeStyle(i),
                            constraints = measureConstraints,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!result.hasVisualOverflow) {
                            step = i
                            break
                        }
                    }

                    // If at smallest font and still overflows, trim from front
                    var trim = 0
                    if (step == fontSizeSteps.lastIndex) {
                        val style = makeStyle(step)
                        val fullResult = textMeasurer.measure(
                            text = androidx.compose.ui.text.AnnotatedString(rawText),
                            style = style,
                            constraints = measureConstraints,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (fullResult.hasVisualOverflow) {
                            val visibleEnd = fullResult.getLineEnd(
                                fullResult.lineCount - 1, true
                            )
                            val excess = rawText.length - visibleEnd
                            trim = (excess + 2).coerceAtLeast(1)

                            // Fine-tune until it fits (guard: max 20 extra iterations)
                            var guard = 0
                            while (trim < rawText.length - 1 && guard < 20) {
                                val trimmedText = "… " + rawText.drop(trim)
                                val trimResult = textMeasurer.measure(
                                    text = androidx.compose.ui.text.AnnotatedString(trimmedText),
                                    style = style,
                                    constraints = measureConstraints,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!trimResult.hasVisualOverflow) break
                                trim++
                                guard++
                            }
                        }
                    }

                    step to trim
                }

                // onTextLayout correction: TextMeasurer can disagree with the
                // actual Text composable on some OEMs (e.g. Samsung One UI).
                // Start from the TextMeasurer estimate and let onTextLayout
                // step down further if the real rendering still overflows.
                var fontCorrection by remember(rawText) { mutableIntStateOf(0) }
                val bestStep = (estimatedStep + fontCorrection).coerceAtMost(fontSizeSteps.lastIndex)

                val displayText = if (bestTrim > 0 && rawText.length > bestTrim) {
                    "… " + rawText.drop(bestTrim)
                } else {
                    rawText
                }
                val currentFontSize = fontSizeSteps[bestStep]
                // "… " prefix length — used to clamp cursor/tap offsets to visible area
                val prefixLen = if (bestTrim > 0) 2 else 0

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
                        if (result.hasVisualOverflow && bestStep < fontSizeSteps.lastIndex) {
                            fontCorrection++
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CalculatorTestTags.EXPRESSION)
                        .pointerInput(displayText, bestTrim, eNotationActive) {
                            if (eNotationActive) return@pointerInput
                            detectTapGestures { offset ->
                                textLayoutResult.value?.let { layout ->
                                    val tappedOffset = layout.getOffsetForPosition(offset)
                                    // Clamp taps on "… " prefix to the first visible char
                                    val clampedTap = tappedOffset.coerceAtLeast(prefixLen)
                                    val adjustedOffset = if (bestTrim > 0) {
                                        clampedTap + bestTrim - prefixLen
                                    } else clampedTap
                                    val rawCursor = mapCursorFromFormatted(expression, adjustedOffset)
                                    onCursorChange(rawCursor)
                                }
                            }
                        }
                )

                // Hide cursor if it's in the trimmed-away portion
                val cursorInVisibleRange = bestTrim == 0 || cursorInFormatted >= bestTrim
                if (cursorVisible && blinkVisible && cursorInVisibleRange) {
                    textLayoutResult.value?.let { layout ->
                        val layoutLen = layout.layoutInput.text.length
                        val adjustedCursor = if (bestTrim > 0) {
                            cursorInFormatted - bestTrim + prefixLen
                        } else cursorInFormatted
                        val cursorOffset = adjustedCursor.coerceIn(0, layoutLen)
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
                text = if (result.startsWith("Error")) {
                    when (result) {
                        CalculatorViewModel.ERROR_DIVISION_BY_ZERO -> stringResource(R.string.error_division_by_zero)
                        CalculatorViewModel.ERROR_OVERFLOW -> stringResource(R.string.error_overflow)
                        else -> stringResource(R.string.error_invalid_operation)
                    }
                } else "= ${formatResultNumber(result)}",
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary.copy(alpha = 0.65f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag(CalculatorTestTags.PREVIEW)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Stable semantics tags so UI tests can assert on one specific display, not any text on screen. */
internal object CalculatorTestTags {
    /** The main expression / answer line. */
    const val EXPRESSION = "calc_expression"
    /** The live result preview ("= 8") or the error message below the expression. */
    const val PREVIEW = "calc_preview"
    /** The "5 + 3 =" label above the expression after pressing equals. */
    const val HISTORY_LABEL = "calc_history_label"
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
    formatHistoryResult: (String) -> String = { it },
    onDismiss: () -> Unit,
    onEntryClick: (HistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    /** Saved AI solves; null while AI is locked, which hides the AI history tab. */
    aiHistory: List<AiHistoryEntry>? = null,
    aiImageOf: (AiHistoryEntry) -> java.io.File? = { null },
    onUseAiAnswer: (String) -> Unit = {},
    onSolveAgain: (AiHistoryEntry) -> Unit = {},
    onDeleteAiEntry: (AiHistoryEntry) -> Unit = {},
    onClearAiHistory: () -> Unit = {},
    onAskAboutAi: (AiHistoryEntry) -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    // 0 = calculations, 1 = AI history. Saveable, like the open entry, so rotation keeps both.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openAiId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClearAi by remember { mutableStateOf(false) }
    val showingAi = aiHistory != null && tab == 1
    val openAi = if (showingAi) aiHistory?.find { it.id == openAiId } else null

    if (confirmClearAi) {
        AlertDialog(
            onDismissRequest = { confirmClearAi = false },
            title = { Text(stringResource(R.string.ai_history_clear)) },
            text = { Text(stringResource(R.string.ai_history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAi = false
                    openAiId = null
                    onClearAiHistory()
                }) { Text(stringResource(R.string.ai_history_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAi = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

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
                if (showingAi && openAi == null && !aiHistory.isNullOrEmpty()) {
                    // Pictures go too, so this one asks first.
                    IconButton(onClick = { confirmClearAi = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.ai_history_clear),
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                } else if (!showingAi && historyList.isNotEmpty()) {
                    IconButton(onClick = onClearHistory) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.clear_history),
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (aiHistory != null) {
                SecondaryTabRow(selectedTabIndex = tab, containerColor = colorScheme.surface) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.history_tab_calculations)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.history_tab_ai)) })
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (showingAi) {
                if (openAi != null) {
                    AiHistoryDetail(
                        entry = openAi,
                        image = aiImageOf(openAi),
                        onBack = { openAiId = null },
                        onUse = onUseAiAnswer,
                        onSolveAgain = { onSolveAgain(openAi) },
                        onDelete = {
                            openAiId = null
                            onDeleteAiEntry(openAi)
                        },
                        onAskAbout = { onAskAboutAi(openAi) }
                    )
                } else {
                    AiHistoryList(
                        entries = aiHistory.orEmpty(),
                        imageOf = aiImageOf,
                        onOpen = { openAiId = it.id },
                        onDelete = onDeleteAiEntry
                    )
                }
            } else if (historyList.isEmpty()) {
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
                                    text = "= ${formatResultNumber(formatHistoryResult(entry.result))}",
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

private val BINARY_OPERATORS = setOf('+', '×', '÷', '−')

private fun needsSeparator(indexInIntPart: Int, intPartLength: Int): Boolean =
    indexInIntPart > 0 && (intPartLength - indexInIntPart) % 3 == 0

internal fun formatExpression(expr: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < expr.length) {
        val c = expr[i]
        when {
            c.isDigit() || (c == '.' && i + 1 < expr.length && expr[i + 1].isDigit()) -> {
                // Integer part — collect digits and add thousand separators
                val intStart = i
                while (i < expr.length && expr[i].isDigit()) i++
                val intPart = expr.substring(intStart, i)
                for (j in intPart.indices) {
                    if (needsSeparator(j, intPart.length)) sb.append(',')
                    sb.append(intPart[j])
                }
                // Decimal part (no separators)
                if (i < expr.length && expr[i] == '.') {
                    sb.append('.')
                    i++
                    while (i < expr.length && expr[i].isDigit()) {
                        sb.append(expr[i])
                        i++
                    }
                }
                // E notation suffix (pass through)
                if (i < expr.length && (expr[i] == 'E' || expr[i] == 'e')) {
                    sb.append(expr[i])
                    i++
                    if (i < expr.length && (expr[i] == '+' || expr[i] == '-')) {
                        sb.append(expr[i])
                        i++
                    }
                    while (i < expr.length && expr[i].isDigit()) {
                        sb.append(expr[i])
                        i++
                    }
                }
            }
            // Binary operators: space around them (not at position 0).
            // ASCII '-' (from +/− toggle) is unary, excluded from this set.
            c in BINARY_OPERATORS && sb.isNotEmpty() -> {
                sb.append(" $c ")
                i++
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

internal fun formatResultNumber(value: String): String {
    if (value.isEmpty() || value.startsWith("Error") || value.contains('E') || value.contains('e')) return value
    val negative = value.startsWith("-")
    val abs = if (negative) value.substring(1) else value
    val dotIndex = abs.indexOf('.')
    val intPart = if (dotIndex >= 0) abs.substring(0, dotIndex) else abs
    if (intPart.length <= 3) return value
    val rest = if (dotIndex >= 0) abs.substring(dotIndex) else ""
    val formatted = buildString {
        for (j in intPart.indices) {
            if (needsSeparator(j, intPart.length)) append(',')
            append(intPart[j])
        }
    }
    return (if (negative) "-" else "") + formatted + rest
}

internal fun mapCursorToFormatted(raw: String, rawCursor: Int): Int {
    var fPos = 0
    var i = 0
    val limit = rawCursor.coerceAtMost(raw.length)
    while (i < limit) {
        val c = raw[i]
        when {
            c.isDigit() || (c == '.' && i + 1 < raw.length && raw[i + 1].isDigit()) -> {
                val intStart = i
                var intEnd = i
                while (intEnd < raw.length && raw[intEnd].isDigit()) intEnd++
                val intLen = intEnd - intStart
                val intLimit = limit.coerceAtMost(intEnd)
                for (j in i until intLimit) {
                    if (needsSeparator(j - intStart, intLen)) fPos++
                    fPos++
                }
                i = intLimit
                if (i == intEnd && i < limit && i < raw.length && raw[i] == '.') {
                    fPos++; i++
                    while (i < limit && i < raw.length && raw[i].isDigit()) { fPos++; i++ }
                }
                if (i < limit && i < raw.length && (raw[i] == 'E' || raw[i] == 'e')) {
                    fPos++; i++
                    if (i < limit && i < raw.length && (raw[i] == '+' || raw[i] == '-')) { fPos++; i++ }
                    while (i < limit && i < raw.length && raw[i].isDigit()) { fPos++; i++ }
                }
            }
            c in BINARY_OPERATORS && i > 0 -> { fPos += 3; i++ }
            else -> { fPos++; i++ }
        }
    }
    return fPos
}

internal fun mapCursorFromFormatted(raw: String, formattedCursor: Int): Int {
    var fPos = 0
    var i = 0
    while (i < raw.length && fPos < formattedCursor) {
        val c = raw[i]
        when {
            c.isDigit() || (c == '.' && i + 1 < raw.length && raw[i + 1].isDigit()) -> {
                val intStart = i
                var intEnd = i
                while (intEnd < raw.length && raw[intEnd].isDigit()) intEnd++
                val intLen = intEnd - intStart
                while (i < intEnd && fPos < formattedCursor) {
                    val posInInt = i - intStart
                    if (needsSeparator(posInInt, intLen)) {
                        fPos++
                        if (fPos >= formattedCursor) break
                    }
                    fPos++; i++
                }
                if (i == intEnd && i < raw.length && raw[i] == '.' && fPos < formattedCursor) {
                    fPos++; i++
                    while (i < raw.length && raw[i].isDigit() && fPos < formattedCursor) { fPos++; i++ }
                }
                if (i < raw.length && (raw[i] == 'E' || raw[i] == 'e') && fPos < formattedCursor) {
                    fPos++; i++
                    if (i < raw.length && (raw[i] == '+' || raw[i] == '-') && fPos < formattedCursor) { fPos++; i++ }
                    while (i < raw.length && raw[i].isDigit() && fPos < formattedCursor) { fPos++; i++ }
                }
            }
            c in BINARY_OPERATORS && i > 0 -> { fPos += 3; i++ }
            else -> { fPos++; i++ }
        }
    }
    return i
}
