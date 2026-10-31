package app.clearmark.domain

import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.RemovalMethod
import app.clearmark.domain.model.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    private val fullHd = VideoInfo(codedWidth = 1920, codedHeight = 1080, durationMs = 10_000)

    private fun command(
        method: RemovalMethod = RemovalMethod.DELOGO,
        video: VideoInfo = fullHd,
        logo: PixelRect = PixelRect(1536, 972, 346, 87),
        audioMode: AudioMode = AudioMode.COPY,
    ) = ExportCommand(
        input = "saf:1.mp4",
        output = "/cache/exports/out.mp4",
        video = video,
        logo = logo,
        method = method,
        audioMode = audioMode,
    )

    /** Wert direkt nach einer Option, z. B. valueOf(args, "-crf") == "18". */
    private fun valueOf(args: List<String>, option: String): String {
        val index = args.indexOf(option)
        assertTrue("Option $option fehlt in $args", index >= 0 && index + 1 < args.size)
        return args[index + 1]
    }

    // --- Allgemeiner Aufbau ---

    @Test
    fun `Grundaufbau mit libx264, crf 18, veryfast und Audio-Kopie`() {
        val args = FfmpegCommandBuilder.build(command())
        assertEquals("libx264", valueOf(args, "-c:v"))
        assertEquals("18", valueOf(args, "-crf"))
        assertEquals("veryfast", valueOf(args, "-preset"))
        assertEquals("copy", valueOf(args, "-c:a"))
        assertEquals("yuv420p", valueOf(args, "-pix_fmt"))
        assertEquals("0", valueOf(args, "-map_metadata"))
        assertEquals("+faststart", valueOf(args, "-movflags"))
        assertEquals("/cache/exports/out.mp4", args.last())
    }

    @Test
    fun `noautorotate steht vor der Eingabe, damit die Rotation erhalten bleibt`() {
        val args = FfmpegCommandBuilder.build(command())
        val noAuto = args.indexOf("-noautorotate")
        val input = args.indexOf("-i")
        assertTrue(noAuto in 0 until input)
        assertEquals("saf:1.mp4", args[input + 1])
        assertFalse(args.contains("-autorotate"))
    }

    @Test
    fun `erste Videospur und alle Tonspuren werden gemappt`() {
        val args = FfmpegCommandBuilder.build(command())
        val maps = args.withIndex().filter { it.value == "-map" }.map { args[it.index + 1] }
        assertEquals(listOf("0:v:0", "0:a?"), maps)
    }

    @Test
    fun `AAC-Rueckfall ersetzt nur die Audio-Optionen`() {
        val args = FfmpegCommandBuilder.build(command(audioMode = AudioMode.AAC))
        assertEquals("aac", valueOf(args, "-c:a"))
        assertEquals("192k", valueOf(args, "-b:a"))
        assertEquals("libx264", valueOf(args, "-c:v"))
    }

    @Test
    fun `Argumente sind einzelne Eintraege, Pfade mit Leerzeichen brauchen kein Quoting`() {
        val args = FfmpegCommandBuilder.build(
            command().copy(input = "/storage/Mein Video.mp4", output = "/cache/Ergebnis 1.mp4"),
        )
        assertEquals("/storage/Mein Video.mp4", valueOf(args, "-i"))
        assertEquals("/cache/Ergebnis 1.mp4", args.last())
    }

    // --- delogo ---

    @Test
    fun `delogo-Filter mit den Pixelwerten`() {
        val args = FfmpegCommandBuilder.build(command(logo = PixelRect(1500, 950, 300, 80)))
        assertEquals("delogo=x=1500:y=950:w=300:h=80", valueOf(args, "-vf"))
    }

    @Test
    fun `delogo haelt 2 Pixel Abstand zum Rand`() {
        // Markierung bis exakt in die Ecke unten rechts
        val area = FfmpegCommandBuilder.sanitizeDelogoRect(PixelRect(1600, 1000, 320, 80), 1920, 1080)
        assertEquals(PixelRect(1600, 1000, 318, 78), area)
        assertEquals(1918, area.right)
        assertEquals(1078, area.bottom)
    }

    @Test
    fun `delogo verschiebt Markierungen an der linken oberen Ecke nach innen`() {
        val area = FfmpegCommandBuilder.sanitizeDelogoRect(PixelRect(0, 0, 200, 100), 1920, 1080)
        assertEquals(PixelRect(2, 2, 198, 98), area)
    }

    @Test
    fun `delogo erzwingt eine Mindestgroesse`() {
        val area = FfmpegCommandBuilder.sanitizeDelogoRect(PixelRect(1919, 1079, 0, 0), 1920, 1080)
        assertEquals(FfmpegCommandBuilder.MIN_DELOGO_SIZE, area.width)
        assertEquals(FfmpegCommandBuilder.MIN_DELOGO_SIZE, area.height)
        assertTrue(area.right <= 1920 - FfmpegCommandBuilder.EDGE_MARGIN)
        assertTrue(area.bottom <= 1080 - FfmpegCommandBuilder.EDGE_MARGIN)
    }

    @Test
    fun `delogo-Bereich liegt fuer beliebige Markierungen immer im Bild`() {
        val w = 1280
        val h = 720
        val m = FfmpegCommandBuilder.EDGE_MARGIN
        for (x in listOf(-50, 0, 1, 2, 600, 1270, 1279, 1400)) {
            for (width in listOf(0, 1, 10, 500, 2000)) {
                val area = FfmpegCommandBuilder.sanitizeDelogoRect(PixelRect(x, x / 2, width, width / 3), w, h)
                assertTrue("x=${area.x}", area.x >= m)
                assertTrue("y=${area.y}", area.y >= m)
                assertTrue("right=${area.right}", area.right <= w - m)
                assertTrue("bottom=${area.bottom}", area.bottom <= h - m)
                assertTrue(area.width >= FfmpegCommandBuilder.MIN_DELOGO_SIZE)
                assertTrue(area.height >= FfmpegCommandBuilder.MIN_DELOGO_SIZE)
            }
        }
    }

    @Test
    fun `Hochkant-Video - delogo arbeitet in codierten Koordinaten`() {
        // Handyvideo hochkant: codiert 1920x1080, Rotation 90°. Logo unten rechts in der Anzeige.
        val video = VideoInfo(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 90)
        val logo = CoordinateMapper.displayRectToVideoPixels(NormalizedRect.Default, video)
        val args = FfmpegCommandBuilder.build(command(video = video, logo = logo))
        assertEquals("delogo=x=1728:y=21:w=154:h=195", valueOf(args, "-vf"))
    }

    @Test
    fun `ungerade Videomasse werden fuer libx264 auf gerade Werte beschnitten`() {
        val odd = VideoInfo(codedWidth = 1281, codedHeight = 721)
        val args = FfmpegCommandBuilder.build(command(video = odd, logo = PixelRect(1000, 600, 200, 80)))
        assertEquals("delogo=x=1000:y=600:w=200:h=80,crop=1280:720:0:0", valueOf(args, "-vf"))
    }
}
