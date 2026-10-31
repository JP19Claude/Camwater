package app.clearmark.domain

import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.PixelRect
import app.clearmark.domain.model.VideoInfo
import app.clearmark.domain.model.ViewRect
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {

    private val eps = 1e-4f

    private fun assertRect(expected: NormalizedRect, actual: NormalizedRect) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("right", expected.right, actual.right, eps)
        assertEquals("bottom", expected.bottom, actual.bottom, eps)
    }

    private fun assertView(expected: ViewRect, actual: ViewRect) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("width", expected.width, actual.width, eps)
        assertEquals("height", expected.height, actual.height, eps)
    }

    // --- Letterboxing ---

    @Test
    fun `16zu9 Video in Hochkant-Container bekommt Balken oben und unten`() {
        val content = CoordinateMapper.fitContent(1080f, 1920f, 16f / 9f)
        assertView(ViewRect(0f, (1920f - 607.5f) / 2f, 1080f, 607.5f), content)
    }

    @Test
    fun `Hochkant-Video in breitem Container bekommt Balken links und rechts`() {
        val content = CoordinateMapper.fitContent(2000f, 1000f, 9f / 16f)
        val width = 1000f * 9f / 16f
        assertView(ViewRect((2000f - width) / 2f, 0f, width, 1000f), content)
    }

    @Test
    fun `gleiches Seitenverhaeltnis fuellt den Container exakt`() {
        assertView(ViewRect(0f, 0f, 1600f, 900f), CoordinateMapper.fitContent(1600f, 900f, 16f / 9f))
    }

    @Test
    fun `leerer Container liefert leeres Rechteck statt Absturz`() {
        assertView(ViewRect(0f, 0f, 0f, 0f), CoordinateMapper.fitContent(0f, 0f, 16f / 9f))
    }

    @Test
    fun `Ansicht zu normiert beruecksichtigt den Letterbox-Versatz`() {
        val content = ViewRect(0f, 100f, 1000f, 500f)
        val view = ViewRect(800f, 500f, 150f, 50f)
        assertRect(NormalizedRect(0.8f, 0.8f, 0.95f, 0.9f), CoordinateMapper.viewToNormalized(view, content))
    }

    @Test
    fun `Ansicht zu normiert begrenzt auf das Bild (Balkenbereich zaehlt nicht)`() {
        val content = ViewRect(0f, 100f, 1000f, 500f)
        val view = ViewRect(900f, 550f, 300f, 200f) // ragt rechts und unten in den Balken
        assertRect(NormalizedRect(0.9f, 0.9f, 1f, 1f), CoordinateMapper.viewToNormalized(view, content))
    }

    @Test
    fun `normiert zu Ansicht und zurueck ist verlustfrei`() {
        val content = CoordinateMapper.fitContent(1080f, 2200f, 16f / 9f)
        val rect = NormalizedRect(0.1f, 0.2f, 0.4f, 0.5f)
        assertRect(rect, CoordinateMapper.viewToNormalized(CoordinateMapper.normalizedToView(rect, content), content))
    }

    // --- Rotation ---

    @Test
    fun `ohne Rotation bleibt das Rechteck unveraendert`() {
        val rect = NormalizedRect(0.8f, 0.9f, 0.98f, 0.98f)
        assertRect(rect, CoordinateMapper.displayToCoded(rect, 0))
    }

    @Test
    fun `90 Grad - unten rechts in der Anzeige ist oben rechts im codierten Bild`() {
        // Hochkant-Handyvideo: codiert 1920x1080, Anzeige 1080x1920 (90° im Uhrzeigersinn gedreht).
        val display = NormalizedRect(0.8f, 0.9f, 0.98f, 0.98f)
        val coded = CoordinateMapper.displayToCoded(display, 90)
        assertRect(NormalizedRect(0.9f, 0.02f, 0.98f, 0.2f), coded)
    }

    @Test
    fun `180 Grad spiegelt in beide Richtungen`() {
        val display = NormalizedRect(0.8f, 0.9f, 0.98f, 0.98f)
        assertRect(NormalizedRect(0.02f, 0.02f, 0.2f, 0.1f), CoordinateMapper.displayToCoded(display, 180))
    }

    @Test
    fun `270 Grad - unten rechts in der Anzeige ist unten links im codierten Bild`() {
        val display = NormalizedRect(0.8f, 0.9f, 0.98f, 0.98f)
        assertRect(NormalizedRect(0.02f, 0.8f, 0.1f, 0.98f), CoordinateMapper.displayToCoded(display, 270))
    }

    @Test
    fun `negative und ungewoehnliche Rotationswerte werden normalisiert`() {
        val rect = NormalizedRect(0.1f, 0.2f, 0.3f, 0.4f)
        assertRect(CoordinateMapper.displayToCoded(rect, 270), CoordinateMapper.displayToCoded(rect, -90))
        assertRect(CoordinateMapper.displayToCoded(rect, 90), CoordinateMapper.displayToCoded(rect, 450))
        assertEquals(90, VideoInfo.normalizeRotation(89))
    }

    @Test
    fun `Hin- und Rueckrechnung ist fuer alle Rotationen verlustfrei`() {
        val rect = NormalizedRect(0.12f, 0.34f, 0.56f, 0.78f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val roundTrip = CoordinateMapper.codedToDisplay(CoordinateMapper.displayToCoded(rect, rotation), rotation)
            assertRect(rect, roundTrip)
        }
    }

    // --- Pixel ---

    @Test
    fun `toPixels rundet nach aussen`() {
        val px = CoordinateMapper.toPixels(NormalizedRect(0.1001f, 0.1001f, 0.2001f, 0.2001f), 1000, 1000)
        assertEquals(PixelRect(100, 100, 101, 101), px)
    }

    @Test
    fun `toPixels rundet exakte Werte nicht unnoetig auf`() {
        val px = CoordinateMapper.toPixels(NormalizedRect(0.1f, 0.25f, 0.3f, 0.75f), 1000, 400)
        assertEquals(PixelRect(100, 100, 200, 200), px)
    }

    @Test
    fun `toPixels bleibt im Bild`() {
        val px = CoordinateMapper.toPixels(NormalizedRect(0.9f, 0.9f, 1f, 1f), 1921, 1081)
        assertEquals(1921, px.right)
        assertEquals(1081, px.bottom)
    }

    @Test
    fun `Standardrechteck in 1080p-Querformat`() {
        val video = VideoInfo(codedWidth = 1920, codedHeight = 1080)
        val px = CoordinateMapper.displayRectToVideoPixels(NormalizedRect.Default, video)
        // 80 % .. 98 % der Breite, 90 % .. 98 % der Höhe
        assertEquals(PixelRect(1536, 972, 346, 87), px)
    }

    @Test
    fun `Standardrechteck in Hochkant-Handyvideo landet rechts oben im codierten Bild`() {
        val video = VideoInfo(codedWidth = 1920, codedHeight = 1080, rotationDegrees = 90)
        assertEquals(1080, video.displayWidth)
        assertEquals(1920, video.displayHeight)
        assertEquals(9f / 16f, video.displayAspectRatio, eps)

        val px = CoordinateMapper.displayRectToVideoPixels(NormalizedRect.Default, video)
        // codiert: x = 0.90..0.98 * 1920, y = 0.02..0.20 * 1080 (nach außen gerundet)
        assertEquals(PixelRect(1728, 21, 154, 195), px)

        // Anzeige-Pixel: 18 % von 1080 breit, 8 % von 1920 hoch
        val display = CoordinateMapper.displayRectToDisplayPixels(NormalizedRect.Default, video)
        assertEquals(PixelRect(864, 1728, 195, 154), display)
    }

    @Test
    fun `nicht-quadratische Pixel veraendern das Anzeige-Seitenverhaeltnis`() {
        val video = VideoInfo(codedWidth = 1440, codedHeight = 1080, pixelAspectRatio = 4f / 3f)
        assertEquals(16f / 9f, video.displayAspectRatio, eps)
    }
}
