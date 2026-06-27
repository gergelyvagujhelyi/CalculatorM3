@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)

package com.vagujhelyigergely.calculatorm3.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.WindowManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vagujhelyigergely.calculatorm3.R
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


@Composable
fun CameraScanScreen(
    viewModel: ScanViewModel,
    onExpressionRecognized: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Model whose download is queued behind the notification-permission flow.
    var pendingDownloadModel by remember { mutableStateOf<AiModel?>(null) }
    var showNotifRationale by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Start the queued download regardless of the result — the notification only shows
        // progress; the download itself runs either way.
        pendingDownloadModel?.let { viewModel.startDownload(it) }
        pendingDownloadModel = null
    }
    // Start a model download. On Android 13+ without notification permission, first explain why
    // we ask (the download is large and runs in a background service with a progress
    // notification), then request it. Pre-Tiramisu or already-granted: download straight away.
    val startDownloadWithNotifPrompt: (AiModel) -> Unit = { model ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadModel = model
            showNotifRationale = true
        } else {
            viewModel.startDownload(model)
        }
    }
    // Launches the AppAuth Custom Tab for HuggingFace sign-in; the returned Intent carries
    // the authorization code, which the ViewModel exchanges for a token.
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.onSignInResult(result.data) }

    // Initialize on a genuine screen entry only — NOT on every rotation. The ScanViewModel is
    // Activity-scoped, so a config change recreates this Composable while the ViewModel keeps its
    // in-flight Processing/Success state; re-running initialize() then would reset the screen back
    // to the capture chooser. rememberSaveable survives rotation but is dropped when the screen
    // leaves composition, so a real reopen still re-initializes. The extra Idle check covers process
    // death, which restores the saved flag but recreates the ViewModel fresh (uiState == Idle).
    var didInitialize by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!didInitialize || viewModel.uiState is ScanUiState.Idle) {
            didInitialize = true
            viewModel.initialize()
        }
    }

    if (showNotifRationale) {
        // Proceed with the queued download whether the user allows, declines, or dismisses.
        val proceedWithoutPermission = {
            showNotifRationale = false
            pendingDownloadModel?.let { viewModel.startDownload(it) }
            pendingDownloadModel = null
        }
        AlertDialog(
            onDismissRequest = proceedWithoutPermission,
            icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
            title = { Text(stringResource(R.string.notif_permission_title)) },
            text = { Text(stringResource(R.string.notif_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotifRationale = false
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notif_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = proceedWithoutPermission) {
                    Text(stringResource(R.string.not_now))
                }
            }
        )
    }

    // Keep screen on while the user is actively waiting in-app (model load / inference).
    // Downloads run in a WorkManager foreground service with their own notification,
    // so they survive the screen turning off and don't need this.
    val keepScreenOn = viewModel.uiState is ScanUiState.ModelLoading ||
        viewModel.uiState is ScanUiState.Processing
    val activity = context as? android.app.Activity
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            // One SharedTransitionLayout + AnimatedContent drive every state change: states
            // cross-fade/scale into each other and tagged elements (the close button, the hero
            // icon/title, the answer card) morph across states instead of cutting. The content
            // key is coarse so streaming/progress updates (new Processing/Downloading instances
            // on every token/byte) recompose in place rather than replaying the transition.
            SharedTransitionLayout {
                AnimatedContent(
                    targetState = viewModel.uiState,
                    contentKey = { it.screenKey() },
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(240)) +
                            scaleIn(initialScale = 0.94f, animationSpec = tween(240))) togetherWith
                            fadeOut(animationSpec = tween(160))
                    },
                    label = "scanState"
                ) { state ->
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides this@SharedTransitionLayout,
                        LocalAiVisibilityScope provides this@AnimatedContent
                    ) {
                        when (state) {
                            is ScanUiState.Idle -> StatusContent(
                                title = stringResource(R.string.initializing),
                                subtitle = null,
                                onDismiss = onDismiss
                            )
                            is ScanUiState.ModelLoading -> ModelLoadingContent(
                                startTimeMs = state.startTimeMs,
                                onDismiss = onDismiss
                            )
                            is ScanUiState.Capturing -> CameraContent(
                                modelName = viewModel.selectedModelName,
                                onPhotoCaptured = { path -> viewModel.onPhotoCaptured(path) },
                                onCaptureError = { msg -> viewModel.onCaptureError(msg) },
                                onSwitchModel = { viewModel.showModelSelection() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.Processing -> ProcessingContent(
                                partialRaw = state.partialRaw,
                                startTimeMs = state.startTimeMs,
                                tokenCount = state.tokenCount,
                                backend = state.backend,
                                firstTokenMs = state.firstTokenMs,
                                onStop = { viewModel.stopInference() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.Success -> SuccessContent(
                                answer = state.answer,
                                rawResponse = state.rawResponse,
                                elapsedMs = state.elapsedMs,
                                tokenCount = state.tokenCount,
                                backend = state.backend,
                                ttftMs = state.ttftMs,
                                decodeTokensPerSec = state.decodeTokensPerSec,
                                onUse = {
                                    onExpressionRecognized(state.answer)
                                    onDismiss()
                                },
                                onRetry = { viewModel.retry() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.Error -> ErrorContent(
                                message = state.message,
                                rawResponse = state.rawResponse,
                                onRetry = { viewModel.retry() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.FirstTimeWarning -> FirstTimeWarningContent(
                                onContinue = { viewModel.showModelSelection() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.MobileDataWarning -> MobileDataWarningContent(
                                model = state.model,
                                onContinue = { viewModel.confirmMobileDataDownload(state.model) },
                                onCancel = { viewModel.showModelSelection() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.AuthError -> AuthErrorContent(
                                httpCode = state.httpCode,
                                model = state.model,
                                onRetry = { startDownloadWithNotifPrompt(state.model) },
                                onReauth = { viewModel.showSignIn(state.model) },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.SignInRequired -> SignInContent(
                                model = state.model,
                                onSignIn = { signInLauncher.launch(viewModel.signInIntentFor(state.model)) },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.Authenticating -> StatusContent(
                                title = stringResource(R.string.signing_in),
                                subtitle = null,
                                onDismiss = onDismiss
                            )
                            is ScanUiState.Downloading -> DownloadingContent(
                                currentFile = state.currentFile,
                                downloadedBytes = state.downloadedBytes,
                                totalBytes = state.totalBytes,
                                fileIndex = state.fileIndex,
                                fileCount = state.fileCount,
                                onCancel = { viewModel.cancelDownload() },
                                onDismiss = onDismiss
                            )
                            is ScanUiState.DownloadComplete -> StatusContent(
                                title = stringResource(R.string.download_complete),
                                subtitle = stringResource(R.string.model_loading),
                                onDismiss = onDismiss,
                                icon = Icons.Default.CheckCircle,
                                loading = false
                            )
                            is ScanUiState.ModelSelection -> ModelSelectionContent(
                                selectedModel = state.selectedModel,
                                downloadedModels = state.downloadedModels,
                                deviceRamGb = state.deviceRamGb,
                                onSelectModel = { viewModel.selectModel(it) },
                                onDownloadModel = startDownloadWithNotifPrompt,
                                onDismiss = onDismiss
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Coarse, stable identity per screen so [AnimatedContent] only animates real screen
 * changes. Crucially, Processing and Downloading collapse to a single key each: their
 * state object is replaced on every streamed token / progress tick, and we don't want
 * the enter/exit transition to replay each time.
 */
private fun ScanUiState.screenKey(): Any = when (this) {
    is ScanUiState.Idle -> "idle"
    is ScanUiState.ModelLoading -> "modelLoading"
    is ScanUiState.Capturing -> "capturing"
    is ScanUiState.Processing -> "processing"
    is ScanUiState.Success -> "success"
    is ScanUiState.Error -> "error"
    is ScanUiState.ModelSelection -> "modelSelection"
    is ScanUiState.Downloading -> "downloading"
    is ScanUiState.DownloadComplete -> "downloadComplete"
    is ScanUiState.SignInRequired -> "signIn"
    is ScanUiState.Authenticating -> "authenticating"
    is ScanUiState.AuthError -> "authError"
    is ScanUiState.FirstTimeWarning -> "firstTime"
    is ScanUiState.MobileDataWarning -> "mobileData"
}

/** Elapsed time counter that updates every second. */
@Composable
private fun ElapsedTimeText(startTimeMs: Long) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startTimeMs) {
        while (true) {
            elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
            delay(1000)
        }
    }
    Text(
        text = "${elapsed}s",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Generic centered status screen: a hero (expressive loader or icon) plus optional subtitle. */
@Composable
private fun StatusContent(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    loading: Boolean = true
) {
    AiStateScaffold(onDismiss = onDismiss) {
        AiHero(
            title = title,
            subtitle = subtitle,
            icon = icon,
            loading = loading,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        )
    }
}

@Composable
private fun ModelLoadingContent(startTimeMs: Long, onDismiss: () -> Unit) {
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AiHero(
                title = stringResource(R.string.model_loading),
                subtitle = stringResource(R.string.model_loading_hint),
                loading = true
            )
            ElapsedTimeText(startTimeMs = startTimeMs)
        }
    }
}

@Composable
private fun ProcessingContent(
    partialRaw: String,
    startTimeMs: Long,
    tokenCount: Int,
    backend: String,
    firstTokenMs: Long?,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    val isGenerating = partialRaw.isNotEmpty()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Landscape has a short viewport, and the streaming card grows as tokens arrive. Cap it to a
    // fraction of the available height (instead of a fixed 300dp) and drop the hero, so the HUD and
    // Stop button always stay on screen — otherwise the growing card pushes Stop off in landscape.
    val cardMaxHeight = if (isLandscape) (configuration.screenHeightDp * 0.45f).dp else 300.dp

    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 12.dp else 20.dp)
        ) {
            if (!isLandscape) {
                AiHero(
                    title = if (isGenerating)
                        stringResource(R.string.camera_generating)
                    else
                        stringResource(R.string.camera_processing_image),
                    subtitle = if (isGenerating) null else stringResource(R.string.processing_image_hint),
                    icon = Icons.Default.Psychology,
                    pulsing = true
                )
            }

            // One card spans the whole "answer" lifecycle: a shimmering skeleton while the
            // model prefills the image (no tokens yet), then the live streaming text. Tagged
            // as the shared "answer-card" so it morphs straight into the Success result card.
            val scrollState = rememberScrollState()
            LaunchedEffect(partialRaw) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            AiCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = cardMaxHeight)
                    .aiSharedBounds("answer-card")
                    .animateContentSize()
            ) {
                if (isGenerating) {
                    Text(
                        text = partialRaw,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(scrollState)
                    )
                } else {
                    ShimmerLines(modifier = Modifier.padding(16.dp))
                }
            }

            PerfHud(startTimeMs = startTimeMs, tokenCount = tokenCount, backend = backend, firstTokenMs = firstTokenMs)

            // Abort a slow or wrong generation and return to capture. Stop is prompt during token
            // generation; during the initial image prefill it takes effect at the next boundary.
            OutlinedButton(onClick = onStop) {
                Text(stringResource(R.string.stop))
            }
        }
    }
}

/** TEMP debug HUD: live decode tok/s (excludes image prefill), TTFT, and the active backend. */
@Composable
private fun PerfHud(startTimeMs: Long, tokenCount: Int, backend: String, firstTokenMs: Long?) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startTimeMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    val label = backend.ifEmpty { "?" }
    val text = if (firstTokenMs == null) {
        // Still prefilling the image — no tokens generated yet.
        "%s · prefill %.1fs".format(label, (nowMs - startTimeMs).coerceAtLeast(0L) / 1000.0)
    } else {
        val ttftSecs = (firstTokenMs - startTimeMs).coerceAtLeast(0L) / 1000.0
        val decodeSecs = (nowMs - firstTokenMs).coerceAtLeast(0L) / 1000.0
        val decodeTps = if (tokenCount > 1 && decodeSecs > 0.05) (tokenCount - 1) / decodeSecs else 0.0
        "%s · %.1f tok/s · ttft %.1fs".format(label, decodeTps, ttftSecs)
    }
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CameraContent(
    modelName: String,
    onPhotoCaptured: (String) -> Unit,
    onCaptureError: (String) -> Unit,
    onSwitchModel: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Path of the file the system camera writes into. Saveable so it survives the
    // activity being recreated while the camera is foreground (memory pressure) —
    // otherwise the returned photo would be dropped and the scan lost.
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }

    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingPhotoPath?.let { File(it) }
        pendingPhotoPath = null
        if (success && file != null) {
            onPhotoCaptured(file.absolutePath)
        } else {
            // Cancelled or failed — discard the empty file and stay on this screen.
            file?.delete()
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = copyUriToCache(context, uri)
                if (path != null) onPhotoCaptured(path)
                else onCaptureError("Could not open the selected image")
            }
        }
    }

    AiStateScaffold(onDismiss = onDismiss) {
        // Model switcher, top-end (close button is supplied by the scaffold, top-start).
        AssistChip(
            onClick = onSwitchModel,
            label = { Text(text = modelName, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AiHero(
                title = stringResource(R.string.scan_hint),
                icon = Icons.Default.PhotoCamera
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                    pendingPhotoPath = file.absolutePath
                    takePhotoLauncher.launch(fileProviderUri(context, file))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.take_photo))
            }
            OutlinedButton(
                onClick = {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.choose_from_gallery))
            }
        }
    }
}

/** Content Uri the system camera can write the captured photo to, via the app's FileProvider. */
private fun fileProviderUri(context: android.content.Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Copy a picked gallery image into the app cache and return its path (the solver needs a file path). */
private suspend fun copyUriToCache(context: android.content.Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                null
            } else {
                val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

/** Renders [text] as markdown with LaTeX math (`$…$` inline and `$$…$$` block) via Markwon + jlatexmath. */
@Composable
private fun MarkdownLatexText(text: String, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val argb = color.toArgb()
    val textSizePx = with(LocalDensity.current) { 16.sp.toPx() }
    val markwon = remember(textSizePx) {
        Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(textSizePx) { it.inlinesEnabled(true) })
            .build()
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> TextView(ctx).apply { textSize = 16f } },
        update = { tv ->
            tv.setTextColor(argb)
            markwon.setMarkdown(tv, normalizeLatex(text))
        }
    )
}

/** Normalize \(..\) and \[..\] delimiters to $..$ / $$..$$ so Markwon's latex ext renders them. */
// Markwon's jlatexmath only treats $$..$$ as math (single $..$ stays plain text),
// but with inlinesEnabled a $$..$$ span renders inline. So normalize every math
// delimiter the model might emit — \(..\), \[..\] and single $..$ — to $$..$$.
private const val DD = "$$"
private val parenMathRegex = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val bracketMathRegex = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
// A single $..$ pair not adjacent to another $ (so it skips existing $$..$$ spans).
private val singleDollarRegex = Regex("(?<![$])[$](?![$])(.+?)(?<![$])[$](?![$])", RegexOption.DOT_MATCHES_ALL)

private fun normalizeLatex(s: String): String {
    var r = parenMathRegex.replace(s) { DD + it.groupValues[1] + DD }
    r = bracketMathRegex.replace(r) { DD + it.groupValues[1] + DD }
    r = singleDollarRegex.replace(r) { DD + it.groupValues[1] + DD }
    return r
}

@Composable
private fun SuccessContent(
    answer: String,
    rawResponse: String,
    elapsedMs: Long,
    tokenCount: Int,
    backend: String,
    ttftMs: Long,
    decodeTokensPerSec: Double,
    onUse: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AiHero(
                title = stringResource(R.string.answer_found),
                icon = Icons.Default.CheckCircle
            )
            // The model's full answer (markdown + LaTeX). Shares the "answer-card" bounds with
            // the Processing card, so the streaming text morphs into this result.
            AiCard(modifier = Modifier.fillMaxWidth().aiSharedBounds("answer-card")) {
                MarkdownLatexText(
                    text = rawResponse.trim(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
            Text(
                text = stringResource(R.string.recognized_in, formatElapsed(elapsedMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // TEMP debug: decode speed (excludes image prefill), TTFT, and the backend.
            Text(
                text = "%s · %.1f tok/s decode · ttft %s · %d tokens".format(
                    backend.ifEmpty { "?" },
                    decodeTokensPerSec,
                    formatElapsed(ttftMs),
                    tokenCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
                Button(onClick = onUse) {
                    Text(
                        text = if (answer.isNotBlank())
                            stringResource(R.string.use_answer, answer)
                        else stringResource(R.string.use_expression)
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    rawResponse: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var showRaw by remember { mutableStateOf(false) }

    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(32.dp)
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AiHero(
                title = stringResource(R.string.recognition_error),
                subtitle = message,
                icon = Icons.Default.ErrorOutline,
                tint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
            if (rawResponse != null) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(
                        text = stringResource(R.string.raw_model_output),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Icon(
                        imageVector = if (showRaw) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                AnimatedVisibility(visible = showRaw) {
                    AiCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = rawResponse,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionContent(
    selectedModel: AiModel,
    downloadedModels: List<AiModel>,
    deviceRamGb: Int,
    onSelectModel: (AiModel) -> Unit,
    onDownloadModel: (AiModel) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Two top-level groups: the Gemma 4 models (no account needed) and the gated models
    // that need a HuggingFace login. Today these coincide exactly with the requiresAuth split.
    val gemma4Models = AiModel.entries.filter { !it.requiresAuth }
    val loginModels = AiModel.entries.filter { it.requiresAuth }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.choose_model)) },
                navigationIcon = {
                    CloseButton(onDismiss = onDismiss, modifier = Modifier.aiSharedElement("close"))
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Fold the Scaffold insets into contentPadding (rather than Modifier.padding) so the
            // list fills the screen and items scroll behind the translucent nav bar, while the
            // first/last items stay clear of the app bar and system bars (incl. landscape cutouts).
            contentPadding = PaddingValues(
                start = 24.dp + innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = 24.dp + innerPadding.calculateEndPadding(LocalLayoutDirection.current),
                top = 8.dp + innerPadding.calculateTopPadding(),
                bottom = 24.dp + innerPadding.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AiHint(
                    text = stringResource(R.string.choose_model_description, deviceRamGb),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }
            if (gemma4Models.isNotEmpty()) {
                item { ModelGroupHeader(stringResource(R.string.models_group_gemma4)) }
                items(gemma4Models, key = { it.id }) { model ->
                    ModelCard(model, model in downloadedModels, model == selectedModel,
                        model.minRamGb > deviceRamGb, deviceRamGb, onSelectModel, onDownloadModel)
                }
            }
            if (loginModels.isNotEmpty()) {
                item { ModelGroupHeader(stringResource(R.string.models_group_login)) }
                items(loginModels, key = { it.id }) { model ->
                    ModelCard(model, model in downloadedModels, model == selectedModel,
                        model.minRamGb > deviceRamGb, deviceRamGb, onSelectModel, onDownloadModel)
                }
            }
        }
    }
}

@Composable
private fun ModelGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}

@Composable
private fun ModelCard(
    model: AiModel,
    isDownloaded: Boolean,
    isSelected: Boolean,
    tooLarge: Boolean,
    deviceRamGb: Int,
    onSelectModel: (AiModel) -> Unit,
    onDownloadModel: (AiModel) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Press feedback mirroring the calculator buttons: a subtle spring scale + haptic.
    LaunchedEffect(isPressed) {
        if (isPressed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "modelCardScale"
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            tooLarge -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "modelCardColor"
    )

    OutlinedCard(
        onClick = {
            when {
                isDownloaded -> onSelectModel(model)
                tooLarge -> Unit  // can't run on this device — the card shows why
                else -> onDownloadModel(model)
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = if (isSelected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${model.totalSizeDisplay} · ${model.minRamGb} GB+ RAM",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tooLarge) {
                    Text(
                        text = stringResource(R.string.model_too_large, model.minRamGb, deviceRamGb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isDownloaded) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.downloaded),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.download_model),
                    tint = if (tooLarge)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun MobileDataWarningContent(
    model: AiModel,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AiHero(
                title = stringResource(R.string.mobile_data_title),
                subtitle = stringResource(R.string.mobile_data_description, model.totalSizeDisplay),
                icon = Icons.Default.ErrorOutline,
                tint = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = onContinue) {
                    Text(stringResource(R.string.download_anyway))
                }
            }
        }
    }
}

@Composable
private fun FirstTimeWarningContent(
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AiHero(
                title = stringResource(R.string.ai_feature_title),
                subtitle = stringResource(R.string.ai_feature_description),
                icon = Icons.Default.Psychology
            )
            Button(onClick = onContinue) {
                Text(stringResource(R.string.ai_feature_continue))
            }
        }
    }
}

@Composable
private fun AuthErrorContent(
    httpCode: Int,
    model: AiModel,
    onRetry: () -> Unit,
    onReauth: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (httpCode == 403) {
                AiHero(
                    title = stringResource(R.string.license_required_title),
                    subtitle = stringResource(R.string.license_required_description, model.displayName),
                    icon = Icons.Default.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error
                )
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.licenseUrl)))
                }) {
                    Text(stringResource(R.string.accept_license))
                }
            } else {
                AiHero(
                    title = stringResource(R.string.token_invalid_title),
                    subtitle = stringResource(R.string.token_invalid_description),
                    icon = Icons.Default.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error
                )
                Button(onClick = onReauth) {
                    Text(stringResource(R.string.sign_in_again))
                }
            }

            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun SignInContent(
    model: AiModel,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit
) {
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AiHero(
                title = stringResource(R.string.hf_token_title),
                subtitle = stringResource(R.string.hf_token_description, model.displayName),
                icon = Icons.AutoMirrored.Filled.Login
            )
            Button(onClick = onSignIn) {
                Text(stringResource(R.string.sign_in_huggingface))
            }
        }
    }
}

@Composable
private fun DownloadingContent(
    currentFile: String,
    downloadedBytes: Long,
    totalBytes: Long,
    fileIndex: Int,
    fileCount: Int,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AiStateScaffold(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AiHero(
                title = stringResource(R.string.downloading_model),
                icon = Icons.Default.CloudDownload,
                pulsing = true
            )
            Text(
                text = stringResource(R.string.download_file_progress, fileIndex + 1, fileCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = currentFile,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else null
            AiDownloadBar(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Text(
                text = when {
                    progress != null ->
                        "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)} (${(progress * 100).toInt()}%)"
                    downloadedBytes > 0 -> formatBytes(downloadedBytes)
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateContentSize()
            )
            Text(
                text = stringResource(R.string.download_warning),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}
