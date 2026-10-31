package app.clearmark.domain

import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.RemovalMethod
import app.clearmark.domain.model.VideoInfo
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Wie die Tonspur behandelt wird. */
enum class AudioMode {
    /** Unverändert übernehmen (Standard). */
    COPY,

    /** Nach AAC umwandeln – nur als Rückfall, wenn der Ton nicht in MP4 passt. */
    AAC,
}

/**
 * Alles, was für einen FFmpeg-Aufruf nötig ist.
 *
 * @property input Eingabe für FFmpeg (Dateipfad oder `saf:`-Parameter von FFmpegKit).
 * @property output Pfad der Ausgabedatei (.mp4).
 * @property logo Logo-Bereich in Pixeln des **codierten** Bildes (siehe [CoordinateMapper.displayRectToVideoPixels]).
 */
data class ExportCommand(
    val input: String,
    val output: String,
    val video: VideoInfo,
    val logo: PixelRect,
    val method: RemovalMethod,
    val audioMode: AudioMode = AudioMode.COPY,
)

/** Die Markierung ist so groß, dass beim Zuschneiden zu wenig vom Bild übrig bliebe. */
class SelectionTooLargeException : IllegalArgumentException("Markierung zu groß für Crop")

/**
 * Baut die FFmpeg-Argumente.
 *
 * Die Eingabe wird mit `-noautorotate` gelesen: Die Filter arbeiten dann auf dem codierten Bild
 * (deshalb rechnet [CoordinateMapper] die Markierung vorher dorthin um), und FFmpeg übernimmt die
 * Rotations-Information (Display-Matrix) unverändert in die Ausgabe. Das Video wird so nicht
 * „physisch“ gedreht, sondern behält dieselbe Rotation wie das Original.
 */
object FfmpegCommandBuilder {

    /** Abstand zum Bildrand in Pixeln; delogo braucht Nachbarpixel rund um den Bereich. */
    const val EDGE_MARGIN = 2

    /** Kleinste sinnvolle Kantenlänge für delogo. */
    const val MIN_DELOGO_SIZE = 4

    /** Kleinste Kantenlänge des weichgezeichneten Bereichs (boxblur braucht Platz für den Radius). */
    const val MIN_BLUR_SIZE = 8

    /** Kleinste Kantenlänge des Bildausschnitts beim Zuschneiden. */
    const val MIN_CROP_SIZE = 16

    /** Wie oft boxblur angewendet wird (3 Durchgänge ≈ Gauß-Weichzeichner). */
    const val BLUR_POWER = 3
    const val MAX_LUMA_RADIUS = 40
    const val MAX_CHROMA_RADIUS = 20

    const val VIDEO_CODEC = "libx264"
    const val CRF = 18
    const val PRESET = "veryfast"

    fun build(command: ExportCommand): List<String> {
        val width = command.video.codedWidth
        val height = command.video.codedHeight
        require(width > 0 && height > 0) { "Ungültige Videogröße ${width}x$height" }

        val args = mutableListOf(
            "-y",
            "-hide_banner",
            "-noautorotate",
            "-i", command.input,
        )

        when (command.method) {
            RemovalMethod.DELOGO -> {
                val area = sanitizeDelogoRect(command.logo, width, height)
                args += listOf("-map", "0:v:0", "-vf", delogoFilter(area) + evenSizeSuffix(width, height))
            }
            RemovalMethod.CROP -> {
                val window = cropWindow(command.logo, width, height) ?: throw SelectionTooLargeException()
                args += listOf("-map", "0:v:0", "-vf", cropFilter(window, width, height, command.video.pixelAspectRatio))
            }
            RemovalMethod.BLUR -> {
                val area = blurArea(command.logo, width, height)
                // Bewusst -vf statt -filter_complex: Nur so bleibt die Ausgabe-Videospur mit der
                // Eingabespur verknüpft und FFmpeg übernimmt die Rotations-Information.
                args += listOf("-map", "0:v:0", "-vf", blurFilter(area, width, height))
            }
        }

        // Alle Tonspuren (falls vorhanden) übernehmen; das ? verhindert einen Fehler ohne Ton.
        args += listOf("-map", "0:a?")

        args += listOf(
            "-c:v", VIDEO_CODEC,
            "-preset", PRESET,
            "-crf", CRF.toString(),
            "-pix_fmt", "yuv420p",
        )
        args += when (command.audioMode) {
            AudioMode.COPY -> listOf("-c:a", "copy")
            AudioMode.AAC -> listOf("-c:a", "aac", "-b:a", "192k")
        }
        args += listOf(
            // Globale Metadaten (Aufnahmedatum, Ort, …) übernehmen.
            "-map_metadata", "0",
            "-movflags", "+faststart",
            command.output,
        )
        return args
    }

