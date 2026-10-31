package app.clearmark.domain.model

/** Verfahren, mit dem das Logo entfernt wird. */
enum class RemovalMethod {
    /** FFmpeg-Filter delogo: füllt den Bereich aus den umliegenden Pixeln auf. */
    DELOGO,

    /** Bildrand mit dem Logo abschneiden und wieder auf Originalgröße skalieren. */
    CROP,

    /** Bereich mit boxblur weichzeichnen (Fallback). */
    BLUR,
}
