package app.camwater

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/** Bildschirm zum Bearbeiten oder Anlegen einer Watermark-Vorlage (mit Live-Vorschau). */
class EditorDialog(
    private val activity: MainActivity,
    private val store: PresetStore,
    private val original: Preset,
    private val isNew: Boolean,
    private val sample: Bitmap,
    private val sampleInfo: PhotoInfo,
    private val executor: Executor,
    private val onChanged: () -> Unit,
) : Dialog(activity, R.style.Theme_Camwater_Editor) {

    private val main = Handler(Looper.getMainLooper())
    private var logoPath = original.badge.image
    private var token = 0
    private val renderLater = Runnable { renderPreview() }

    private lateinit var previewView: ImageView
    private lateinit var brandInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var prefixInput: EditText
    private lateinit var brandStyleSpinner: Spinner
    private lateinit var styleGroup: RadioGroup
    private lateinit var themeGroup: RadioGroup
    private lateinit var borderCheck: CheckBox
    private lateinit var dateCheck: CheckBox
    private lateinit var badgeTypeSpinner: Spinner
    private lateinit var badgeTextInput: EditText
    private lateinit var badgeFontSpinner: Spinner
    private lateinit var badgeBgInput: EditText
    private lateinit var badgeFgInput: EditText
    private lateinit var exifGroup: RadioGroup
    private lateinit var exifTextInput: EditText
    private lateinit var subtitleInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_editor)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        previewView = requireViewById(R.id.editorPreview)
        brandInput = requireViewById(R.id.brandInput)
        modelInput = requireViewById(R.id.modelInput)
        prefixInput = requireViewById(R.id.prefixInput)
        brandStyleSpinner = requireViewById(R.id.brandStyleSpinner)
        styleGroup = requireViewById(R.id.styleGroup)
        themeGroup = requireViewById(R.id.themeGroup)
        borderCheck = requireViewById(R.id.borderCheck)
        dateCheck = requireViewById(R.id.dateCheck)
        badgeTypeSpinner = requireViewById(R.id.badgeTypeSpinner)
        badgeTextInput = requireViewById(R.id.badgeTextInput)
        badgeFontSpinner = requireViewById(R.id.badgeFontSpinner)
        badgeBgInput = requireViewById(R.id.badgeBgInput)
        badgeFgInput = requireViewById(R.id.badgeFgInput)
        exifGroup = requireViewById(R.id.exifGroup)
        exifTextInput = requireViewById(R.id.exifTextInput)
        subtitleInput = requireViewById(R.id.subtitleInput)

        requireViewById<TextView>(R.id.editorTitle).text =
            if (isNew) "Eigene Marke hinzufügen" else "${original.label} bearbeiten"

        fill(original)
        listOf(brandInput, modelInput, prefixInput, badgeTextInput, badgeBgInput, badgeFgInput, exifTextInput, subtitleInput)
            .forEach { it.addTextChangedListener(watcher) }
        for (g in listOf(styleGroup, themeGroup, exifGroup)) g.setOnCheckedChangeListener { _, _ -> refresh() }
        borderCheck.setOnCheckedChangeListener { _, _ -> refresh() }
        dateCheck.setOnCheckedChangeListener { _, _ -> refresh() }
        swatches(requireViewById(R.id.bgSwatches), badgeBgInput)
        swatches(requireViewById(R.id.fgSwatches), badgeFgInput)

        requireViewById<Button>(R.id.closeButton).setOnClickListener { dismiss() }
        requireViewById<Button>(R.id.logoButton).setOnClickListener { activity.pickLogo { uri -> importLogo(uri) } }
        requireViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        setupDeleteAndCopy()
        refresh()
    }

    /* ---------- Formular ---------- */

    private val brandStyles = listOf(
        "plain" to "Normal", "bold" to "Fett", "round" to "Extra fett",
        "wide" to "Breit / gesperrt", "italic" to "Kursiv", "serif" to "Serifen",
    )
    private val badgeTypes = listOf(
        "none" to "Kein Logo", "box" to "Schriftfeld (wie ZEISS)", "circle" to "Kreis (wie Leica)",
        "text" to "Nur Schriftzug (wie HASSELBLAD)", "image" to "Eigenes Logo-Bild",
    )
    private val badgeFonts = listOf("" to "Normal", "wide" to "Breit", "serif" to "Serifen", "script" to "Schreibschrift")

    private val styleIds = listOf("frame-split" to R.id.styleSplit, "frame-center" to R.id.styleCenter, "overlay" to R.id.styleOverlay)
    private val themeIds = listOf("light" to R.id.themeLight, "dark" to R.id.themeDark)
    private val exifIds = listOf("photo" to R.id.exifPhoto, "custom" to R.id.exifCustom, "off" to R.id.exifOff)

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = refresh()
    }

    private fun Spinner.setup(options: List<Pair<String, String>>, value: String) {
        val a = ArrayAdapter(context, android.R.layout.simple_spinner_item, options.map { it.second })
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter = a
        setSelection(max(0, options.indexOfFirst { it.first == value }))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun Spinner.value(options: List<Pair<String, String>>): String =
        options[selectedItemPosition.coerceIn(0, options.size - 1)].first

    private fun RadioGroup.value(ids: List<Pair<String, Int>>, fallback: String): String =
        ids.firstOrNull { it.second == checkedRadioButtonId }?.first ?: fallback

    private fun RadioGroup.select(ids: List<Pair<String, Int>>, value: String) {
        check(ids.firstOrNull { it.first == value }?.second ?: ids.first().second)
    }

    private fun fill(p: Preset) {
        brandInput.setText(p.brand)
        modelInput.setText(p.model)
        prefixInput.setText(p.prefix)
        brandStyleSpinner.setup(brandStyles, p.brandStyle)
        styleGroup.select(styleIds, p.style)
        themeGroup.select(themeIds, p.theme)
        borderCheck.isChecked = p.border
        dateCheck.isChecked = p.showDate
        badgeTypeSpinner.setup(badgeTypes, p.badge.type)
        badgeTextInput.setText(p.badge.text)
        badgeFontSpinner.setup(badgeFonts, p.badge.font)
        badgeBgInput.setText(p.badge.bg.ifBlank { "#1d4e9e" })
        badgeFgInput.setText(p.badge.fg.ifBlank { "#ffffff" })
        exifGroup.select(exifIds, p.exifMode)
        exifTextInput.setText(p.exifText)
        subtitleInput.setText(p.subtitle)
    }

    private fun readForm(): Preset {
        val type = badgeTypeSpinner.value(badgeTypes)
        val badge = when (type) {
            "box", "circle" -> Badge(
                type = type,
                text = badgeTextInput.text.toString().trim(),
                bg = badgeBgInput.text.toString().trim(),
                fg = badgeFgInput.text.toString().trim(),
                font = if (type == "circle") badgeFontSpinner.value(badgeFonts) else "",
            )
            "text" -> Badge(type = type, text = badgeTextInput.text.toString().trim(), font = badgeFontSpinner.value(badgeFonts))
            "image" -> Badge(type = type, image = logoPath)
            else -> Badge()
        }
        return Preset(
            id = original.id,
            brand = brandInput.text.toString().trim(),
            model = modelInput.text.toString().trim(),
            prefix = prefixInput.text.toString().trim(),
            brandStyle = brandStyleSpinner.value(brandStyles),
            style = styleGroup.value(styleIds, "frame-split"),
            theme = themeGroup.value(themeIds, "light"),
            border = borderCheck.isChecked,
            showDate = dateCheck.isChecked,
            subtitle = subtitleInput.text.toString().trim(),
            badge = badge,
            exifMode = exifGroup.value(exifIds, "photo"),
            exifText = exifTextInput.text.toString().trim(),
            builtIn = original.builtIn,
        )
    }

    private fun refresh() {
        if (!::subtitleInput.isInitialized) return
        val type = badgeTypeSpinner.value(badgeTypes)
        requireViewById<View>(R.id.badgeTextGroup).visibility = vis(type == "box" || type == "circle" || type == "text")
        requireViewById<View>(R.id.badgeFontGroup).visibility = vis(type == "circle" || type == "text")
        requireViewById<View>(R.id.badgeColorGroup).visibility = vis(type == "box" || type == "circle")
        requireViewById<View>(R.id.logoButton).visibility = vis(type == "image")
        val mode = exifGroup.value(exifIds, "photo")
        requireViewById<View>(R.id.exifTextGroup).visibility = vis(mode != "off")
        requireViewById<TextView>(R.id.exifTextLabel).text =
            if (mode == "custom") "Kameradaten-Text" else "Ersatz, falls das Foto keine Daten hat"
        borderCheck.visibility = vis(styleGroup.value(styleIds, "frame-split") != "overlay")
        main.removeCallbacks(renderLater)
        main.postDelayed(renderLater, 120)
    }

    private fun vis(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun renderPreview() {
        val p = readForm()
        val t = ++token
        executor.execute {
            val badge = if (p.badge.type == "image" && p.badge.image.isNotBlank()) BitmapFactory.decodeFile(p.badge.image) else null
            val bmp = WatermarkRenderer.renderCrop(sample, p, sampleInfo, badge)
            badge?.recycle()
            main.post { if (t == token && isShowing) previewView.setImageBitmap(bmp) }
        }
    }

    /* ---------- Farben ---------- */

    private val palette = listOf(
        "#1d4e9e", "#e20612", "#eb0029", "#111111", "#ffffff",
        "#c8a24a", "#1f7a4d", "#6b3fa0", "#ff7a00", "#8a8a8a",
    )

    private fun swatches(row: LinearLayout, target: EditText) {
        val d = context.resources.displayMetrics.density
        val size = (34 * d).roundToInt()
        for (hex in palette) {
            val v = View(context)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(WatermarkRenderer.parseColor(hex, 0))
                setStroke((1.5f * d).roundToInt(), 0x66FFFFFF)
            }
            v.contentDescription = "Farbe $hex"
            v.setOnClickListener { target.setText(hex) }
            row.addView(v, LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * d).roundToInt() })
        }
    }

    /* ---------- Eigenes Logo ---------- */

    private fun importLogo(uri: Uri) {
        executor.execute {
            val path = runCatching {
                val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val s = 600f / max(src.width, src.height)
                val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true) else src
                val dir = File(context.filesDir, "logos").apply { mkdirs() }
                val file = File(dir, "${original.id}-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file.absolutePath
            }.getOrNull()
            main.post {
                if (path == null) {
                    activity.toast("Dieses Bild kann nicht als Logo verwendet werden.")
                } else {
                    logoPath = path
                    badgeTypeSpinner.setSelection(badgeTypes.indexOfFirst { it.first == "image" })
                    refresh()
                }
            }
        }
    }

    /* ---------- Speichern, Löschen, Kopie ---------- */

    private fun save() {
        val p = readForm()
        store.save(p)
        store.selectedId = p.id
        onChanged()
        activity.toast("„${p.label}“ gespeichert")
        dismiss()
    }

    private fun setupDeleteAndCopy() {
        val delete = requireViewById<Button>(R.id.deleteButton)
        val copy = requireViewById<Button>(R.id.copyButton)
        copy.visibility = vis(!isNew)
        when {
            isNew -> delete.visibility = View.GONE
            original.builtIn -> {
                delete.text = "Zurücksetzen"
                delete.visibility = vis(store.isOverridden(original.id))
            }
            else -> delete.text = "Löschen"
        }
        delete.setOnClickListener {
            if (original.builtIn) {
                store.delete(original)
                onChanged()
                activity.toast("„${original.label}“ zurückgesetzt")
                dismiss()
            } else {
                AlertDialog.Builder(context)
                    .setTitle("„${original.label}“ löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        store.delete(original)
                        onChanged()
                        dismiss()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
        copy.setOnClickListener {
            val p = readForm().copy(id = Preset.newId(), builtIn = false)
            store.save(p)
            store.selectedId = p.id
            onChanged()
            activity.toast("Kopie „${p.label}“ gespeichert")
            dismiss()
        }
    }
}
