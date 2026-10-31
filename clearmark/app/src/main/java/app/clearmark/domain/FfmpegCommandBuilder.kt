package app.clearmark.domain

import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.RemovalMethod
import app.clearmark.domain.model.VideoInfo

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
            RemovalMethod.CROP, RemovalMethod.BLUR ->
                throw UnsupportedOperationException("Methode ${command.method} ist noch nicht umgesetzt")
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

    /**
     * libx264 mit yuv420p braucht gerade Breite und Höhe. Bei (seltenen) ungeraden Maßen wird
     * die letzte Pixelzeile/-spalte abgeschnitten.
     */
    fun evenSizeSuffix(width: Int, height: Int): String =
        if (width % 2 == 0 && height % 2 == 0) "" else ",crop=${floorEven(width)}:${floorEven(height)}:0:0"

    internal fun floorEven(value: Int): Int = value - (value and 1)

    internal fun ceilEven(value: Int): Int = value + (value and 1)
}
