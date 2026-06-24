package com.vagujhelyigergely.calculatorm3.camera

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vagujhelyigergely.calculatorm3.R
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import kotlinx.coroutines.delay
import java.io.File


@Composable
fun CameraScanScreen(
    viewModel: ScanViewModel,
    onExpressionRecognized: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort: the download runs regardless of notification visibility */ }

    LaunchedEffect(Unit) {
        viewModel.initialize()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            run {
                when (val state = viewModel.uiState) {
                    is ScanUiState.Idle -> StatusContent(
                        icon = Icons.Default.Psychology,
                        title = stringResource(R.string.initializing),
                        subtitle = null,
                        showProgress = true,
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
                        onDismiss = onDismiss
                    )
                    is ScanUiState.Success -> SuccessContent(
                        answer = state.answer,
                        rawResponse = state.rawResponse,
                        elapsedMs = state.elapsedMs,
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
                    is ScanUiState.DeviceTooWeak -> StatusContent(
                        icon = Icons.Default.ErrorOutline,
                        title = stringResource(R.string.device_too_weak_title),
                        subtitle = stringResource(R.string.device_too_weak_description, viewModel.deviceRamGb),
                        showProgress = false,
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
                        onRetry = { viewModel.startDownload(state.model) },
                        onDismiss = onDismiss
                    )
                    is ScanUiState.TokenRequired -> TokenInputContent(
                        model = state.model,
                        onSubmit = { token -> viewModel.setHfTokenAndDownload(token, state.model) },
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
                        icon = Icons.Default.CheckCircle,
                        title = stringResource(R.string.download_complete),
                        subtitle = stringResource(R.string.model_loading),
                        showProgress = true,
                        onDismiss = onDismiss
                    )
                    is ScanUiState.ModelSelection -> ModelSelectionContent(
                        selectedModel = state.selectedModel,
                        downloadedModels = state.downloadedModels,
                        deviceRamGb = state.deviceRamGb,
                        onSelectModel = { viewModel.selectModel(it) },
                        onDownloadModel = { viewModel.startDownload(it) },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onDismiss,
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.close),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
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

/** Generic status screen with icon, title, optional subtitle and progress. */
@Composable
private fun StatusContent(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    showProgress: Boolean,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (showProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }
    }
}

@Composable
private fun ModelLoadingContent(startTimeMs: Long, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.model_loading),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.model_loading_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            ElapsedTimeText(startTimeMs = startTimeMs)
        }
    }
}

@Composable
private fun ProcessingContent(partialRaw: String, startTimeMs: Long, onDismiss: () -> Unit) {
    val isGenerating = partialRaw.isNotEmpty()

    // Pulsing icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale),
                tint = MaterialTheme.colorScheme.primary
            )

            if (!isGenerating) {
                // Phase 1: processing image (prefill) — no tokens yet
                Text(
                    text = stringResource(R.string.camera_processing_image),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.processing_image_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            } else {
                // Phase 2: generating tokens — show streaming output
                Text(
                    text = stringResource(R.string.camera_generating),
                    style = MaterialTheme.typography.titleMedium
                )
                val scrollState = rememberScrollState()
                LaunchedEffect(partialRaw) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp
                ) {
                    Text(
                        text = partialRaw,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(scrollState)
                    )
                }
            }

            ElapsedTimeText(startTimeMs = startTimeMs)
        }
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
    // File the system camera writes the captured photo into.
    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingPhoto
        pendingPhoto = null
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
            val path = copyUriToCache(context, uri)
            if (path != null) onPhotoCaptured(path)
            else onCaptureError("Could not open the selected image")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top bar: close + model switch
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CloseButton(onDismiss = onDismiss)
            TextButton(onClick = onSwitchModel) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Centered prompt + capture/pick actions
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.scan_hint),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                    pendingPhoto = file
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
            Spacer(modifier = Modifier.height(12.dp))
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
private fun copyUriToCache(context: android.content.Context, uri: Uri): String? = try {
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
            markwon.setMarkdown(tv, text)
        }
    )
}

@Composable
private fun SuccessContent(
    answer: String,
    rawResponse: String,
    elapsedMs: Long,
    onUse: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .zIndex(1f)
        )
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
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.answer_found),
                style = MaterialTheme.typography.titleMedium
            )
            // The model's full answer, rendered with markdown + LaTeX math.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(R.string.recognition_error),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
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
                if (showRaw) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
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
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 56.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.choose_model),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.choose_model_description, deviceRamGb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            val freeModels = AiModel.entries.filter { !it.advanced }
            val advancedModels = AiModel.entries.filter { it.advanced }

            freeModels.forEach { model ->
                ModelCard(model, model in downloadedModels, model == selectedModel,
                    model.minRamGb > deviceRamGb, deviceRamGb, onSelectModel, onDownloadModel)
            }

            if (advancedModels.isNotEmpty()) {
                var showAdvanced by remember { mutableStateOf(false) }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(
                        text = stringResource(R.string.advanced_models),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (showAdvanced) {
                    Text(
                        text = stringResource(R.string.advanced_models_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    advancedModels.forEach { model ->
                        ModelCard(model, model in downloadedModels, model == selectedModel,
                            model.minRamGb > deviceRamGb, deviceRamGb, onSelectModel, onDownloadModel)
                    }
                }
            }
        }
    }
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
    Surface(
        onClick = {
            if (isDownloaded) onSelectModel(model) else onDownloadModel(model)
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (tooLarge)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (model.requiresAuth) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.requires_login),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${model.totalSizeDisplay} | ${model.minRamGb} GB+ RAM",
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
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = stringResource(R.string.mobile_data_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.mobile_data_description, model.totalSizeDisplay),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
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
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.ai_feature_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.ai_feature_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )

            if (httpCode == 403) {
                Text(
                    text = stringResource(R.string.license_required_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.license_required_description, model.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.licenseUrl)))
                }) {
                    Text(stringResource(R.string.accept_license))
                }
            } else {
                Text(
                    text = stringResource(R.string.token_invalid_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.token_invalid_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://huggingface.co/settings/tokens")))
                }) {
                    Text(stringResource(R.string.create_token))
                }
            }

            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun TokenInputContent(
    model: AiModel,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf("") }

    // imePadding keeps the token field above the on-screen keyboard.
    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.hf_token_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.hf_token_description, model.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.hf_token_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSubmit(token.trim()) },
                enabled = token.trim().startsWith("hf_")
            ) {
                Text(stringResource(R.string.download_model))
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
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.downloading_model),
                style = MaterialTheme.typography.titleMedium
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
            if (totalBytes > 0) {
                val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Text(
                    text = "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)} (${(progress * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                if (downloadedBytes > 0) {
                    Text(
                        text = formatBytes(downloadedBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
