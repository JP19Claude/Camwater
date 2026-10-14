package app.camwater

import org.json.JSONObject

/** Partner-Logo neben dem Watermark (ZEISS, Leica, eigenes Bild …). */
data class Badge(
    /** none | box | circle | text | image */
    val type: String = "none",
    val text: String = "",
    val bg: String = "#1d4e9e",
    /** Leer = automatisch (Schriftfarbe des Watermarks). */
    val fg: String = "",
    /** "" | wide | serif | script */
    val font: String = "",
    /** Pfad zu einem eigenen Logo-Bild (nur bei type = image). */
    val image: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("text", text)
        .put("bg", bg)
        .put("fg", fg)
        .put("font", font)
        .put("image", image)

    companion object {
        fun fromJson(o: JSONObject?): Badge {
            if (o == null) return Badge()
            return Badge(
                type = o.optString("type", "none"),
                text = o.optString("text", ""),
                bg = o.optString("bg", "#1d4e9e"),
                fg = o.optString("fg", ""),
                font = o.optString("font", ""),
                image = o.optString("image", ""),
            )
        }
    }
}

/** Eine Watermark-Vorlage, z.B. "vivo X300 Ultra" mit ZEISS-Logo. */
data class Preset(
    val id: String,
    val brand: String = "",
    val model: String = "",
    /** Text vor der Marke, z.B. "Shot on". */
    val prefix: String = "",
    /** plain | bold | round | wide | italic | serif */
    val brandStyle: String = "bold",
    /** frame-split (Leiste, Logo rechts) | frame-center (Leiste, mittig) | overlay (im Bild) */
    val style: String = "frame-split",
    /** light | dark (bei overlay: helle oder dunkle Schrift) */
    val theme: String = "light",
    val border: Boolean = false,
    val showDate: Boolean = true,
    val subtitle: String = "",
    val badge: Badge = Badge(),
    /** photo (aus dem Foto) | custom (eigener Text) | off */
    val exifMode: String = "photo",
    val exifText: String = "",
    val builtIn: Boolean = false,
) {
    val label: String
        get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Ohne Namen" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("brand", brand)
        .put("model", model)
        .put("prefix", prefix)
        .put("brandStyle", brandStyle)
        .put("style", style)
        .put("theme", theme)
        .put("border", border)
        .put("showDate", showDate)
        .put("subtitle", subtitle)
        .put("badge", badge.toJson())
        .put("exifMode", exifMode)
        .put("exifText", exifText)

    companion object {
        fun fromJson(o: JSONObject, builtIn: Boolean = false): Preset = Preset(
            id = o.optString("id", newId()),
            brand = o.optString("brand", ""),
            model = o.optString("model", ""),
            prefix = o.optString("prefix", ""),
            brandStyle = o.optString("brandStyle", "bold"),
            style = o.optString("style", "frame-split"),
            theme = o.optString("theme", "light"),
            border = o.optBoolean("border", false),
            showDate = o.optBoolean("showDate", true),
            subtitle = o.optString("subtitle", ""),
            badge = Badge.fromJson(o.optJSONObject("badge")),
            exifMode = o.optString("exifMode", "photo"),
            exifText = o.optString("exifText", ""),
            builtIn = builtIn,
        )

        fun newId(): String = "custom-" + System.currentTimeMillis().toString(36) + (1000..9999).random()
    }
}

/** Mitgelieferte Vorlagen. Logos werden als Schrift/Formen gezeichnet, nicht als Originaldateien. */
object BuiltInPresets {
    private val ZEISS = Badge(type = "box", text = "ZEISS", bg = "#1d4e9e", fg = "#ffffff")
    private val LEICA = Badge(type = "circle", text = "Leica", bg = "#e20612", fg = "#ffffff", font = "script")

    val list: List<Preset> = listOf(
        Preset(
            id = "vivo-x300-ultra", brand = "vivo", model = "X300 Ultra", brandStyle = "round",
            style = "frame-split", badge = ZEISS, exifText = "35mm  f/1.85  1/100s  ISO100", builtIn = true,
        ),
        Preset(
            id = "vivo-x300-pro", brand = "vivo", model = "X300 Pro", brandStyle = "round",
            style = "frame-center", badge = ZEISS, exifText = "24mm  f/1.57  1/100s  ISO100", builtIn = true,
        ),
        Preset(
            id = "xiaomi-17-ultra", brand = "Xiaomi", model = "17 Ultra", brandStyle = "bold",
            style = "frame-split", badge = LEICA, exifText = "23mm  f/1.63  1/120s  ISO50", builtIn = true,
        ),
        Preset(
            id = "oppo-find-x9-ultra", brand = "OPPO", model = "Find X9 Ultra", brandStyle = "bold",
            style = "frame-center", badge = Badge(type = "text", text = "HASSELBLAD", font = "wide"),
            exifText = "23mm  f/1.8  1/100s  ISO100", builtIn = true,
        ),
        Preset(
            id = "huawei-pura-80-ultra", brand = "HUAWEI", model = "Pura 80 Ultra", brandStyle = "wide",
            style = "frame-split", badge = Badge(type = "text", text = "XMAGE", font = "wide"),
            exifText = "23mm  f/1.6  1/100s  ISO50", builtIn = true,
        ),
        Preset(
            id = "honor-magic8-pro", brand = "HONOR", model = "Magic8 Pro", brandStyle = "wide",
            style = "overlay", exifText = "23mm  f/1.6  1/100s  ISO100", builtIn = true,
        ),
        Preset(
            id = "samsung-s26-ultra", brand = "Galaxy", model = "S26 Ultra", brandStyle = "bold",
            style = "overlay", prefix = "Shot with my", exifText = "24mm  f/1.7  1/100s  ISO50", builtIn = true,
        ),
        Preset(
            id = "iphone-17-pro-max", brand = "iPhone", model = "17 Pro Max", brandStyle = "plain",
            style = "overlay", prefix = "Shot on", showDate = false, exifMode = "off",
            exifText = "24mm  f/1.78  1/120s  ISO80", builtIn = true,
        ),
        Preset(
            id = "pixel-10-pro", brand = "Google", model = "Pixel 10 Pro", brandStyle = "plain",
            style = "frame-split", theme = "dark", exifText = "25mm  f/1.68  1/120s  ISO50", builtIn = true,
        ),
        Preset(
            id = "oneplus-15", brand = "OnePlus", model = "15", brandStyle = "bold", style = "frame-split",
            theme = "dark", badge = Badge(type = "box", text = "DetailMax", bg = "#eb0029", fg = "#ffffff"),
            exifText = "24mm  f/1.8  1/100s  ISO100", builtIn = true,
        ),
        Preset(
            id = "leica-m11", brand = "Leica", model = "M11", brandStyle = "serif", style = "frame-split",
            border = true, badge = LEICA, exifText = "35mm  f/2  1/250s  ISO64", builtIn = true,
        ),
        Preset(
            id = "sony-a7-iv", brand = "SONY", model = "α7 IV", brandStyle = "wide", style = "frame-split",
            theme = "dark", badge = Badge(type = "text", text = "α", font = "serif"),
            exifText = "50mm  f/1.8  1/200s  ISO100", builtIn = true,
        ),
        Preset(
            id = "fujifilm-x100vi", brand = "FUJIFILM", model = "X100VI", brandStyle = "wide",
            style = "frame-center", border = true, exifText = "23mm  f/2  1/500s  ISO125", builtIn = true,
        ),
    )
}
