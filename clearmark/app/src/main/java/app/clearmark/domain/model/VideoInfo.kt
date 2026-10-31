package app.clearmark.domain.model

/**
 * Technische Eckdaten eines Videos.
 *
 * [codedWidth] und [codedHeight] sind die Maße der gespeicherten (codierten) Bilder.
 * [rotationDegrees] gibt an, um wie viel Grad im Uhrzeigersinn der Player das Bild
 * für die Anzeige dreht (Android-Konvention, z. B. 90 bei Hochkant-Handyvideos).
 */
data class VideoInfo(
    val codedWidth: Int,
    val codedHeight: Int,
    val rotationDegrees: Int = 0,
    val durationMs: Long = 0L,
    val hasAudio: Boolean = true,
    val sizeBytes: Long? = null,
    /** Pixel-Seitenverhältnis (SAR). 1 bei quadratischen Pixeln, wie bei fast allen Handyvideos. */
    val pixelAspectRatio: Float = 1f,
) {
    val rotation: Int get() = normalizeRotation(rotationDegrees)

    val isQuarterTurn: Boolean get() = rotation == 90 || rotation == 270

    /** Breite des angezeigten Bildes in Pixeln (nach Rotation). */
    val displayWidth: Int get() = if (isQuarterTurn) codedHeight else codedWidth

    /** Höhe des angezeigten Bildes in Pixeln (nach Rotation). */
    val displayHeight: Int get() = if (isQuarterTurn) codedWidth else codedHeight

    /** Seitenverhältnis Breite/Höhe, wie das Video angezeigt wird. */
    val displayAspectRatio: Float
        get() {
            val par = if (pixelAspectRatio > 0f && pixelAspectRatio.isFinite()) pixelAspectRatio else 1f
            val w = codedWidth * par
            val h = codedHeight.toFloat()
            if (w <= 0f || h <= 0f) return 16f / 9f
            return if (isQuarterTurn) h / w else w / h
        }

    companion object {
        /** Bringt beliebige Gradzahlen auf 0, 90, 180 oder 270. */
        fun normalizeRotation(degrees: Int): Int {
            val positive = ((degrees % 360) + 360) % 360
            return ((positive + 45) / 90 * 90) % 360
        }
    }
}
