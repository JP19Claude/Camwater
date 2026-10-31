package app.clearmark.ui.common

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Zeigt das Bild eines Media3-Players an.
 * Ohne Controller füllt die Fläche genau den übergebenen Bereich (der Aufrufer sorgt für das
 * richtige Seitenverhältnis); mit Controller übernimmt PlayerView das Einpassen selbst.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    player: Player,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                this.useController = useController
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
                if (useController) {
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
                this.player = player
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
    )
}
