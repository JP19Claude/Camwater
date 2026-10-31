package app.clearmark.data.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.clearmark.domain.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Das Video konnte nicht gelesen werden. */
class VideoUnreadableException(cause: Throwable? = null) : Exception(cause)

/** Liest Abmessungen, Rotation, Dauer und Größe eines Videos über Android-Bordmittel. */
class VideoInfoRepository(private val context: Context) {

    suspend fun load(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "no") {
                throw VideoUnreadableException()
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width == null || height == null || width <= 0 || height <= 0) {
                throw VideoUnreadableException()
            }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            VideoInfo(
                codedWidth = width,
                codedHeight = height,
                rotationDegrees = rotation,
                durationMs = durationMs,
                hasAudio = hasAudio,
                sizeBytes = querySize(uri),
            )
        } catch (e: VideoUnreadableException) {
            throw e
        } catch (e: Exception) {
            throw VideoUnreadableException(e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it > 0 }
}
