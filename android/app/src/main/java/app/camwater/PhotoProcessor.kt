package app.camwater

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Lädt ein Foto, setzt das Watermark und speichert das Ergebnis in der Galerie. */
class PhotoProcessor(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    class Result(val uri: Uri, val name: String, val preview: Bitmap?, val info: PhotoInfo)

    /** Liest Kameradaten (Brennweite, Blende, Belichtung, ISO, Datum) aus dem Foto. */
    fun readExif(uri: Uri): ExifInterface? = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it) }
    }.getOrNull()

    fun photoInfo(exif: ExifInterface?, fallbackDate: Date?): PhotoInfo {
        if (exif == null) return PhotoInfo("", fallbackDate)
        val parts = mutableListOf<String>()
        val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        val focal = if (focal35 > 0) focal35.toDouble() else exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        if (focal > 0) parts += "${focal.roundToInt()}mm"
        val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        if (fNumber > 0) parts += "f/" + trim(fNumber, 2)
        val t = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        if (t > 0) parts += if (t >= 1) trim(t, 1) + "s" else "1/${(1 / t).roundToInt()}s"
        val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
        if (iso > 0) parts += "ISO$iso"
        val date = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME))
            ?: fallbackDate
        return PhotoInfo(parts.joinToString("  "), date)
    }

    private fun trim(v: Double, digits: Int): String {
        val s = String.format(Locale.US, "%.${digits}f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    private fun parseExifDate(s: String?): Date? {
        if (s.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s.trim()) }.getOrNull()
    }

    /** Dekodiert das Foto (JPEG, HEIF, PNG …), richtig gedreht und bei Bedarf verkleinert. */
    fun decode(uri: Uri, maxPixels: Long = MAX_PIXELS): Bitmap {
        var sample = 1
        while (true) {
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val px = info.size.width.toLong() * info.size.height
                    var s = sample
                    while (px / (s.toLong() * s) > maxPixels) s *= 2
                    if (s > 1) decoder.setTargetSampleSize(s)
                }
            } catch (oom: OutOfMemoryError) {
                if (sample >= 8) throw oom
                sample *= 2
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return (name ?: "Foto").substringBeforeLast('.').ifBlank { "Foto" }
    }

    private fun dateTaken(uri: Uri): Date? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Date(c.getLong(0)) else null
        }
    }.getOrNull()

    private fun loadBadge(p: Preset): Bitmap? =
        if (p.badge.type == "image" && p.badge.image.isNotBlank()) BitmapFactory.decodeFile(p.badge.image) else null

    /**
     * Setzt das Watermark auf das Foto [uri] und speichert eine neue Datei.
     * Das Original bleibt unverändert.
     */
    fun process(uri: Uri, preset: Preset, folder: String, keepExif: Boolean): Result {
        val exif = readExif(uri)
        val taken = dateTaken(uri)
        val info = photoInfo(exif, taken ?: Date())
        val photo = decode(uri)
        val badge = loadBadge(preset)
        val out = WatermarkRenderer.render(photo, photo.width, photo.height, preset, info, badge)

        val previewScale = 1600f / max(photo.width, photo.height)
        val preview = if (previewScale < 1f) {
            Bitmap.createScaledBitmap(photo, (photo.width * previewScale).roundToInt(), (photo.height * previewScale).roundToInt(), true)
        } else {
            photo.copy(Bitmap.Config.ARGB_8888, false)
        }
        photo.recycle()
        badge?.recycle()

        val temp = File(app.cacheDir, "camwater-${System.nanoTime()}.jpg")
        try {
            FileOutputStream(temp).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (keepExif && exif != null) copyExif(exif, temp, out.width, out.height)
            val name = "${displayName(uri)}_${slug(preset.label)}.jpg"
            val saved = saveToGallery(temp, name, folder, (info.date ?: taken ?: Date()).time)
            return Result(saved, name, preview, info)
        } finally {
            out.recycle()
            temp.delete()
        }
    }

    private fun copyExif(src: ExifInterface, file: File, width: Int, height: Int) {
        runCatching {
            val dst = ExifInterface(file.absolutePath)
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Camwater")
            dst.saveAttributes()
        }
    }

    private fun saveToGallery(file: File, name: String, folder: String, takenMs: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder.trimEnd('/') + "/")
            put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Konnte das Foto nicht speichern.")
        try {
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
                ?: error("Konnte das Foto nicht speichern.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun slug(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .ifBlank { "Watermark" }

    companion object {
        /** Größere Fotos (50/200 MP) werden auf ca. 12–26 MP verkleinert. */
        const val MAX_PIXELS = 26_000_000L

        private val EXIF_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            "LensMake",
            "LensModel",
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}