    /**
     * Begrenzt den delogo-Bereich so, dass er vollständig im Bild liegt und rundherum mindestens
     * [EDGE_MARGIN] Pixel Abstand zum Rand hat (delogo interpoliert aus den Pixeln direkt außerhalb).
     */
    fun sanitizeDelogoRect(logo: PixelRect, frameWidth: Int, frameHeight: Int): PixelRect {
        val m = EDGE_MARGIN
        val minSize = MIN_DELOGO_SIZE
        require(frameWidth >= 2 * m + minSize && frameHeight >= 2 * m + minSize) {
            "Video zu klein für delogo"
        }
        val x = logo.x.coerceIn(m, frameWidth - m - minSize)
        val y = logo.y.coerceIn(m, frameHeight - m - minSize)
        val right = logo.right.coerceIn(x + minSize, frameWidth - m)
        val bottom = logo.bottom.coerceIn(y + minSize, frameHeight - m)
        return PixelRect(x, y, right - x, bottom - y)
    }

    fun delogoFilter(area: PixelRect): String =
        "delogo=x=${area.x}:y=${area.y}:w=${area.width}:h=${area.height}"

    // --- Crop ---

    /**
     * Sucht den größten Bildausschnitt mit dem Seitenverhältnis des Videos, der das Logo
     * (plus [EDGE_MARGIN]) nicht enthält. Geprüft werden die vier Möglichkeiten: Bereich oberhalb,
     * unterhalb, links oder rechts des Logos behalten. Der Ausschnitt wird quer zur Schnittrichtung
     * zentriert. Alle Werte sind gerade (yuv420p). Liefert null, wenn nichts Sinnvolles übrig bleibt.
     */
    fun cropWindow(logo: PixelRect, frameWidth: Int, frameHeight: Int): PixelRect? {
        if (frameWidth < MIN_CROP_SIZE || frameHeight < MIN_CROP_SIZE) return null
        val m = EDGE_MARGIN
        val logoLeft = (logo.x - m).coerceIn(0, frameWidth)
        val logoTop = (logo.y - m).coerceIn(0, frameHeight)
        val logoRight = (logo.right + m).coerceIn(0, frameWidth)
        val logoBottom = (logo.bottom + m).coerceIn(0, frameHeight)
        val aspect = frameWidth.toDouble() / frameHeight

        val candidates = listOfNotNull(
            horizontalBand(0, logoTop, frameWidth, aspect), // oben behalten
            horizontalBand(logoBottom, frameHeight, frameWidth, aspect), // unten behalten
            verticalBand(0, logoLeft, frameHeight, aspect), // links behalten
            verticalBand(logoRight, frameWidth, frameHeight, aspect), // rechts behalten
        )
        return candidates.maxByOrNull { it.width.toLong() * it.height }
    }

    /** Vergrößerung in Prozent, die beim Zuschneiden entsteht (für die Anzeige im Editor). */
    fun cropZoomPercent(window: PixelRect, frameWidth: Int): Int =
        ((frameWidth.toDouble() / window.width - 1.0) * 100.0).roundToInt().coerceAtLeast(0)

    fun cropFilter(window: PixelRect, frameWidth: Int, frameHeight: Int, pixelAspectRatio: Float = 1f): String =
        "crop=${window.width}:${window.height}:${window.x}:${window.y}," +
            "scale=${floorEven(frameWidth)}:${floorEven(frameHeight)}:flags=lanczos," +
            "setsar=${formatSar(pixelAspectRatio)}"

    private fun horizontalBand(start: Int, end: Int, frameWidth: Int, aspect: Double): PixelRect? {
        val y = ceilEven(start)
        val height = floorEven(end - y)
        if (height < MIN_CROP_SIZE) return null
        val width = floorEven(min(frameWidth.toDouble(), height * aspect).toInt())
        if (width < MIN_CROP_SIZE) return null
        return PixelRect(floorEven((frameWidth - width) / 2), y, width, height)
    }

