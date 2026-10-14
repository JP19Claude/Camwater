package app.camwater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log

/**
 * Läuft im Hintergrund, solange der Schalter an ist. Sobald die Kamera-App ein
 * neues Foto speichert (Ordner DCIM/Camera), bekommt es automatisch das Watermark.
 */
class CameraWatchService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var observer: ContentObserver
    private lateinit var processor: PhotoProcessor
    private lateinit var store: PresetStore
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private var baselineId = 0L
    private val handled = HashSet<Long>()
    private var count = 0
    private val scan = Runnable { scanNewPhotos() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = PresetStore(this)
        processor = PhotoProcessor(this)
        createChannel(this)
        goForeground(statusText())

        thread = HandlerThread("camwater-watch").apply { start() }
        handler = Handler(thread.looper)
        // Nur Fotos, die ab jetzt entstehen – alte Fotos bleiben unberührt.
        baselineId = latestId()
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = schedule()
            override fun onChange(selfChange: Boolean, uri: Uri?) = schedule()
        }
        contentResolver.registerContentObserver(collection, true, observer)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.autoEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(statusText())
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun schedule() {
        // Die Kamera schreibt das Foto in mehreren Schritten – kurz warten.
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, 1200)
    }

    private fun latestId(): Long {
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        return runCatching {
            contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun scanNewPhotos() {
        if (!store.autoEnabled) {
            stopSelf()
            return
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val found = mutableListOf<Pair<Long, String>>()
        runCatching {
            contentResolver.query(
                collection, projection,
                "${MediaStore.Images.Media._ID} > ?", arrayOf(baselineId.toString()),
                "${MediaStore.Images.Media._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (!handled.add(id)) continue
                    val path = c.getString(1) ?: ""
                    val owner = c.getString(2) ?: ""
                    val mime = c.getString(3) ?: ""
                    val name = c.getString(4) ?: ""
                    // Eigene Ergebnisse, andere Ordner und z.B. RAW-Dateien überspringen.
                    if (owner == packageName) continue
                    if (!path.startsWith("DCIM/Camera", ignoreCase = true)) continue
                    if (mime !in SUPPORTED_TYPES) continue
                    found += id to name
                }
            }
        }.onFailure { Log.w(TAG, "Abfrage fehlgeschlagen", it) }

        for ((id, name) in found) {
            val preset = store.selected()
            try {
                processor.process(ContentUris.withAppendedId(collection, id), preset, store.saveFolder, store.keepExif)
                    .preview?.recycle()
                count++
                goForeground("✓ $name – ${preset.label}")
            } catch (e: Throwable) {
                Log.w(TAG, "Foto $id konnte nicht bearbeitet werden", e)
                goForeground("Fehler bei $name")
            }
        }
        if (handled.size > 5000) handled.clear()
    }

    private fun statusText(): String = "Neue Fotos bekommen: ${store.selected().label}"

    private fun goForeground(text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (count > 0) "Camwater ist an · $count Foto(s) bearbeitet" else "Camwater ist an"
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "Camwater"
        private const val CHANNEL = "watch"
        private const val NOTIFICATION_ID = 1
        private val SUPPORTED_TYPES = setOf("image/jpeg", "image/heif", "image/heic", "image/png", "image/webp")

        @Volatile
        var isRunning = false
            private set

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Automatisches Watermark", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Zeigt an, dass Camwater neue Fotos mit Watermark versieht."
                nm.createNotificationChannel(ch)
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CameraWatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraWatchService::class.java))
        }
    }
}
