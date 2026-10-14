package app.camwater

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Speichert eigene Vorlagen, Änderungen an mitgelieferten Vorlagen und Einstellungen. */
class PresetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("camwater", Context.MODE_PRIVATE)

    /** Automatik: neue Kamera-Fotos bekommen das Watermark. */
    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    var keepExif: Boolean
        get() = prefs.getBoolean("keepExif", true)
        set(v) = prefs.edit().putBoolean("keepExif", v).apply()

    /** Ordner für fertige Fotos (relativ zum Speicher), z.B. "Pictures/Camwater". */
    var saveFolder: String
        get() = prefs.getString("folder", FOLDER_ALBUM) ?: FOLDER_ALBUM
        set(v) = prefs.edit().putString("folder", v).apply()

    var selectedId: String
        get() = prefs.getString("selected", null) ?: BuiltInPresets.list.first().id
        set(v) = prefs.edit().putString("selected", v).apply()

    private fun custom(): List<Preset> {
        val arr = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Preset.fromJson(it) } }
    }

    private fun overrides(): JSONObject =
        runCatching { JSONObject(prefs.getString("overrides", "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** Alle Vorlagen, eigene zuerst. */
    fun all(): List<Preset> {
        val o = overrides()
        val builtIns = BuiltInPresets.list.map { b ->
            o.optJSONObject(b.id)?.let { Preset.fromJson(it, builtIn = true).copy(id = b.id) } ?: b
        }
        return custom() + builtIns
    }

    fun selected(): Preset {
        val list = all()
        return list.firstOrNull { it.id == selectedId } ?: list.first()
    }

    fun isOverridden(id: String): Boolean = overrides().has(id)

    fun save(p: Preset) {
        if (p.builtIn) {
            val o = overrides().put(p.id, p.toJson())
            prefs.edit().putString("overrides", o.toString()).apply()
        } else {
            val list = custom().toMutableList()
            val i = list.indexOfFirst { it.id == p.id }
            if (i >= 0) list[i] = p else list.add(0, p)
            writeCustom(list)
        }
    }

    fun delete(p: Preset) {
        if (p.builtIn) {
            val o = overrides()
            o.remove(p.id)
            prefs.edit().putString("overrides", o.toString()).apply()
        } else {
            writeCustom(custom().filterNot { it.id == p.id })
            if (selectedId == p.id) selectedId = BuiltInPresets.list.first().id
        }
    }

    private fun writeCustom(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("custom", arr.toString()).apply()
    }

    companion object {
        const val FOLDER_ALBUM = "Pictures/Camwater"
        const val FOLDER_CAMERA = "DCIM/Camera"
    }
}
