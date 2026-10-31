package app.clearmark.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.clearmark.R
import app.clearmark.domain.CoordinateMapper
import app.clearmark.domain.DragHandle
import app.clearmark.domain.RectEditor
import app.clearmark.domain.model.NormalizedRect
import app.clearmark.domain.model.ViewRect
import app.clearmark.ui.common.PlayerSurface
import kotlin.math.roundToInt

/**
 * Video mit darüberliegendem Markierungsrechteck. Das Video wird mit erhaltenem
 * Seitenverhältnis eingepasst; das Rechteck ist relativ zum sichtbaren Bild gespeichert.
 */
@Composable
fun VideoWithOverlay(
    player: Player,
    aspectRatio: Float,
    rect: NormalizedRect,
    onRectChange: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val content = remember(containerSize, aspectRatio) {
        CoordinateMapper.fitContent(
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            contentAspectRatio = aspectRatio,
        )
    }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it },
    ) {
        if (containerSize.width > 0 && containerSize.height > 0) {
            PlayerSurface(
                player = player,
                modifier = Modifier
                    .offset { IntOffset(content.left.roundToInt(), content.top.roundToInt()) }
                    .size(
                        width = with(density) { content.width.toDp() },
                        height = with(density) { content.height.toDp() },
                    ),
            )
            LogoOverlay(
                rect = rect,
                content = content,
                onRectChange = onRectChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LogoOverlay(
    rect: NormalizedRect,
    content: ViewRect,
    onRectChange: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val touchRadius = with(density) { TOUCH_RADIUS_DP.dp.toPx() }
    val handleRadius = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val strokeWidth = with(density) { STROKE_DP.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.editor_overlay_description)

    val currentRect by rememberUpdatedState(rect)
    val currentContent by rememberUpdatedState(content)
    val currentOnChange by rememberUpdatedState(onRectChange)

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                var handle: DragHandle? = null
                var working = currentRect
                detectDragGestures(
                    onDragStart = { start ->
                        working = currentRect
                        handle = RectEditor.hitTest(
                            rect = CoordinateMapper.normalizedToView(working, currentContent),
                            x = start.x,
                            y = start.y,
                            touchRadius = touchRadius,
                        )
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = { handle = null },
                    onDrag = { change, dragAmount ->
                        val active = handle ?: return@detectDragGestures
                        val area = currentContent
                        if (area.width <= 0f || area.height <= 0f) return@detectDragGestures
                        change.consume()
                        working = RectEditor.drag(
                            rect = working,
                            handle = active,
                            dx = dragAmount.x / area.width,
                            dy = dragAmount.y / area.height,
                        )
                        currentOnChange(working)
                    },
                )
            },
    ) {
        val box = CoordinateMapper.normalizedToView(rect, content)

        // Alles außerhalb der Markierung leicht abdunkeln, damit der Bereich gut sichtbar ist.
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(content.left, content.top, content.right, content.bottom))
            addRect(Rect(box.left, box.top, box.right, box.bottom))
        }
        drawPath(dim, Color.Black.copy(alpha = 0.35f))

        drawRect(
            color = accent,
            topLeft = Offset(box.left, box.top),
            size = Size(box.width, box.height),
            style = Stroke(width = strokeWidth),
        )

        RectEditor.handlePositions(box).values.forEach { (x, y) ->
            drawCircle(color = Color.White, radius = handleRadius, center = Offset(x, y))
            drawCircle(
                color = accent,
                radius = handleRadius,
                center = Offset(x, y),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

private const val TOUCH_RADIUS_DP = 28
private const val HANDLE_RADIUS_DP = 7
private const val STROKE_DP = 2
