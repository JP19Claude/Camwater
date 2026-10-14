package app.camwater

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Kameradaten des Fotos, die im Watermark angezeigt werden. */
data class PhotoInfo(val cameraData: String = "", val date: Date? = null)

/**
 * Zeichnet Foto + Watermark. Alle Größen sind relativ zur kürzeren Bildseite,
 * damit das Watermark auf jedem Foto gleich aussieht.
 */
object WatermarkRenderer {
    private val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val SANS_SEMIBOLD: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    private val SANS_BLACK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val SANS_BOLD_ITALIC: Typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val SERIF: Typeface = Typeface.create("serif", Typeface.NORMAL)
    private val SERIF_BOLD: Typeface = Typeface.create("serif", Typeface.BOLD)
    private val SCRIPT: Typeface = Typeface.create("cursive", Typeface.BOLD)

    private class Theme(val bg: Int, val title: Int, val sub: Int, val sep: Int)

    private val LIGHT = Theme(0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF8A8A8A.toInt(), 0xFFD2D2D2.toInt())
    private val DARK = Theme(0xFF0E0E0E.toInt(), 0xFFF4F4F4.toInt(), 0xFF8F8F8F.toInt(), 0xFF3D3D3D.toInt())

    /** Ein Textstück; spacing ist der Buchstabenabstand in em. */
    private class Run(val text: String, val size: Float, val typeface: Typeface, val spacing: Float, val color: Int)

    private class Brand(val typeface: Typeface, val spacing: Float)

    private fun brandStyle(name: String) = when (name) {
        "plain" -> Brand(SANS, 0f)
        "round" -> Brand(SANS_BLACK, -0.02f)
        "wide" -> Brand(SANS_BOLD, 0.14f)
        "italic" -> Brand(SANS_BOLD_ITALIC, 0f)
        "serif" -> Brand(SERIF_BOLD, 0f)
        else -> Brand(SANS_BOLD, 0f)
    }

    /** Hält Canvas, Paint und das (optionale) Logo-Bild für einen Zeichenvorgang. */
    private class Ctx(val canvas: Canvas, val badgeImage: Bitmap?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG)
        var shadowRadius = 0f
        var shadowDy = 0f

        fun reset(color: Int): Paint {
            paint.reset()
            paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG
            paint.color = color
            paint.style = Paint.Style.FILL
            if (shadowRadius > 0f) paint.setShadowLayer(shadowRadius, 0f, shadowDy, 0x73000000)
            return paint
        }

