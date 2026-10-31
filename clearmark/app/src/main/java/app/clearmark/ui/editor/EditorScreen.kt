package app.clearmark.ui.editor

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clearmark.R
import app.clearmark.domain.model.AppError
import app.clearmark.ui.common.message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pausePlayback() }

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
                            EditorPanel(state, viewModel)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        videoArea(Modifier.weight(1f).fillMaxWidth())
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            EditorPanel(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EditorPanel(state: EditorUiState, viewModel: EditorViewModel) {
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
        MethodSelector(selected = state.method, onSelected = viewModel::onMethodSelected)
        Text(
            text = stringResource(state.method.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
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
