package app.clearmark.data.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Zu wenig Platz im Galerie-Speicher. */
class NotEnoughStorageException : IOException()

/** Speichert fertige Videos in der Galerie unter Movies/ClearMark. */
class MediaStoreRepository(private val context: Context) {

    /** Vor Android 10 braucht das Schreiben in Movies/ die Speicherberechtigung. */
    val needsLegacyStoragePermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    suspend fun saveVideo(file: File): Uri = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) throw IOException("Datei fehlt: $file")
        val free = context.getExternalFilesDir(null)?.usableSpace
        if (free != null && free < file.length() + SAFETY_BYTES) throw NotEnoughStorageException()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(file) else saveLegacy(file)
        } catch (e: IOException) {
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true) {
                throw NotEnoughStorageException()
            }
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore-Eintrag fehlgeschlagen")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Ausgabe nicht verfügbar")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(file: File): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner kann nicht angelegt werden")
        var target = File(dir, file.name)
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${file.nameWithoutExtension}_${counter++}.${file.extension}")
        }
        try {
            file.copyTo(target)
        } catch (e: Exception) {
            target.delete()
            throw if (e is IOException) e else IOException(e)
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MIME_TYPE), null)
        return Uri.fromFile(target)
    }

    private companion object {
        const val ALBUM = "ClearMark"
        const val MIME_TYPE = "video/mp4"
        const val SAFETY_BYTES = 10L * 1024 * 1024
    }
}
