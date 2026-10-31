package app.clearmark.domain.model

/** Fehler, die dem Nutzer verständlich angezeigt werden. */
sealed interface AppError {
    /** Das Video kann nicht geöffnet oder gelesen werden. */
    data object VideoUnreadable : AppError

    /** Auf dem Gerät ist zu wenig freier Speicher. */
    data object NotEnoughStorage : AppError

    /** FFmpeg hat mit einem Fehler abgebrochen. [details] enthält die letzten Logzeilen. */
    data class FfmpegFailed(val details: String?) : AppError

    /** Die Markierung ist für „Zuschneiden“ zu groß (vom Bild bliebe zu wenig übrig). */
    data object SelectionTooLarge : AppError

    /** Die nativen FFmpeg-Bibliotheken laufen auf diesem Gerät nicht (z. B. 32-Bit-Gerät). */
    data object UnsupportedDevice : AppError

    /** Das Ergebnis ist nicht mehr vorhanden (z. B. Cache geleert). */
    data object ResultMissing : AppError

    /** Speichern in der Galerie ist fehlgeschlagen. */
    data object SaveFailed : AppError

    /** Keine App zum Teilen gefunden. */
    data object ShareFailed : AppError

    /** Unerwarteter Fehler. */
    data class Unexpected(val message: String?) : AppError
}
