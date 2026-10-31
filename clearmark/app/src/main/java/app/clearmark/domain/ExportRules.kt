package app.clearmark.domain

import app.clearmark.domain.model.VideoInfo

/** Schätzt den Speicherbedarf eines Exports, um früh mit einer klaren Meldung abzubrechen. */
object StorageEstimator {

    /** Puffer für Metadaten, temporäre Daten und das System. */
    const val SAFETY_BYTES = 50L * 1024 * 1024

    /** Annahme für CRF 18, falls die Dateigröße unbekannt ist (≈ 40 Mbit/s). */
    const val FALLBACK_BYTES_PER_SECOND = 5L * 1024 * 1024

    fun requiredBytes(video: VideoInfo): Long {
        val size = video.sizeBytes
        val estimate = if (size != null && size > 0) {
            // CRF 18 ist hochwertig; das Ergebnis kann größer als das Original werden.
            size + size / 2
        } else {
            (video.durationMs.coerceAtLeast(0L) / 1000L + 1L) * FALLBACK_BYTES_PER_SECOND
        }
        return estimate + SAFETY_BYTES
    }
}

/** Ordnet FFmpeg-Logausgaben einer verständlichen Fehlerursache zu. */
object FfmpegFailureClassifier {

    enum class Kind {
        /** Speicher voll. */
        NO_SPACE,

        /** Eingabe defekt, nicht lesbar oder ohne Videospur. */
        UNREADABLE_INPUT,

        /** Tonspur passt nicht in MP4 (z. B. Vorbis aus WebM) – Rückfall auf AAC möglich. */
        AUDIO_INCOMPATIBLE,

        /** Sonstiger Fehler. */
        OTHER,
    }

    private val noSpace = listOf("no space left on device", "enospc", "disk full")

    private val unreadable = listOf(
        "invalid data found when processing input",
        "moov atom not found",
        "error opening input",
        "could not find codec parameters",
        "does not contain any stream",
        "stream map '0:v:0' matches no streams",
        "no such file or directory",
        "permission denied",
    )

    private val audioIncompatible = listOf(
        "could not find tag for codec",
        "codec not currently supported in container",
    )

    fun classify(log: String?): Kind {
        val text = log?.lowercase() ?: return Kind.OTHER
        return when {
            noSpace.any { it in text } -> Kind.NO_SPACE
            audioIncompatible.any { it in text } -> Kind.AUDIO_INCOMPATIBLE
            unreadable.any { it in text } -> Kind.UNREADABLE_INPUT
            else -> Kind.OTHER
        }
    }

    /** Die letzten aussagekräftigen Logzeilen für die Fehleranzeige. */
    fun summarize(log: String?, maxLines: Int = 4): String? = log
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        ?.takeLast(maxLines)
        ?.joinToString("\n")
        ?.takeIf { it.isNotEmpty() }
}
