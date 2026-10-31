package app.clearmark.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.clearmark.R
import app.clearmark.domain.model.AppError

/** Verständlicher Text zu einem [AppError]. */
@Composable
fun AppError.message(): String = when (this) {
    AppError.VideoUnreadable -> stringResource(R.string.error_video_unreadable)
    AppError.NotEnoughStorage -> stringResource(R.string.error_not_enough_storage)
    is AppError.FfmpegFailed -> stringResource(R.string.error_ffmpeg)
    AppError.SelectionTooLarge -> stringResource(R.string.error_selection_too_large)
    AppError.UnsupportedDevice -> stringResource(R.string.error_unsupported_device)
    AppError.ResultMissing -> stringResource(R.string.error_result_missing)
    AppError.SaveFailed -> stringResource(R.string.error_save_failed)
    AppError.ShareFailed -> stringResource(R.string.error_share_failed)
    is AppError.Unexpected -> stringResource(R.string.error_unexpected)
}

/** Technische Zusatzinfos (z. B. letzte FFmpeg-Logzeilen), falls vorhanden. */
fun AppError.details(): String? = when (this) {
    is AppError.FfmpegFailed -> details?.takeIf { it.isNotBlank() }
    is AppError.Unexpected -> message?.takeIf { it.isNotBlank() }
    else -> null
}
