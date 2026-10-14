package app.camwater

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: PresetStore
    private lateinit var processor: PhotoProcessor
    private val work: ExecutorService = Executors.newSingleThreadExecutor()
    private val render: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var autoSwitch: Switch
    private lateinit var autoTitle: TextView
    private lateinit var autoStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var preview: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var pickButton: Button
    private lateinit var progressRow: View
    private lateinit var progressText: TextView
    private lateinit var presetList: LinearLayout

    /** Beispielbilder für Vorschau (quer) und Vorlagen-Kacheln (hochkant). */
    @Volatile private var demo: Bitmap? = null
    @Volatile private var demoThumb: Bitmap? = null

    /** Zuletzt bearbeitetes eigenes Foto, damit die Vorschau dein Foto zeigt. */
    private var lastPhoto: Bitmap? = null
    private var lastInfo = PhotoInfo()
    private var logoCallback: ((Uri) -> Unit)? = null
    private var updatingSwitch = false
    private var previewToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PresetStore(this)
        processor = PhotoProcessor(this)

        autoSwitch = requireViewById(R.id.autoSwitch)
        autoTitle = requireViewById(R.id.autoTitle)
        autoStatus = requireViewById(R.id.autoStatus)
        batteryButton = requireViewById(R.id.batteryButton)
        preview = requireViewById(R.id.preview)
        previewLabel = requireViewById(R.id.previewLabel)
        pickButton = requireViewById(R.id.pickButton)
        progressRow = requireViewById(R.id.progressRow)
        progressText = requireViewById(R.id.progressText)
        presetList = requireViewById(R.id.presetList)

        autoSwitch.setOnCheckedChangeListener { _, checked -> if (!updatingSwitch) onAutoToggled(checked) }
        batteryButton.setOnClickListener { requestBatteryExemption() }
        pickButton.setOnClickListener { pickPhotos() }
        requireViewById<Button>(R.id.editButton).setOnClickListener { openEditor(store.selected(), false) }
        requireViewById<Button>(R.id.addButton).setOnClickListener { openEditor(newPreset(), true) }

        val folderGroup = requireViewById<RadioGroup>(R.id.folderGroup)
        folderGroup.check(if (store.saveFolder == PresetStore.FOLDER_CAMERA) R.id.folderCamera else R.id.folderAlbum)
        folderGroup.setOnCheckedChangeListener { _, id ->
            store.saveFolder = if (id == R.id.folderCamera) PresetStore.FOLDER_CAMERA else PresetStore.FOLDER_ALBUM
            updateAutoStatus()
        }
        val exifSwitch = requireViewById<Switch>(R.id.exifSwitch)
        exifSwitch.isChecked = store.keepExif
        exifSwitch.setOnCheckedChangeListener { _, checked -> store.keepExif = checked }

        render.execute {
            demo = WatermarkRenderer.demoPhoto(1600, 1200)
            demoThumb = WatermarkRenderer.demoPhoto(900, 1200)
            main.post {
                if (!isDestroyed) {
                    renderPresetList()
                    renderPreview()
                }
            }
        }
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (store.autoEnabled && !Permissions.hasPhotoAccess(this)) {
            store.autoEnabled = false
            CameraWatchService.stop(this)
        }
        if (store.autoEnabled && !CameraWatchService.isRunning) {
            runCatching { CameraWatchService.start(this) }
        }
        setSwitch(store.autoEnabled)
        updateAutoStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        work.shutdown()
        render.shutdown()
        super.onDestroy()
    }

    /* ---------- Hauptschalter ---------- */

    private fun setSwitch(on: Boolean) {
        updatingSwitch = true
        autoSwitch.isChecked = on
        updatingSwitch = false
    }

    private fun onAutoToggled(on: Boolean) {
        if (!on) {
            store.autoEnabled = false
            CameraWatchService.stop(this)
            updateAutoStatus()
            toast("Watermark ist AUS")
            return
        }
        if (Permissions.hasPhotoAccess(this)) enableAuto() else requestPermissions(Permissions.needed(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (Permissions.hasPhotoAccess(this)) {
            enableAuto()
        } else {
            setSwitch(false)
            showPermissionHelp()
        }
    }

    private fun enableAuto() {
        store.autoEnabled = true
        try {
            CameraWatchService.start(this)
            toast("Watermark ist AN – mach jetzt ein Foto mit deiner Kamera")
        } catch (e: Exception) {
            store.autoEnabled = false
            toast("Automatik konnte nicht starten: ${e.message}")
        }
        setSwitch(store.autoEnabled)
        updateAutoStatus()
    }

    private fun showPermissionHelp() {
        val message = if (Permissions.hasOnlyPartialAccess(this)) {
            "Du hast nur ausgewählte Fotos erlaubt. Damit Camwater neue Kamera-Fotos findet, wähle bei „Fotos und Videos“ bitte „Alle zulassen“."
        } else {
            "Damit Camwater neue Fotos aus deiner Kamera findet, braucht die App Zugriff auf deine Fotos. Du kannst das in den Einstellungen unter Berechtigungen erlauben."
        }
        AlertDialog.Builder(this)
            .setTitle("Zugriff auf Fotos nötig")
            .setMessage(message)
            .setPositiveButton("Einstellungen öffnen") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun folderName(folder: String) =
        if (folder == PresetStore.FOLDER_CAMERA) "im Kamera-Ordner" else "im Album „Camwater“"

    private fun updateAutoStatus() {
        val on = store.autoEnabled
        autoTitle.text = if (on) "Watermark AN" else "Watermark AUS"
        autoTitle.setTextColor(getColor(if (on) R.color.accent else R.color.text))
        autoStatus.text = if (on) {
            "Jedes neue Foto aus deiner Kamera bekommt automatisch „${store.selected().label}“. Die Kopie mit Watermark findest du ${folderName(store.saveFolder)}."
        } else {
            "Schalte ein, damit jedes neue Foto aus deiner Samsung-Kamera automatisch ein Watermark bekommt."
        }
        val unrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
        batteryButton.visibility = if (on && !unrestricted) View.VISIBLE else View.GONE
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /* ---------- Vorschau & Vorlagen ---------- */

    private fun loadBadge(p: Preset): Bitmap? =
        if (p.badge.type == "image" && p.badge.image.isNotBlank()) BitmapFactory.decodeFile(p.badge.image) else null

    private fun renderPreview() {
        val src = lastPhoto ?: demo ?: return
        val info = if (lastPhoto != null) lastInfo else PhotoInfo("", Date())
        val p = store.selected()
        val token = ++previewToken
        previewLabel.visibility = if (lastPhoto == null) View.VISIBLE else View.GONE
        render.execute {
            val badge = loadBadge(p)
            val bmp = WatermarkRenderer.render(src, src.width, src.height, p, info, badge)
            badge?.recycle()
            main.post { if (!isDestroyed && token == previewToken) preview.setImageBitmap(bmp) }
        }
    }

    private fun renderPresetList() {
        val thumb = demoThumb ?: return
        presetList.removeAllViews()
        val selectedId = store.selected().id
        for (p in store.all()) {
            val item = layoutInflater.inflate(R.layout.item_preset, presetList, false)
            item.tag = p.id
            item.isSelected = p.id == selectedId
            item.requireViewById<TextView>(R.id.name).text = p.label
            val tagText = when {
                !p.builtIn -> "Eigene"
                store.isOverridden(p.id) -> "Angepasst"
                else -> ""
            }
            item.requireViewById<TextView>(R.id.tag).apply {
                text = tagText
                visibility = if (tagText.isEmpty()) View.GONE else View.VISIBLE
            }
            item.setOnClickListener { select(p.id) }
            item.setOnLongClickListener {
                openEditor(p, false)
                true
            }
            presetList.addView(item)
            val iv = item.requireViewById<ImageView>(R.id.thumb)
            render.execute {
                val badge = loadBadge(p)
                val bmp = WatermarkRenderer.renderCrop(thumb, p, PhotoInfo("", Date()), badge)
                badge?.recycle()
                main.post { if (!isDestroyed) iv.setImageBitmap(bmp) }
            }
        }
    }

    private fun select(id: String) {
        store.selectedId = id
        for (i in 0 until presetList.childCount) {
            val v = presetList.getChildAt(i)
            v.isSelected = v.tag == id
        }
        renderPreview()
        updateAutoStatus()
        refreshService()
    }

    private fun refreshService() {
        // Aktualisiert den Text der Benachrichtigung.
        if (CameraWatchService.isRunning) runCatching { CameraWatchService.start(this) }
    }

    private fun newPreset() = Preset(id = Preset.newId(), exifText = "24mm  f/1.8  1/100s  ISO100")

    private fun openEditor(p: Preset, isNew: Boolean) {
        val sample = lastPhoto ?: demoThumb ?: return
        val info = if (lastPhoto != null) lastInfo else PhotoInfo("", Date())
        EditorDialog(this, store, p, isNew, sample, info, render) {
            renderPresetList()
            renderPreview()
            updateAutoStatus()
            refreshService()
        }.show()
    }

    /* ---------- Fotos auswählen ---------- */

    private fun pickerIntent(multiple: Boolean): Intent =
        if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }

    private fun pickPhotos() {
        try {
            startActivityForResult(pickerIntent(true), REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            toast("Keine Galerie gefunden.")
        }
    }

    /** Wird vom Editor aufgerufen, um ein eigenes Logo-Bild zu wählen. */
    fun pickLogo(callback: (Uri) -> Unit) {
        logoCallback = callback
        try {
            startActivityForResult(pickerIntent(false), REQ_LOGO)
        } catch (e: ActivityNotFoundException) {
            toast("Keine Galerie gefunden.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        when (requestCode) {
            REQ_PICK -> processUris(uris)
            REQ_LOGO -> uris.firstOrNull()?.let { logoCallback?.invoke(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        intent.action = Intent.ACTION_MAIN // nicht erneut verarbeiten, z.B. beim Drehen
        processUris(uris)
    }

    private fun processUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val preset = store.selected()
        val folder = store.saveFolder
        val keepExif = store.keepExif
        progressRow.visibility = View.VISIBLE
        progressText.text = "Wird bearbeitet …"
        pickButton.isEnabled = false
        pickButton.alpha = 0.5f
        work.execute {
            var ok = 0
            var error: String? = null
            uris.forEachIndexed { i, uri ->
                if (uris.size > 1) main.post { progressText.text = "Foto ${i + 1} von ${uris.size} …" }
                try {
                    val r = processor.process(uri, preset, folder, keepExif)
                    ok++
                    main.post {
                        lastPhoto = r.preview
                        lastInfo = r.info
                        renderPreview()
                    }
                } catch (e: Throwable) {
                    error = e.message ?: e.javaClass.simpleName
                }
            }
            val saved = ok
            val failed = error
            main.post {
                if (isDestroyed) return@post
                progressRow.visibility = View.GONE
                pickButton.isEnabled = true
                pickButton.alpha = 1f
                val where = folderName(folder)
                toast(
                    when {
                        saved > 0 && failed == null ->
                            if (saved == 1) "Mit Watermark gespeichert ✓ ($where)" else "$saved Fotos mit Watermark gespeichert ✓ ($where)"
                        saved > 0 -> "$saved gespeichert, bei anderen gab es einen Fehler: $failed"
                        else -> "Das hat nicht geklappt: $failed"
                    },
                )
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_PICK = 2
        private const val REQ_LOGO = 3
    }
}
