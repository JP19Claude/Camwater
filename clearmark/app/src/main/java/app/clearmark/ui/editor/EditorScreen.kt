package app.clearmark.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clearmark.R
import app.clearmark.domain.model.AppError
import app.clearmark.domain.model.RemovalMethod
import app.clearmark.ui.common.details
import app.clearmark.ui.common.message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onExported: (String) -> Unit,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pausePlayback() }

    // Fertiges Video: zum Ergebnis-Screen wechseln (einmalig).
    LaunchedEffect(state.exportedPath) {
        state.exportedPath?.let { path ->
            viewModel.onResultShown()
            onExported(path)
        }
    }

    // Ab Android 13 einmalig nach der Benachrichtigungs-Berechtigung fragen (Fortschritt im Hintergrund).
    var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onExport() }
    val startExport: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission && !notificationPermissionAsked) {
            notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onExport()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::onResetRect, enabled = state.video != null) {
                        Text(stringResource(R.string.editor_reset_rect))
                    }
                },
            )
        },
    ) { padding ->
        val video = state.video
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.loadError != null || video == null -> LoadErrorContent(
                message = (state.loadError ?: AppError.VideoUnreadable).message(),
                onBack = onBack,
                modifier = Modifier.padding(padding),
            )

            else -> BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val landscape = maxWidth > maxHeight
                val videoArea: @Composable (Modifier) -> Unit = { areaModifier ->
                    VideoWithOverlay(
                        player = viewModel.player,
                        aspectRatio = video.displayAspectRatio,
                        rect = state.rect,
                        onRectChange = viewModel::onRectChanged,
                        modifier = areaModifier.background(Color.Black),
                    )
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        videoArea(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EditorPanel(state, viewModel, startExport)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        videoArea(Modifier.weight(1f).fillMaxWidth())
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            EditorPanel(state, viewModel, startExport)
                        }
                    }
                }
            }
        }
    }

    state.exportProgress?.let { progress ->
        ExportProgressDialog(progress = progress, onCancel = viewModel::onCancelExport)
    }
    state.exportError?.let { error ->
        ExportErrorDialog(error = error, onDismiss = viewModel::onExportErrorDismissed)
    }
}

@Composable
private fun ColumnScope.EditorPanel(state: EditorUiState, viewModel: EditorViewModel, onExport: () -> Unit) {
    PlaybackControls(
        isPlaying = state.isPlaying,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        onPlayPause = viewModel::onPlayPause,
        onSeek = viewModel::onSeek,
    )
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.previewFailed) {
            Text(
                text = stringResource(R.string.editor_preview_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.displayPixels?.let { px ->
            Text(
                text = stringResource(R.string.editor_selection_info, px.width, px.height, px.x, px.y),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.editor_method), style = MaterialTheme.typography.titleSmall)
        MethodSelector(
            selected = state.method,
            onSelected = viewModel::onMethodSelected,
            enabled = !state.isExporting,
        )
        Text(
            text = stringResource(state.method.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.method == RemovalMethod.CROP) {
            val zoom = state.cropZoomPercent
            Text(
                text = if (zoom != null) {
                    stringResource(R.string.editor_crop_zoom, zoom)
                } else {
                    stringResource(R.string.error_selection_too_large)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (zoom != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onExport,
            enabled = !state.isExporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.editor_export))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ExportProgressDialog(progress: Float, onCancel: () -> Unit) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.export_progress, percent),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ExportErrorDialog(error: AppError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error.message())
                error.details()?.let { details ->
                    Text(
                        text = stringResource(R.string.error_details, details),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun LoadErrorContent(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.editor_pick_other)) }
    }
}
