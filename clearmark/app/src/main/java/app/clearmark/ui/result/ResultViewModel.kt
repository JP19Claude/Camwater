package app.clearmark.ui.result

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import app.clearmark.clearMarkApp
import app.clearmark.data.media.MediaStoreRepository
import app.clearmark.data.media.NotEnoughStorageException
import app.clearmark.data.media.ShareRepository
import app.clearmark.domain.model.AppError
import app.clearmark.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Saved : SaveState
}

data class ResultUiState(
    val fileAvailable: Boolean = true,
    val saveState: SaveState = SaveState.Idle,
    /** Einmalige Meldung (Snackbar). */
    val error: AppError? = null,
)

class ResultViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val mediaStoreRepository: MediaStoreRepository,
    private val shareRepository: ShareRepository,
) : ViewModel() {

    private val file: File? = savedStateHandle.get<String>(Routes.ARG_PATH)?.let(::File)

    private val _uiState = MutableStateFlow(
        ResultUiState(fileAvailable = file?.isFile == true).let { state ->
            if (state.fileAvailable) state else state.copy(error = AppError.ResultMissing)
        },
    )
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        file?.takeIf { it.isFile }?.let { existing ->
            setMediaItem(MediaItem.fromUri(Uri.fromFile(existing)))
            prepare()
        }
    }

    val needsLegacyStoragePermission: Boolean
        get() = mediaStoreRepository.needsLegacyStoragePermission

    fun onSave() {
        val target = file
        if (target == null || !target.isFile) {
            _uiState.update { it.copy(fileAvailable = false, error = AppError.ResultMissing) }
            return
        }
        if (_uiState.value.saveState != SaveState.Idle) return
        _uiState.update { it.copy(saveState = SaveState.Saving) }
        viewModelScope.launch {
            try {
                mediaStoreRepository.saveVideo(target)
                _uiState.update { it.copy(saveState = SaveState.Saved) }
            } catch (e: NotEnoughStorageException) {
                _uiState.update { it.copy(saveState = SaveState.Idle, error = AppError.NotEnoughStorage) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saveState = SaveState.Idle, error = AppError.SaveFailed) }
            }
        }
    }

    /** Liefert den Teilen-Intent oder null (dann wird ein Fehler gesetzt). */
    fun createShareIntent(): Intent? {
        val target = file
        if (target == null || !target.isFile) {
            _uiState.update { it.copy(fileAvailable = false, error = AppError.ResultMissing) }
            return null
        }
        return try {
            shareRepository.createShareIntent(target)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = AppError.ShareFailed) }
            null
        }
    }

    fun onShareFailed() {
        _uiState.update { it.copy(error = AppError.ShareFailed) }
    }

    fun onStoragePermissionDenied() {
        _uiState.update { it.copy(error = AppError.SaveFailed) }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun pausePlayback() {
        player.pause()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = clearMarkApp()
                ResultViewModel(
                    application = app,
                    savedStateHandle = createSavedStateHandle(),
                    mediaStoreRepository = app.container.mediaStoreRepository,
                    shareRepository = app.container.shareRepository,
                )
            }
        }
    }
}
