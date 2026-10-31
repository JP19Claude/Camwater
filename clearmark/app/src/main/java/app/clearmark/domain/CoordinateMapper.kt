package app.clearmark.domain

import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.VideoInfo
import app.clearmark.domain.model.ViewRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Rechnet die Markierung zwischen Player-Ansicht, normierten Koordinaten und echten
 * Videopixeln um.
 *
 * Ablauf: Ansicht (Bildschirmpixel, mit Letterboxing) → normiert im angezeigten Bild (0..1)
 * → normiert im codierten Bild (Rotation rückgängig gemacht) → Videopixel.
 */
object CoordinateMapper {

    /** Toleranz gegen Float-Rundungsfehler beim Runden auf ganze Pixel. */
    private const val PIXEL_EPSILON = 1e-3

    /**
     * Berechnet, wo das Video innerhalb des Containers liegt, wenn es mit erhaltenem
     * Seitenverhältnis eingepasst wird (Letterboxing oben/unten oder Pillarboxing links/rechts).
     */
    fun fitContent(containerWidth: Float, containerHeight: Float, contentAspectRatio: Float): ViewRect {
        if (containerWidth <= 0f || containerHeight <= 0f ||
            contentAspectRatio <= 0f || !contentAspectRatio.isFinite()
        ) {
            return ViewRect(0f, 0f, containerWidth.coerceAtLeast(0f), containerHeight.coerceAtLeast(0f))
        }
        val containerAspect = containerWidth / containerHeight
        return if (contentAspectRatio > containerAspect) {
            // Video ist breiter als der Container: volle Breite, Balken oben und unten.
            val height = containerWidth / contentAspectRatio
            ViewRect(0f, (containerHeight - height) / 2f, containerWidth, height)
        } else {
            // Video ist schmaler: volle Höhe, Balken links und rechts.
            val width = containerHeight * contentAspectRatio
            ViewRect((containerWidth - width) / 2f, 0f, width, containerHeight)
        }
    }

    /** Ansichtsrechteck → normiert relativ zum sichtbaren Videobild, auf das Bild begrenzt. */
    fun viewToNormalized(rect: ViewRect, content: ViewRect): NormalizedRect {
        if (content.width <= 0f || content.height <= 0f) return NormalizedRect(0f, 0f, 0f, 0f)
        fun nx(x: Float) = ((x - content.left) / content.width).coerceIn(0f, 1f)
        fun ny(y: Float) = ((y - content.top) / content.height).coerceIn(0f, 1f)
        return NormalizedRect(nx(rect.left), ny(rect.top), nx(rect.right), ny(rect.bottom))
    }

    /** Normiertes Rechteck → Ansichtskoordinaten innerhalb des Containers. */
    fun normalizedToView(rect: NormalizedRect, content: ViewRect): ViewRect = ViewRect(
        left = content.left + rect.left * content.width,
        top = content.top + rect.top * content.height,
        width = rect.width * content.width,
        height = rect.height * content.height,
    )

    /**
     * Wandelt ein Rechteck aus dem angezeigten Bild in das codierte Bild um, d. h. macht die
     * Rotation rückgängig. [rotationDegrees] ist die Drehung im Uhrzeigersinn, mit der das
     * codierte Bild angezeigt wird.
     *
     * Bei 90° wird ein codierter Punkt (s, t) zu (1 - t, s) im angezeigten Bild,
     * umgekehrt also (u, v) → (v, 1 - u).
     */
    fun displayToCoded(rect: NormalizedRect, rotationDegrees: Int): NormalizedRect =
        when (VideoInfo.normalizeRotation(rotationDegrees)) {
            90 -> NormalizedRect(
                left = rect.top,
                top = 1f - rect.right,
                right = rect.bottom,
                bottom = 1f - rect.left,
            )
            180 -> NormalizedRect(
                left = 1f - rect.right,
                top = 1f - rect.bottom,
                right = 1f - rect.left,
                bottom = 1f - rect.top,
            )
            270 -> NormalizedRect(
                left = 1f - rect.bottom,
                top = rect.left,
                right = 1f - rect.top,
                bottom = rect.right,
            )
            else -> rect
        }

    /** Umkehrung von [displayToCoded]. */
    fun codedToDisplay(rect: NormalizedRect, rotationDegrees: Int): NormalizedRect =
        displayToCoded(rect, 360 - VideoInfo.normalizeRotation(rotationDegrees))

    /**
     * Normiertes Rechteck → ganze Pixel in einem Bild der Größe [frameWidth] × [frameHeight].
     * Es wird nach außen gerundet, damit das Logo sicher vollständig abgedeckt ist.
     */
    fun toPixels(rect: NormalizedRect, frameWidth: Int, frameHeight: Int): PixelRect {
        val left = floor(rect.left.toDouble() * frameWidth + PIXEL_EPSILON).toInt().coerceIn(0, frameWidth)
        val top = floor(rect.top.toDouble() * frameHeight + PIXEL_EPSILON).toInt().coerceIn(0, frameHeight)
        val right = ceil(rect.right.toDouble() * frameWidth - PIXEL_EPSILON).toInt().coerceIn(left, frameWidth)
        val bottom = ceil(rect.bottom.toDouble() * frameHeight - PIXEL_EPSILON).toInt().coerceIn(top, frameHeight)
        return PixelRect(left, top, right - left, bottom - top)
    }

    /** Markierung (angezeigtes Bild, normiert) → Pixel im codierten Videobild, wie FFmpeg es sieht. */
    fun displayRectToVideoPixels(rect: NormalizedRect, video: VideoInfo): PixelRect =
        toPixels(displayToCoded(rect, video.rotation), video.codedWidth, video.codedHeight)

    /** Markierung in Pixeln des angezeigten Bildes (für die Anzeige im Editor). */
    fun displayRectToDisplayPixels(rect: NormalizedRect, video: VideoInfo): PixelRect =
        toPixels(rect, video.displayWidth, video.displayHeight)
}
