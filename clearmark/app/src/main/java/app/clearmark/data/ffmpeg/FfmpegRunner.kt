package app.clearmark.data.ffmpeg

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Ergebnis eines FFmpeg-Laufs. */
sealed interface FfmpegOutcome {
    data object Success : FfmpegOutcome
    data object Cancelled : FfmpegOutcome
    data class Failure(val log: String) : FfmpegOutcome

    /** Native Bibliotheken fehlen oder laufen nicht (z. B. 32-Bit-Gerät). */
    data object NativeLibraryUnavailable : FfmpegOutcome
}

/** Die native FFmpeg-Bibliothek konnte nicht geladen werden. */
class NativeLibraryUnavailableException(cause: Throwable) : Exception(cause)

/** Dünner Wrapper um FFmpegKit: Coroutine-API, Fortschritt in 0..1 und Abbrechen. */
class FfmpegRunner(private val context: Context) {

    /**
     * Liefert den Eingabeparameter für FFmpeg. Content-URIs (Photo Picker, SAF) werden über das
     * SAF-Protokoll von FFmpegKit gelesen, ohne die Datei vorher zu kopieren.
     * Jeder Parameter ist nur für einen FFmpeg-Lauf gültig.
     */
    fun inputFor(uri: Uri): String {
        if (uri.scheme == "file") return requireNotNull(uri.path)
        return try {
            FFmpegKitConfig.getSafParameterForRead(context, uri)
        } catch (e: LinkageError) {
            throw NativeLibraryUnavailableException(e)
        }
    }

    suspend fun execute(
        arguments: List<String>,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): FfmpegOutcome = suspendCancellableCoroutine { continuation ->
        val log = LogTail()
        val session: FFmpegSession = try {
            FFmpegKit.executeWithArgumentsAsync(
                arguments.toTypedArray(),
                { finished ->
                    val returnCode = finished.returnCode
                    val outcome = when {
                        ReturnCode.isSuccess(returnCode) -> FfmpegOutcome.Success
                        ReturnCode.isCancel(returnCode) -> FfmpegOutcome.Cancelled
                        else -> FfmpegOutcome.Failure(
                            buildString {
                                append(log.text())
                                finished.failStackTrace?.let { append('\n').append(it) }
                            },
                        )
                    }
                    if (continuation.isActive) continuation.resume(outcome)
                },
                { entry -> entry?.message?.let(log::append) },
                { statistics ->
                    if (durationMs > 0 && statistics != null) {
                        onProgress((statistics.time / durationMs).toFloat().coerceIn(0f, 1f))
                    }
                },
            )
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError
            continuation.resume(FfmpegOutcome.NativeLibraryUnavailable)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }

    /** Hält nur das Ende des Logs (FFmpeg kann sehr viel ausgeben). */
    private class LogTail(private val maxChars: Int = 16_000) {
        private val buffer = StringBuilder()

        fun append(text: String) = synchronized(buffer) {
            buffer.append(text)
            if (buffer.length > maxChars) buffer.delete(0, buffer.length - maxChars)
        }

        fun text(): String = synchronized(buffer) { buffer.toString() }
    }
}
