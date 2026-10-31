package app.clearmark.domain

import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.ViewRect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Welcher Teil des Markierungsrechtecks gezogen wird. */
enum class DragHandle(
    val movesLeft: Boolean = false,
    val movesTop: Boolean = false,
    val movesRight: Boolean = false,
    val movesBottom: Boolean = false,
) {
    Move,
    TopLeft(movesLeft = true, movesTop = true),
    Top(movesTop = true),
    TopRight(movesRight = true, movesTop = true),
    Right(movesRight = true),
    BottomRight(movesRight = true, movesBottom = true),
    Bottom(movesBottom = true),
    BottomLeft(movesLeft = true, movesBottom = true),
    Left(movesLeft = true),
}

/** Reine Logik für Verschieben und Skalieren des Markierungsrechtecks. */
object RectEditor {

    /** Mindestgröße des Rechtecks relativ zum Bild. */
    const val MIN_SIZE = 0.02f

    /** Positionen der acht Griffe (Ecken und Kantenmitten) in Ansichtskoordinaten. */
    fun handlePositions(rect: ViewRect): Map<DragHandle, Pair<Float, Float>> {
        val cx = rect.left + rect.width / 2f
        val cy = rect.top + rect.height / 2f
        return linkedMapOf(
            DragHandle.TopLeft to (rect.left to rect.top),
            DragHandle.TopRight to (rect.right to rect.top),
            DragHandle.BottomRight to (rect.right to rect.bottom),
            DragHandle.BottomLeft to (rect.left to rect.bottom),
            DragHandle.Top to (cx to rect.top),
            DragHandle.Right to (rect.right to cy),
            DragHandle.Bottom to (cx to rect.bottom),
            DragHandle.Left to (rect.left to cy),
        )
    }

    /**
     * Ermittelt, was bei einer Berührung an ([x], [y]) gezogen wird.
     * Ist das Rechteck sehr klein, gewinnt innen immer „Verschieben“; die Griffe sind dann
     * von außen erreichbar, weil ihr Trefferbereich über das Rechteck hinausragt.
     */
    fun hitTest(rect: ViewRect, x: Float, y: Float, touchRadius: Float): DragHandle? {
        val inside = rect.contains(x, y)
        val small = min(rect.width, rect.height) < 2.5f * touchRadius
        if (inside && small) return DragHandle.Move

        val nearest = handlePositions(rect)
            .map { (handle, pos) -> handle to hypot(x - pos.first, y - pos.second) }
            .minByOrNull { it.second }
        if (nearest != null && nearest.second <= touchRadius) return nearest.first
        return if (inside) DragHandle.Move else null
    }

    /**
     * Wendet eine Ziehbewegung an. [dx] und [dy] sind normiert (Anteil der Bildbreite/-höhe).
     * Das Ergebnis bleibt immer innerhalb des Bildes und mindestens [minWidth] × [minHeight] groß.
     */
    fun drag(
        rect: NormalizedRect,
        handle: DragHandle,
        dx: Float,
        dy: Float,
        minWidth: Float = MIN_SIZE,
        minHeight: Float = MIN_SIZE,
    ): NormalizedRect {
        val start = sanitize(rect, minWidth, minHeight)
        if (handle == DragHandle.Move) {
            val left = clamp(start.left + dx, 0f, 1f - start.width)
            val top = clamp(start.top + dy, 0f, 1f - start.height)
            return NormalizedRect(left, top, left + start.width, top + start.height)
        }
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        if (handle.movesLeft) left = clamp(left + dx, 0f, right - minWidth)
        if (handle.movesRight) right = clamp(right + dx, left + minWidth, 1f)
        if (handle.movesTop) top = clamp(top + dy, 0f, bottom - minHeight)
        if (handle.movesBottom) bottom = clamp(bottom + dy, top + minHeight, 1f)
        return NormalizedRect(left, top, right, bottom)
    }

    /** Bringt ein beliebiges Rechteck in einen gültigen Zustand (geordnet, im Bild, Mindestgröße). */
    fun sanitize(rect: NormalizedRect, minWidth: Float = MIN_SIZE, minHeight: Float = MIN_SIZE): NormalizedRect {
        val minW = minWidth.coerceIn(0f, 1f)
        val minH = minHeight.coerceIn(0f, 1f)
        var left = clamp(min(rect.left, rect.right), 0f, 1f)
        var right = clamp(max(rect.left, rect.right), 0f, 1f)
        var top = clamp(min(rect.top, rect.bottom), 0f, 1f)
        var bottom = clamp(max(rect.top, rect.bottom), 0f, 1f)
        if (right - left < minW) {
            right = min(1f, left + minW)
            left = right - minW
        }
        if (bottom - top < minH) {
            bottom = min(1f, top + minH)
            top = bottom - minH
        }
        return NormalizedRect(left, top, right, bottom)
    }

    /** Wie coerceIn, wirft aber nicht, wenn die Grenzen vertauscht sind (dann gewinnt [low]). */
    private fun clamp(value: Float, low: Float, high: Float): Float =
        if (high < low) low else value.coerceIn(low, high)
}
