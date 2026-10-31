package app.clearmark.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.clearmark.clearMarkApp
import app.clearmark.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StartUiState(
    /** Hinweis „nur eigene Inhalte“ anzeigen (beim ersten Start oder auf Wunsch). */
    val showNotice: Boolean = false,
    /** true, solange der Hinweis noch nie bestätigt wurde. */
    val firstRun: Boolean = false,
)

class StartViewModel(private val settings: SettingsRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(
        settings.isNoticeAccepted().let { accepted -> StartUiState(showNotice = !accepted, firstRun = !accepted) },
    )
    val uiState: StateFlow<StartUiState> = _uiState.asStateFlow()

    fun onNoticeAccepted() {
        settings.setNoticeAccepted()
        _uiState.update { it.copy(showNotice = false, firstRun = false) }
    }

    fun onShowNotice() {
        _uiState.update { it.copy(showNotice = true) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { StartViewModel(clearMarkApp().container.settingsRepository) }
        }
    }
}