    private fun verticalBand(start: Int, end: Int, frameHeight: Int, aspect: Double): PixelRect? {
        val x = ceilEven(start)
        val width = floorEven(end - x)
        if (width < MIN_CROP_SIZE) return null
        val height = floorEven(min(frameHeight.toDouble(), width / aspect).toInt())
        if (height < MIN_CROP_SIZE) return null
        return PixelRect(x, floorEven((frameHeight - height) / 2), width, height)
    }

    private fun formatSar(pixelAspectRatio: Float): String {
        if (!pixelAspectRatio.isFinite() || pixelAspectRatio <= 0f || pixelAspectRatio == 1f) return "1"
        return String.format(Locale.US, "%.6f", pixelAspectRatio).trimEnd('0').trimEnd('.')
    }

    // --- Blur ---

    /**
     * Weichzeichnungsbereich: im Bild, gerade Koordinaten und Maße (yuv420p) und mindestens
     * [MIN_BLUR_SIZE] groß. Ein Randabstand ist hier nicht nötig.
     */
    fun blurArea(logo: PixelRect, frameWidth: Int, frameHeight: Int): PixelRect {
        val maxX = floorEven(frameWidth)
        val maxY = floorEven(frameHeight)
        require(maxX >= MIN_BLUR_SIZE && maxY >= MIN_BLUR_SIZE) { "Video zu klein für Blur" }
        var left = floorEven(logo.x.coerceIn(0, maxX))
        var top = floorEven(logo.y.coerceIn(0, maxY))
        var right = ceilEven(logo.right.coerceIn(0, maxX)).coerceAtMost(maxX)
        var bottom = ceilEven(logo.bottom.coerceIn(0, maxY)).coerceAtMost(maxY)
        if (right - left < MIN_BLUR_SIZE) {
            right = min(maxX, max(right, left + MIN_BLUR_SIZE))
            left = right - MIN_BLUR_SIZE
        }
        if (bottom - top < MIN_BLUR_SIZE) {
            bottom = min(maxY, max(bottom, top + MIN_BLUR_SIZE))
            top = bottom - MIN_BLUR_SIZE
        }
        return PixelRect(left, top, right - left, bottom - top)
    }

    /**
     * Radien für boxblur. FFmpeg verlangt pro Ebene radius ≤ min(Breite, Höhe) / 2; die
     * Farbebenen sind bei yuv420p nur halb so groß. Wir bleiben jeweils 1 Pixel darunter.
     */
    fun blurRadii(area: PixelRect): Pair<Int, Int> {
        val lumaLimit = min(area.width, area.height) / 2 - 1
        val chromaLimit = min(area.width / 2, area.height / 2) / 2 - 1
        return lumaLimit.coerceIn(1, MAX_LUMA_RADIUS) to chromaLimit.coerceIn(1, MAX_CHROMA_RADIUS)
    }

    /**
     * Teilt das Bild, zeichnet den Ausschnitt weich und legt ihn an derselben Stelle wieder darüber.
     * Ein einfacher Filtergraph (ein Ein-, ein Ausgang) mit internen Labels, nutzbar mit -vf.
     */
    fun blurFilter(area: PixelRect, frameWidth: Int, frameHeight: Int): String {
        val (luma, chroma) = blurRadii(area)
        return "split=2[cm_base][cm_logo];" +
            "[cm_logo]crop=${area.width}:${area.height}:${area.x}:${area.y}," +
            "boxblur=luma_radius=$luma:luma_power=$BLUR_POWER:chroma_radius=$chroma:chroma_power=$BLUR_POWER[cm_blur];" +
            "[cm_base][cm_blur]overlay=${area.x}:${area.y}${evenSizeSuffix(frameWidth, frameHeight)}"
    }

    // --- Hilfen ---

    /**
     * libx264 mit yuv420p braucht gerade Breite und Höhe. Bei (seltenen) ungeraden Maßen wird
     * die letzte Pixelzeile/-spalte abgeschnitten.
     */
    fun evenSizeSuffix(width: Int, height: Int): String =
        if (width % 2 == 0 && height % 2 == 0) "" else ",crop=${floorEven(width)}:${floorEven(height)}:0:0"

    internal fun floorEven(value: Int): Int = value - (value and 1)

    internal fun ceilEven(value: Int): Int = value + (value and 1)
}
