package app.clearmark.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.clearmark.clearMarkApp
import app.clearmark.data.video.VideoInfoRepository
import app.clearmark.domain.CoordinateMapper
import app.clearmark.domain.RectEditor
import app.clearmark.domain.model.AppError
import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.RemovalMethod
import app.clearmark.domain.model.VideoInfo
import app.clearmark.ui.navigation.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EditorUiState(
    val loading: Boolean = true,
    val loadError: AppError? = null,
    val video: VideoInfo? = null,
    /** Markierung relativ zum angezeigten Bild. */
    val rect: NormalizedRect = NormalizedRect.Default,
    val method: RemovalMethod = RemovalMethod.DELOGO,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val previewFailed: Boolean = false,
) {
    /** Markierung in Pixeln des angezeigten Bildes (zur Info für den Nutzer). */
    val displayPixels: PixelRect?
        get() = video?.let { CoordinateMapper.displayRectToDisplayPixels(rect, it) }
}

class EditorViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val videoInfoRepository: VideoInfoRepository,
) : ViewModel() {

    private val sourceUri: Uri? = savedStateHandle.get<String>(Routes.ARG_URI)?.let(Uri::parse)

    private val _uiState = MutableStateFlow(
        EditorUiState(
            rect = restoreRect() ?: NormalizedRect.Default,
            method = savedStateHandle.get<String>(KEY_METHOD)
                ?.let { name -> RemovalMethod.entries.firstOrNull { it.name == name } }
                ?: RemovalMethod.DELOGO,
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying, positionMs = currentPosition) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && duration > 0) {
                    _uiState.update { it.copy(durationMs = duration) }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val par = videoSize.pixelWidthHeightRatio
                if (par > 0f && par.isFinite()) {
                    _uiState.update { state ->
                        state.copy(video = state.video?.copy(pixelAspectRatio = par))
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _uiState.update { it.copy(previewFailed = true, isPlaying = false) }
            }
        })
    }

    init {
        loadVideo()
        // Abspielposition für die Seekbar aktuell halten.
        viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    _uiState.update { it.copy(positionMs = player.currentPosition) }
                }
                delay(POSITION_UPDATE_MS)
            }
        }
    }

    private fun loadVideo() {
        val uri = sourceUri
        if (uri == null) {
            _uiState.update { it.copy(loading = false, loadError = AppError.VideoUnreadable) }
            return
        }
        viewModelScope.launch {
            try {
                val info = videoInfoRepository.load(uri)
                _uiState.update {
                    it.copy(
                        loading = false,
                        video = info,
                        durationMs = if (it.durationMs > 0) it.durationMs else info.durationMs,
                    )
                }
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, loadError = AppError.VideoUnreadable) }
            }
        }
    }

    fun onRectChanged(rect: NormalizedRect) {
        val sanitized = RectEditor.sanitize(rect)
        _uiState.update { it.copy(rect = sanitized) }
        savedStateHandle[KEY_RECT] = floatArrayOf(sanitized.left, sanitized.top, sanitized.right, sanitized.bottom)
    }

    fun onResetRect() = onRectChanged(NormalizedRect.Default)

    fun onMethodSelected(method: RemovalMethod) {
        _uiState.update { it.copy(method = method) }
        savedStateHandle[KEY_METHOD] = method.name
    }

    fun onPlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun onSeek(positionMs: Long) {
        player.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
    }

    fun pausePlayback() {
        player.pause()
    }

    private fun restoreRect(): NormalizedRect? {
        val values = savedStateHandle.get<FloatArray>(KEY_RECT) ?: return null
        if (values.size != 4) return null
        return RectEditor.sanitize(NormalizedRect(values[0], values[1], values[2], values[3]))
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    companion object {
        private const val KEY_RECT = "rect"
        private const val KEY_METHOD = "method"
        private const val POSITION_UPDATE_MS = 200L

        val Factory = viewModelFactory {
            initializer {
                val app = clearMarkApp()
                EditorViewModel(
                    application = app,
                    savedStateHandle = createSavedStateHandle(),
                    videoInfoRepository = app.container.videoInfoRepository,
                )
            }
        }
    }
}
