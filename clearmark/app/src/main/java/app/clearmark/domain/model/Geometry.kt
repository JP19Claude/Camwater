package app.clearmark.domain.model

/** Rechteck in Ansichtskoordinaten (Bildschirmpixel innerhalb des Player-Containers). */
data class ViewRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Rechteck relativ zum sichtbaren Videobild, alle Werte zwischen 0 und 1.
 * Bezugssystem ist das Bild so, wie es angezeigt wird (also nach Anwendung der Rotation).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        const val DEFAULT_WIDTH = 0.18f
        const val DEFAULT_HEIGHT = 0.08f
        const val DEFAULT_MARGIN = 0.02f

        /** Startposition: unten rechts, ca. 18 % breit und 8 % hoch. */
        val Default = NormalizedRect(
            left = 1f - DEFAULT_MARGIN - DEFAULT_WIDTH,
            top = 1f - DEFAULT_MARGIN - DEFAULT_HEIGHT,
            right = 1f - DEFAULT_MARGIN,
            bottom = 1f - DEFAULT_MARGIN,
        )
    }
}

/** Rechteck in echten Videopixeln. */
data class PixelRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}
