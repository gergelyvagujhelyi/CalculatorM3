package com.vagujhelyigergely.calculatorm3.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.vagujhelyigergely.calculatorm3.R
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CameraScanScreen(
    viewModel: ScanViewModel,
    onExpressionRecognized: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.initialize()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.initialize()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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
            if (!hasPermission) {
                PermissionDeniedContent(onDismiss = onDismiss)
            } else {
                when (val state = viewModel.uiState) {
                    is ScanUiState.Idle,
                    is ScanUiState.ModelLoading -> LoadingContent(
                        message = stringResource(R.string.model_loading),
                        onDismiss = onDismiss
                    )
                    is ScanUiState.Capturing -> CameraContent(
                        onPhotoCaptured = { path -> viewModel.onPhotoCaptured(path) },
                        onDismiss = onDismiss
                    )
                    is ScanUiState.Processing -> LoadingContent(
                        message = stringResource(R.string.camera_processing),
                        onDismiss = onDismiss
                    )
                    is ScanUiState.Success -> SuccessContent(
                        expression = state.expression,
                        onUse = {
                            onExpressionRecognized(state.expression)
                            onDismiss()
                        },
                        onRetry = { viewModel.retry() },
                        onDismiss = onDismiss
                    )
                    is ScanUiState.Error -> ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.retry() },
                        onDismiss = onDismiss
                    )
                    is ScanUiState.Downloading -> DownloadingContent(
                        currentFile = state.currentFile,
                        downloadedBytes = state.downloadedBytes,
                        totalBytes = state.totalBytes,
                        fileIndex = state.fileIndex,
                        fileCount = state.fileCount,
                        onDismiss = onDismiss
                    )
                    is ScanUiState.ModelMissing -> ModelMissingContent(
                        onDownload = { viewModel.startDownload() },
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

@Composable
private fun CameraContent(
    onPhotoCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            bindToLifecycle(lifecycleOwner)
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Close button
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )

        // Hint text
        Text(
            text = stringResource(R.string.camera_hint),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Capture button
        IconButton(
            onClick = {
                val photoFile = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                cameraController.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            onPhotoCaptured(photoFile.absolutePath)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraScan", "Capture failed", exception)
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.capture),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun LoadingContent(message: String, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        CloseButton(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SuccessContent(
    expression: String,
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
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = stringResource(R.string.recognized_expression),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = expression,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
                Button(onClick = onUse) {
                    Text(stringResource(R.string.use_expression))
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
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
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
        }
    }
}

@Composable
private fun PermissionDeniedContent(onDismiss: () -> Unit) {
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
            Text(
                text = stringResource(R.string.camera_permission_needed),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModelMissingContent(onDownload: () -> Unit, onDismiss: () -> Unit) {
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
            Text(
                text = stringResource(R.string.model_not_found),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.model_download_description),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = onDownload) {
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
                    text = "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}",
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