        fun text(run: Run): Paint = reset(run.color).also {
            it.typeface = run.typeface
            it.textSize = max(1f, run.size)
            it.letterSpacing = run.spacing
        }
    }

    fun parseColor(hex: String, fallback: Int): Int = runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    /* ---------- Textzeilen ---------- */

    private fun titleRuns(p: Preset, size: Float, color: Int): List<Run> {
        val runs = mutableListOf<Run>()
        if (p.prefix.isNotBlank()) runs += Run(p.prefix, size, SANS, 0f, color)
        if (p.brand.isNotBlank()) {
            val b = brandStyle(p.brandStyle)
            runs += Run(p.brand, size, b.typeface, b.spacing, color)
        }
        if (p.model.isNotBlank()) runs += Run(p.model, size, SANS_BOLD, 0f, color)
        return runs
    }

    private fun textRun(text: String, size: Float, color: Int, typeface: Typeface = SANS): List<Run> =
        if (text.isBlank()) emptyList() else listOf(Run(text, size, typeface, 0f, color))

    private fun measureRuns(c: Ctx, runs: List<Run>): Float {
        var w = 0f
        runs.forEachIndexed { i, r ->
            w += c.text(r).measureText(r.text)
            if (i < runs.size - 1) w += r.size * 0.28f
        }
        return w
    }

    private fun drawRuns(c: Ctx, runs: List<Run>, x0: Float, baseline: Float) {
        var x = x0
        for (r in runs) {
            val p = c.text(r)
            c.canvas.drawText(r.text, x, baseline, p)
            x += p.measureText(r.text) + r.size * 0.28f
        }
    }

    /* ---------- Partner-Logo ---------- */

    private class BadgeFont(val size: Float, val typeface: Typeface, val spacing: Float)

    private fun badgeFont(font: String, h: Float) = when (font) {
        "wide" -> BadgeFont(h * 0.5f, SANS_BOLD, 0.2f)
        "serif" -> BadgeFont(h * 0.9f, SERIF, 0f)
        "script" -> BadgeFont(h * 0.62f, SCRIPT, 0f)
        else -> BadgeFont(h * 0.56f, SANS_BOLD, 0f)
    }

    private fun badgeWidth(c: Ctx, b: Badge, h: Float): Float {
        when (b.type) {
            "none" -> return 0f
            "circle" -> return h
            "image" -> {
                val img = c.badgeImage ?: return 0f
                return h * img.width / max(1, img.height)
            }
        }
        if (b.text.isBlank()) return 0f
        if (b.type == "box") {
            val p = c.reset(0).apply { typeface = SANS_BOLD; textSize = h * 0.46f }
            return p.measureText(b.text) + h * 0.7f
        }
        val f = badgeFont(b.font, h)
        val p = c.reset(0).apply { typeface = f.typeface; textSize = f.size; letterSpacing = f.spacing }
        return p.measureText(b.text)
    }

    private fun drawBadge(c: Ctx, b: Badge, x: Float, cy: Float, h: Float, autoColor: Int): Float {
        val w = badgeWidth(c, b, h)
        if (w <= 0f) return 0f
        val top = cy - h / 2
        when (b.type) {
            "image" -> c.canvas.drawBitmap(c.badgeImage!!, null, RectF(x, top, x + w, top + h), c.reset(Color.WHITE))
            "box" -> {
                c.canvas.drawRoundRect(RectF(x, top, x + w, top + h), h * 0.1f, h * 0.1f, c.reset(parseColor(b.bg, 0xFF1D4E9E.toInt())))
                val p = c.reset(parseColor(b.fg, Color.WHITE)).apply {
                    typeface = SANS_BOLD
                    textSize = h * 0.46f
                    textAlign = Paint.Align.CENTER
                }
                c.canvas.drawText(b.text, x + w / 2, cy + p.textSize * 0.36f, p)
            }
            "circle" -> {
                c.canvas.drawCircle(x + h / 2, cy, h / 2, c.reset(parseColor(b.bg, 0xFFE20612.toInt())))
                val f = badgeFont(b.font.ifBlank { "script" }, h)
                val p = c.reset(parseColor(b.fg, Color.WHITE)).apply {
                    typeface = f.typeface
                    textSize = h * 0.42f
                    textAlign = Paint.Align.CENTER
                }
                val tw = p.measureText(b.text)
                if (tw > h * 0.8f) p.textSize = p.textSize * (h * 0.8f) / tw
                c.canvas.drawText(b.text, x + h / 2, cy + p.textSize * 0.33f, p)
            }
            else -> {
                val f = badgeFont(b.font, h)
                val color = if (b.fg.isBlank() || b.fg == "auto") autoColor else parseColor(b.fg, autoColor)
                val p = c.reset(color).apply { typeface = f.typeface; textSize = f.size; letterSpacing = f.spacing }
                c.canvas.drawText(b.text, x, cy + f.size * 0.36f, p)
            }
        }
        return w
    }

    /* ---------- Texte für das Watermark ---------- */

    private val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.GERMANY)

    fun formatDate(d: Date?): String = if (d == null) "" else synchronized(DATE_FORMAT) { DATE_FORMAT.format(d) }

    private class Lines(val cam: String, val date: String, val subtitle: String)

    private fun lines(p: Preset, info: PhotoInfo) = Lines(
        cam = when (p.exifMode) {
            "photo" -> info.cameraData.ifBlank { p.exifText }
            "custom" -> p.exifText
            else -> ""
        },
        date = if (p.showDate) formatDate(info.date) else "",
        subtitle = p.subtitle,
    )

    /* ---------- Größen ---------- */

    private fun border(p: Preset, u: Float) = if (p.border && p.style != "overlay") (2.4f * u).roundToInt() else 0

    /** Größe des fertigen Bildes für ein Foto der Größe w × h. */
    fun outputSize(p: Preset, w: Int, h: Int): Pair<Int, Int> {
        if (p.style == "overlay") return w to h
        val u = min(w, h) / 100f
        val b = border(p, u)
        val bar = ((if (p.style == "frame-center") 13f else 10.5f) * u).roundToInt()
        return (w + 2 * b) to (h + b + bar)
    }

    /** Bereich des fertigen Bildes, in dem das Watermark sitzt (für Vorschau-Kacheln). */
    fun watermarkArea(p: Preset, w: Int, h: Int): Rect {
        val (width, height) = outputSize(p, w, h)
        val u = min(w, h) / 100f
        if (p.style == "overlay") {
            val ch = (16f * u).roundToInt()
            return Rect(0, height - ch, width, height)
        }
        val top = max(0, (border(p, u) + h - 6f * u).roundToInt())
        return Rect(0, top, width, height)
    }

    /* ---------- Zeichnen ---------- */

    /**
     * Zeichnet [source] in der Größe w × h mit Watermark.
     * [badgeImage] ist das eigene Logo-Bild (nur bei Badge-Typ "image").
     */
    fun render(source: Bitmap, w: Int, h: Int, p: Preset, info: PhotoInfo, badgeImage: Bitmap? = null): Bitmap {
        val (width, height) = outputSize(p, w, h)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Ctx(Canvas(out), badgeImage)
        val theme = if (p.theme == "dark") DARK else LIGHT
        val l = lines(p, info)
        val u = min(w, h) / 100f

        if (p.style == "overlay") {
            c.canvas.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), c.reset(Color.WHITE))
            drawOverlay(c, p, l, w.toFloat(), h.toFloat(), u)
            return out
        }

        val b = border(p, u)
        c.canvas.drawColor(theme.bg)
        c.canvas.drawBitmap(source, null, RectF(b.toFloat(), b.toFloat(), (b + w).toFloat(), (b + h).toFloat()), c.reset(Color.WHITE))
        val barTop = (b + h).toFloat()
        val barH = (height - b - h).toFloat()
        if (p.style == "frame-center") drawFrameCenter(c, p, l, theme, width.toFloat(), barTop, barH, u)
        else drawFrameSplit(c, p, l, theme, width.toFloat(), barTop, barH, u, b.toFloat())
        return out
    }

    /** Grundlinien für Titel (Größe t) und Unterzeile (Größe s), zentriert um cy. */
    private fun twoLines(cy: Float, t: Float, s: Float, gap: Float): Pair<Float, Float> {
        val y1 = (2 * cy + 0.72f * t - gap - 0.72f * s) / 2
        return y1 to (y1 + gap + 0.72f * s)
    }

    private fun drawFrameSplit(c: Ctx, p: Preset, l: Lines, t: Theme, width: Float, barTop: Float, barH: Float, u: Float, b: Float) {
        val padX = if (b > 0) b + 1.6f * u else 4.2f * u
        val cy = barTop + barH / 2
        val avail = width - 2 * padX

        class L(k: Float) {
            val T = 3.0f * u * k
            val S = 2.0f * u * k
            val C = 2.5f * u * k
            val gap = 1.1f * u * k
            val bh = (when (p.badge.type) { "circle" -> 5.6f; "box" -> 4.4f; else -> 4.8f }) * u * k
            val sepGap = 1.8f * u * k
            val title = titleRuns(p, T, t.title)
            val sub = textRun(l.subtitle, S, t.sub)
            var top = textRun(l.cam, C, t.title, SANS_SEMIBOLD)
            var rsub = textRun(l.date, S, t.sub)
            init {
                if (top.isEmpty() && rsub.isNotEmpty()) {
                    top = textRun(l.date, C, t.title, SANS_SEMIBOLD)
                    rsub = emptyList()
                }
            }
            val leftW = max(measureRuns(c, title), measureRuns(c, sub))
            val textW = max(measureRuns(c, top), measureRuns(c, rsub))
            val bw = badgeWidth(c, p.badge, bh)
            val rightW = textW + if (bw > 0) bw + (if (textW > 0) 2 * sepGap else 0f) else 0f
        }

        var lay = L(1f)
        val need = lay.leftW + lay.rightW + 3 * u
        if (need > avail) lay = L(max(0.35f, avail / need))

        if (lay.sub.isNotEmpty()) {
            val (y1, y2) = twoLines(cy, lay.T, lay.S, lay.gap)
            drawRuns(c, lay.title, padX, y1)
            drawRuns(c, lay.sub, padX, y2)
        } else {
            drawRuns(c, lay.title, padX, cy + 0.36f * lay.T)
        }

        val xRight = width - padX
        val textX = xRight - lay.textW
        if (lay.rsub.isNotEmpty()) {
            val (y1, y2) = twoLines(cy, lay.C, lay.S, lay.gap)
            drawRuns(c, lay.top, textX, y1)
            drawRuns(c, lay.rsub, textX, y2)
        } else if (lay.top.isNotEmpty()) {
            drawRuns(c, lay.top, textX, cy + 0.36f * lay.C)
        }
        if (lay.bw > 0) {
            var bx = xRight - lay.bw
            if (lay.textW > 0) {
                val sepX = textX - lay.sepGap
                val sh = max(lay.bh, 5.4f * u)
                val sw = max(1f, 0.16f * u)
                c.canvas.drawRect(sepX - sw / 2, cy - sh / 2, sepX + sw / 2, cy + sh / 2, c.reset(t.sep))
                bx = sepX - lay.sepGap - lay.bw
            }
            drawBadge(c, p.badge, bx, cy, lay.bh, t.title)
        }
    }

    private fun drawFrameCenter(c: Ctx, p: Preset, l: Lines, t: Theme, width: Float, barTop: Float, barH: Float, u: Float) {
        val avail = width - 8 * u

        class L(k: Float) {
            val T = 3.2f * u * k
            val S = 2.0f * u * k
            val bh = (if (p.badge.type == "circle") 5f else 3.9f) * u * k
            val g = 1.8f * u * k
            val title = titleRuns(p, T, t.title)
            val sub = textRun(listOf(l.subtitle, l.cam, l.date).filter { it.isNotBlank() }.joinToString("     "), S, t.sub)
            val tw = measureRuns(c, title)
            val bw = badgeWidth(c, p.badge, bh)
            val row1 = tw + if (bw > 0) bw + (if (tw > 0) 2 * g + 0.16f * u else 0f) else 0f
            val row2 = measureRuns(c, sub)
        }

        var lay = L(1f)
        val widest = max(lay.row1, lay.row2)
        if (widest > avail) lay = L(max(0.35f, avail / widest))

        val hasSub = lay.sub.isNotEmpty()
        val cy1 = if (hasSub) barTop + barH * 0.4f else barTop + barH / 2
        var x = (width - lay.row1) / 2
        if (lay.bw > 0) {
            drawBadge(c, p.badge, x, cy1, lay.bh, t.title)
            x += lay.bw
            if (lay.tw > 0) {
                x += lay.g
                val sh = max(lay.bh, lay.T * 1.1f)
                c.canvas.drawRect(x, cy1 - sh / 2, x + max(1f, 0.16f * u), cy1 + sh / 2, c.reset(t.sep))
                x += 0.16f * u + lay.g
            }
        }
        drawRuns(c, lay.title, x, cy1 + 0.36f * lay.T)
        if (hasSub) drawRuns(c, lay.sub, (width - lay.row2) / 2, barTop + barH * 0.72f + 0.36f * lay.S)
    }

    private fun drawOverlay(c: Ctx, p: Preset, l: Lines, w: Float, h: Float, u: Float) {
        // "Hell" = weiße Schrift mit Schatten, "Dunkel" = schwarze Schrift.
        val light = p.theme != "dark"
        val color = if (light) Color.WHITE else 0xFF111111.toInt()
        val sub = if (light) 0xDBFFFFFF.toInt() else 0xB8000000.toInt()
        val margin = 4.2f * u
        val avail = w - 2 * margin

        class L(k: Float) {
            val T = 3.1f * u * k
            val S = 1.9f * u * k
            val gap = 1.0f * u * k
            val bh = (if (p.badge.type == "circle") 5.6f else 4.4f) * u * k
            val g = 1.6f * u * k
            val title = titleRuns(p, T, color)
            val subRuns = textRun(listOf(l.subtitle, l.cam, l.date).filter { it.isNotBlank() }.joinToString("   "), S, sub)
            val tw = max(measureRuns(c, title), measureRuns(c, subRuns))
            val bw = badgeWidth(c, p.badge, bh)
            val total = tw + if (bw > 0) bw + 2 * g else 0f
        }

        var lay = L(1f)
        if (lay.total > avail) lay = L(max(0.35f, avail / lay.total))

        if (light) {
            c.shadowRadius = 0.8f * u
            c.shadowDy = 0.12f * u
        }
        val blockH = if (lay.subRuns.isNotEmpty()) 0.72f * lay.T + lay.gap + 0.72f * lay.S else 0.72f * lay.T
        val cy = h - margin - blockH / 2
        var x = margin
        if (lay.bw > 0) {
            drawBadge(c, p.badge, x, cy, lay.bh, color)
            x += lay.bw + lay.g
            val sh = max(lay.bh, blockH * 1.1f)
            val sw = max(1f, 0.16f * u)
            c.canvas.drawRect(x - sw / 2, cy - sh / 2, x + sw / 2, cy + sh / 2, c.reset(sub))
            x += lay.g
        }
        if (lay.subRuns.isNotEmpty()) {
            val (y1, y2) = twoLines(cy, lay.T, lay.S, lay.gap)
            drawRuns(c, lay.title, x, y1)
            drawRuns(c, lay.subRuns, x, y2)
        } else {
            drawRuns(c, lay.title, x, cy + 0.36f * lay.T)
        }
        c.shadowRadius = 0f
    }

    /* ---------- Beispielbild für die Vorschau ---------- */

    fun demoPhoto(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val horizon = h * 0.64f
        val wf = w.toFloat()

        p.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(0xFF1C2A5E.toInt(), 0xFFB9557C.toInt(), 0xFFF3A45C.toInt(), 0xFFFFD9A0.toInt()),
            floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, wf, horizon + 2, p)

        val sx = w * 0.63f
        val sy = horizon - h * 0.07f
        val sr = min(w, h) * 0.085f
        p.shader = RadialGradient(sx, sy, sr * 5, 0xE6FFF0C8.toInt(), 0x00FFC88C, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, wf, horizon, p)
        p.shader = null
        p.color = 0xFFFFF4D6.toInt()
        cv.drawCircle(sx, sy, sr, p)

        fun ridge(base: Float, amp: Float, color: Int, f1: Float, f2: Float, ph: Float) {
            val path = Path()
            path.moveTo(0f, horizon)
            var x = 0f
            while (x <= wf) {
                val t = x / wf
                val y = base - amp * (0.55f * sin(t * f1 + ph) + 0.35f * sin(t * f2 + ph * 2.1f) + 0.1f * sin(t * 37 + ph))
                path.lineTo(x, y)
                x += wf / 160
            }
            path.lineTo(wf, horizon)
            path.close()
            p.color = color
            cv.drawPath(path, p)
        }
        ridge(horizon - h * 0.14f, h * 0.1f, 0xFF7B4A78.toInt(), 5.1f, 11.3f, 0.4f)
        ridge(horizon - h * 0.06f, h * 0.08f, 0xFF4B2C55.toInt(), 7.3f, 15.7f, 1.7f)
        ridge(horizon, h * 0.05f, 0xFF2A1A36.toInt(), 9.1f, 21.3f, 2.9f)

        p.shader = LinearGradient(
            0f, horizon, 0f, h.toFloat(),
            intArrayOf(0xFFE59A6A.toInt(), 0xFF7D4A6E.toInt(), 0xFF1B1730.toInt()),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, horizon, wf, h.toFloat(), p)
        p.shader = null
        p.color = 0x8CFFECC8.toInt()
        for (i in 0 until 9) {
            val y = horizon + (h - horizon) * 0.55f * (i / 9f).pow(1.6f) + 2
            val len = sr * (2.2f - i * 0.12f)
            cv.drawRect(sx - len / 2, y, sx + len / 2, y + max(1f, h * 0.003f), p)
        }
        return bmp
    }

    /** Rendert die Vorlage auf [source] und schneidet den Watermark-Bereich aus. */
    fun renderCrop(source: Bitmap, p: Preset, info: PhotoInfo, badgeImage: Bitmap?): Bitmap {
        val full = render(source, source.width, source.height, p, info, badgeImage)
        val a = watermarkArea(p, source.width, source.height)
        val crop = Bitmap.createBitmap(full, a.left, a.top, a.width(), a.height())
        if (crop !== full) full.recycle()
        return crop
    }
}
