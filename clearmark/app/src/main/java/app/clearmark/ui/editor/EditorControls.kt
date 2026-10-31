package app.clearmark.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clearmark.R
import app.clearmark.domain.model.RemovalMethod
import java.util.Locale

/** Play/Pause, Seekbar und Zeitanzeige. */
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shownPosition = (scrubPosition ?: positionMs.toFloat()).coerceIn(0f, max)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
            )
        }
        Slider(
            value = shownPosition,
            onValueChange = { value ->
                scrubPosition = value
                onSeek(value.toLong())
            },
            onValueChangeFinished = {
                scrubPosition?.let { onSeek(it.toLong()) }
                scrubPosition = null
            },
            valueRange = 0f..max,
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatTime(shownPosition.toLong())} / ${formatTime(durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
    }
}

/** Auswahl Delogo / Crop / Blur. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MethodSelector(
    selected: RemovalMethod,
    onSelected: (RemovalMethod) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val methods = RemovalMethod.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        methods.forEachIndexed { index, method ->
            SegmentedButton(
                selected = method == selected,
                onClick = { onSelected(method) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = methods.size),
                enabled = enabled,
                label = { Text(stringResource(method.labelRes)) },
            )
        }
    }
}

val RemovalMethod.labelRes: Int
    get() = when (this) {
        RemovalMethod.DELOGO -> R.string.method_delogo
        RemovalMethod.CROP -> R.string.method_crop
        RemovalMethod.BLUR -> R.string.method_blur
    }

val RemovalMethod.descriptionRes: Int
    get() = when (this) {
        RemovalMethod.DELOGO -> R.string.method_delogo_description
        RemovalMethod.CROP -> R.string.method_crop_description
        RemovalMethod.BLUR -> R.string.method_blur_description
    }

fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.GERMANY, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.GERMANY, "%d:%02d", minutes, seconds)
    }
}
