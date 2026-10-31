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

    // --- Crop ---

    @Test
    fun `Crop schneidet bei Logo unten rechts den unteren Rand ab und behaelt das Seitenverhaeltnis`() {
        val window = FfmpegCommandBuilder.cropWindow(PixelRect(1536, 972, 346, 87), 1920, 1080)!!
        // Logo beginnt bei y=972, minus 2 px Abstand → oben 970 Zeilen behalten.
        assertEquals(PixelRect(98, 0, 1724, 970), window)
        assertEquals(1920.0 / 1080.0, window.width.toDouble() / window.height, 0.005)
        assertTrue("Logo darf nicht im Ausschnitt liegen", window.bottom <= 972 - FfmpegCommandBuilder.EDGE_MARGIN)
    }

    @Test
    fun `Crop waehlt die Seite, bei der am meisten Bild uebrig bleibt`() {
        // Schmales, hohes Logo am rechten Rand: Rechts abschneiden ist besser als unten.
        val window = FfmpegCommandBuilder.cropWindow(PixelRect(1800, 100, 100, 900), 1920, 1080)!!
        assertEquals(0, window.x)
        assertTrue(window.right <= 1800 - FfmpegCommandBuilder.EDGE_MARGIN)
        // Logo links oben: Ausschnitt liegt rechts bzw. unterhalb.
        val topLeft = FfmpegCommandBuilder.cropWindow(PixelRect(10, 10, 300, 80), 1920, 1080)!!
        assertTrue(topLeft.y >= 10 + 80 + FfmpegCommandBuilder.EDGE_MARGIN)
    }

    @Test
    fun `Crop-Werte sind gerade und liegen im Bild`() {
        for (logo in listOf(PixelRect(1537, 973, 345, 85), PixelRect(3, 5, 101, 77), PixelRect(900, 500, 31, 17))) {
            val w = FfmpegCommandBuilder.cropWindow(logo, 1281, 721)!!
            listOf(w.x, w.y, w.width, w.height).forEach { assertEquals("Wert $it ungerade in $w", 0, it % 2) }
            assertTrue(w.x >= 0 && w.y >= 0 && w.right <= 1281 && w.bottom <= 721)
        }
    }

    @Test
    fun `Crop meldet zu grosse Markierungen`() {
        assertEquals(null, FfmpegCommandBuilder.cropWindow(PixelRect(0, 0, 1920, 1080), 1920, 1080))
        try {
            FfmpegCommandBuilder.build(command(method = RemovalMethod.CROP, logo = PixelRect(1, 1, 1918, 1078)))
            throw AssertionError("SelectionTooLargeException erwartet")
        } catch (expected: SelectionTooLargeException) {
            // ok
        }
    }

    @Test
    fun `Crop-Filter skaliert zurueck auf Originalgroesse`() {
        val args = FfmpegCommandBuilder.build(command(method = RemovalMethod.CROP))
        assertEquals("crop=1724:970:98:0,scale=1920:1080:flags=lanczos,setsar=1", valueOf(args, "-vf"))
        assertEquals("0:v:0", args[args.indexOf("-map") + 1])
    }

    @Test
    fun `Crop bei Hochkant-Video schneidet im codierten Bild rechts ab`() {
        val video = VideoInfo(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 90)
        val logo = CoordinateMapper.displayRectToVideoPixels(NormalizedRect.Default, video)
        val window = FfmpegCommandBuilder.cropWindow(logo, 1920, 1080)!!
        // Logo liegt codiert rechts oben (x ab 1728) → links behalten = in der Anzeige unten abschneiden.
        assertEquals(PixelRect(0, 54, 1726, 970), window)
        assertEquals(11, FfmpegCommandBuilder.cropZoomPercent(window, 1920))
    }

    @Test
    fun `Crop uebernimmt nicht-quadratische Pixel`() {
        val video = fullHd.copy(codedWidth = 1440, pixelAspectRatio = 4f / 3f)
        val args = FfmpegCommandBuilder.build(command(method = RemovalMethod.CROP, video = video, logo = PixelRect(1100, 980, 300, 80)))
        assertTrue(valueOf(args, "-vf").endsWith("setsar=1.333333"))
    }

    // --- Blur ---

    @Test
    fun `Blur nutzt einen einfachen Filtergraphen mit -vf, damit die Rotation erhalten bleibt`() {
        val args = FfmpegCommandBuilder.build(command(method = RemovalMethod.BLUR, logo = PixelRect(1500, 950, 300, 80)))
        assertFalse(args.contains("-filter_complex"))
        assertEquals(
            "split=2[cm_base][cm_logo];" +
                "[cm_logo]crop=300:80:1500:950," +
                "boxblur=luma_radius=39:luma_power=3:chroma_radius=19:chroma_power=3[cm_blur];" +
                "[cm_base][cm_blur]overlay=1500:950",
            valueOf(args, "-vf"),
        )
    }

    @Test
    fun `Blur-Bereich wird auf gerade Werte erweitert und bleibt im Bild`() {
        assertEquals(PixelRect(1536, 972, 346, 88), FfmpegCommandBuilder.blurArea(PixelRect(1537, 973, 345, 86), 1920, 1080))
        // ganz am Rand und winzig → Mindestgröße, nach innen verschoben
        assertEquals(PixelRect(1912, 1072, 8, 8), FfmpegCommandBuilder.blurArea(PixelRect(1919, 1079, 1, 1), 1920, 1080))
        // ungerade Bildmaße: Bereich endet spätestens an der geraden Grenze
        val odd = FfmpegCommandBuilder.blurArea(PixelRect(1200, 700, 81, 21), 1281, 721)
        assertTrue(odd.right <= 1280 && odd.bottom <= 720)
    }

    @Test
    fun `Blur-Radien halten die boxblur-Grenzen fuer Luma und Chroma ein`() {
        for (size in listOf(8, 9, 10, 16, 31, 64, 100, 400)) {
            for (other in listOf(8, 50, 500)) {
                val area = PixelRect(0, 0, size - size % 2, other)
                val (luma, chroma) = FfmpegCommandBuilder.blurRadii(area)
                val minSide = minOf(area.width, area.height)
                val minChromaSide = minOf(area.width / 2, area.height / 2)
                assertTrue("luma=$luma bei $area", luma in 1..FfmpegCommandBuilder.MAX_LUMA_RADIUS && 2 * luma < minSide)
                assertTrue("chroma=$chroma bei $area", chroma in 1..FfmpegCommandBuilder.MAX_CHROMA_RADIUS && 2 * chroma < minChromaSide)
            }
        }
    }

    @Test
    fun `Blur bei ungeraden Videomassen schneidet am Ende auf gerade Groesse`() {
        val odd = VideoInfo(codedWidth = 1281, codedHeight = 721)
        val args = FfmpegCommandBuilder.build(command(method = RemovalMethod.BLUR, video = odd, logo = PixelRect(1000, 600, 200, 80)))
        assertTrue(valueOf(args, "-vf").endsWith("overlay=1000:600,crop=1280:720:0:0"))
    }
}
