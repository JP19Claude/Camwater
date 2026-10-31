package app.clearmark.domain

import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.ViewRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RectEditorTest {

    private val eps = 1e-5f
    private val rect = NormalizedRect(0.5f, 0.5f, 0.7f, 0.6f)

    private fun assertRect(expected: NormalizedRect, actual: NormalizedRect) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("right", expected.right, actual.right, eps)
        assertEquals("bottom", expected.bottom, actual.bottom, eps)
    }

    @Test
    fun `verschieben behaelt die Groesse`() {
        assertRect(NormalizedRect(0.6f, 0.4f, 0.8f, 0.5f), RectEditor.drag(rect, DragHandle.Move, 0.1f, -0.1f))
    }

    @Test
    fun `verschieben stoppt am Bildrand`() {
        val moved = RectEditor.drag(rect, DragHandle.Move, 0.9f, 0.9f)
        assertRect(NormalizedRect(0.8f, 0.9f, 1f, 1f), moved)
    }

    @Test
    fun `Ecke unten rechts vergroessert nach rechts unten`() {
        assertRect(NormalizedRect(0.5f, 0.5f, 0.8f, 0.7f), RectEditor.drag(rect, DragHandle.BottomRight, 0.1f, 0.1f))
    }

    @Test
    fun `Ecke oben links kann nicht ueber die Mindestgroesse hinaus schrumpfen`() {
        val resized = RectEditor.drag(rect, DragHandle.TopLeft, 0.5f, 0.5f)
        assertEquals(RectEditor.MIN_SIZE, resized.width, eps)
        assertEquals(RectEditor.MIN_SIZE, resized.height, eps)
        assertEquals(0.7f, resized.right, eps)
        assertEquals(0.6f, resized.bottom, eps)
    }

    @Test
    fun `Kantengriff veraendert nur eine Seite`() {
        assertRect(NormalizedRect(0.5f, 0.5f, 0.7f, 0.65f), RectEditor.drag(rect, DragHandle.Bottom, 0.3f, 0.05f))
        assertRect(NormalizedRect(0.4f, 0.5f, 0.7f, 0.6f), RectEditor.drag(rect, DragHandle.Left, -0.1f, 0.3f))
    }

    @Test
    fun `Groessenaenderung bleibt im Bild`() {
        assertRect(NormalizedRect(0.5f, 0.5f, 1f, 1f), RectEditor.drag(rect, DragHandle.BottomRight, 2f, 2f))
        assertRect(NormalizedRect(0f, 0f, 0.7f, 0.6f), RectEditor.drag(rect, DragHandle.TopLeft, -2f, -2f))
    }

    @Test
    fun `sanitize repariert vertauschte und zu kleine Rechtecke`() {
        assertRect(NormalizedRect(0.2f, 0.1f, 0.6f, 0.5f), RectEditor.sanitize(NormalizedRect(0.6f, 0.5f, 0.2f, 0.1f)))
        val tiny = RectEditor.sanitize(NormalizedRect(0.995f, 0.995f, 0.996f, 0.996f))
        assertEquals(RectEditor.MIN_SIZE, tiny.width, eps)
        assertEquals(1f, tiny.right, eps)
    }

    @Test
    fun `hitTest erkennt Ecken, Kanten, Innenflaeche und daneben`() {
        val view = ViewRect(100f, 100f, 400f, 200f)
        assertEquals(DragHandle.TopLeft, RectEditor.hitTest(view, 105f, 95f, 30f))
        assertEquals(DragHandle.BottomRight, RectEditor.hitTest(view, 510f, 310f, 30f))
        assertEquals(DragHandle.Right, RectEditor.hitTest(view, 495f, 200f, 30f))
        assertEquals(DragHandle.Move, RectEditor.hitTest(view, 300f, 200f, 30f))
        assertNull(RectEditor.hitTest(view, 10f, 10f, 30f))
    }

    @Test
    fun `kleines Rechteck laesst sich innen immer verschieben`() {
        val small = ViewRect(100f, 100f, 40f, 30f)
        assertEquals(DragHandle.Move, RectEditor.hitTest(small, 105f, 105f, 30f))
        // Von außen ist der Griff weiterhin erreichbar.
        assertEquals(DragHandle.BottomRight, RectEditor.hitTest(small, 150f, 140f, 30f))
    }

    @Test
    fun `Standardrechteck liegt unten rechts mit 18 mal 8 Prozent`() {
        val d = NormalizedRect.Default
        assertEquals(0.18f, d.width, eps)
        assertEquals(0.08f, d.height, eps)
        assertTrue(d.right <= 1f && d.bottom <= 1f && d.left > 0.5f && d.top > 0.5f)
    }
}
