package app.clearmark.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.clearmark.data.ffmpeg.FfmpegOutcome
import app.clearmark.data.ffmpeg.FfmpegRunner
import app.clearmark.data.ffmpeg.NativeLibraryUnavailableException
import app.clearmark.domain.AudioMode
import app.clearmark.domain.ExportCommand
import app.clearmark.domain.FfmpegCommandBuilder
import app.clearmark.domain.FfmpegFailureClassifier
import app.clearmark.domain.StorageEstimator
import app.clearmark.domain.model.AppError
import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.RemovalMethod
import app.clearmark.domain.model.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Auftrag für einen Export. [logo] ist in Pixeln des codierten Videobildes angegeben. */
data class ExportRequest(
    val sourceUri: Uri,
    val video: VideoInfo,
    val logo: PixelRect,
    val method: RemovalMethod,
)

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Succeeded(val outputPath: String) : ExportState
    data class Failed(val error: AppError) : ExportState
}

/**
 * Führt Exporte app-weit aus (unabhängig vom Screen) und stellt den Zustand als StateFlow bereit.
 * Ein Vordergrunddienst ([ExportService]) hält den Prozess am Leben, solange ein Export läuft.
 */
class ExportManager(
    context: Context,
    private val runner: FfmpegRunner,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = _state.value is ExportState.Running

    fun start(request: ExportRequest) {
        if (isRunning) return
        ExportNotifications.cancelResult(appContext)
        _state.value = ExportState.Running(0f)
        startForegroundService()
        job = scope.launch(Dispatchers.IO) {
            val result = try {
                runExport(request)
            } catch (e: CancellationException) {
                _state.value = ExportState.Idle
                throw e
            } catch (e: Exception) {
                ExportState.Failed(AppError.Unexpected(e.message))
            }
            if (result !is ExportState.Idle) notifyIfInBackground(result)
            _state.value = result
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ExportState.Idle
    }

    /** Ergebnis wurde von der Oberfläche übernommen. */
    fun consumeResult() {
        if (_state.value is ExportState.Succeeded || _state.value is ExportState.Failed) {
            _state.value = ExportState.Idle
        }
    }

    private suspend fun runExport(request: ExportRequest): ExportState {
        val dir = File(appContext.cacheDir, EXPORT_DIR).apply { mkdirs() }
        // Ältere Exporte aufräumen, damit sich der Cache nicht füllt.
        dir.listFiles()?.forEach { it.delete() }

        if (dir.usableSpace < StorageEstimator.requiredBytes(request.video)) {
            return ExportState.Failed(AppError.NotEnoughStorage)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(dir, "ClearMark_$timestamp.mp4")

        var audioMode = AudioMode.COPY
        while (true) {
            val input = try {
                runner.inputFor(request.sourceUri)
            } catch (e: NativeLibraryUnavailableException) {
                return ExportState.Failed(AppError.UnsupportedDevice)
            } catch (e: Exception) {
                return ExportState.Failed(AppError.VideoUnreadable)
            }

            val arguments = FfmpegCommandBuilder.build(
                ExportCommand(
                    input = input,
                    output = output.absolutePath,
                    video = request.video,
                    logo = request.logo,
                    method = request.method,
                    audioMode = audioMode,
                ),
            )

            val outcome = runner.execute(arguments, request.video.durationMs) { progress ->
                if (_state.value is ExportState.Running) _state.value = ExportState.Running(progress)
            }

            when (outcome) {
                FfmpegOutcome.Success -> return if (output.length() > 0) {
                    ExportState.Succeeded(output.absolutePath)
                } else {
                    ExportState.Failed(AppError.FfmpegFailed(null))
                }

                FfmpegOutcome.Cancelled -> {
                    output.delete()
                    return ExportState.Idle
                }

                FfmpegOutcome.NativeLibraryUnavailable -> return ExportState.Failed(AppError.UnsupportedDevice)

                is FfmpegOutcome.Failure -> {
                    output.delete()
                    when (FfmpegFailureClassifier.classify(outcome.log)) {
                        FfmpegFailureClassifier.Kind.AUDIO_INCOMPATIBLE ->
                            if (audioMode == AudioMode.COPY) {
                                // Ton passt nicht in MP4: einmal mit AAC-Ton wiederholen.
                                audioMode = AudioMode.AAC
                                _state.value = ExportState.Running(0f)
                                continue
                            } else {
                                return ExportState.Failed(AppError.FfmpegFailed(FfmpegFailureClassifier.summarize(outcome.log)))
                            }
                        FfmpegFailureClassifier.Kind.NO_SPACE -> return ExportState.Failed(AppError.NotEnoughStorage)
                        FfmpegFailureClassifier.Kind.UNREADABLE_INPUT -> return ExportState.Failed(AppError.VideoUnreadable)
                        FfmpegFailureClassifier.Kind.OTHER ->
                            return ExportState.Failed(AppError.FfmpegFailed(FfmpegFailureClassifier.summarize(outcome.log)))
                    }
                }
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(appContext, ExportService::class.java)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            // Start aus dem Hintergrund nicht erlaubt: Der Export läuft trotzdem, nur ohne Dienst.
        }
    }

    private suspend fun notifyIfInBackground(result: ExportState) = withContext(Dispatchers.Main) {
        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!inForeground) {
            ExportNotifications.showResult(appContext, success = result is ExportState.Succeeded)
        }
    }

    companion object {
        const val EXPORT_DIR = "exports"
    }
}
