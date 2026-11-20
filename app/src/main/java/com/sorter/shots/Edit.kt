package com.sorter.shots

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Draws on, hides parts of, and crops a picture. Everything works on a copy until you press Done.
class EditView(ctx: Context, private var bmp: Bitmap) : View(ctx) {
    var tool = "draw" // draw | hide | crop
    var color = 0xFFFF3B30.toInt()

    private var cv = Canvas(bmp)
    private val undo = ArrayList<Bitmap>()
    private val m = Matrix()
    private val inv = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crop = RectF()
    private var lx = 0f
    private var ly = 0f
    private var edge = -1
    private val dens = ctx.resources.displayMetrics.density

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        dim.color = 0x99000000.toInt()
        line.color = Color.WHITE
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2f * dens
        fill.color = Color.WHITE
        resetCrop()
    }

    fun result(): Bitmap = bmp

    fun canUndo(): Boolean = undo.isNotEmpty()

    private fun resetCrop() {
        crop.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
    }

    private fun fit() {
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
        m.invert(inv)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun pt(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return p
    }

    private fun pushUndo() {
        undo.add(bmp.copy(Bitmap.Config.ARGB_8888, true))
        if (undo.size > 6) undo.removeAt(0)
    }

    fun undoLast() {
        if (undo.isEmpty()) return
        bmp = undo.removeAt(undo.size - 1)
        cv = Canvas(bmp)
        resetCrop()
        fit()
        invalidate()
    }

    fun applyCrop() {
        val l = crop.left.toInt().coerceIn(0, bmp.width - 1)
        val t = crop.top.toInt().coerceIn(0, bmp.height - 1)
        val w = (crop.right.toInt() - l).coerceIn(1, bmp.width - l)
        val h = (crop.bottom.toInt() - t).coerceIn(1, bmp.height - t)
        if (w < 8 || h < 8) return
        pushUndo()
        val part = Bitmap.createBitmap(bmp, l, t, w, h)
        bmp = part.copy(Bitmap.Config.ARGB_8888, true)
        cv = Canvas(bmp)
        resetCrop()
        fit()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(bmp, m, bmpPaint)
        if (tool == "crop") {
            val r = RectF(crop)
            m.mapRect(r)
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawRect(0f, 0f, w, r.top, dim)
            c.drawRect(0f, r.bottom, w, h, dim)
            c.drawRect(0f, r.top, r.left, r.bottom, dim)
            c.drawRect(r.right, r.top, w, r.bottom, dim)
            c.drawRect(r, line)
            val hs = 9f * dens
            c.drawCircle(r.left, (r.top + r.bottom) / 2f, hs, fill)
            c.drawCircle(r.right, (r.top + r.bottom) / 2f, hs, fill)
            c.drawCircle((r.left + r.right) / 2f, r.top, hs, fill)
            c.drawCircle((r.left + r.right) / 2f, r.bottom, hs, fill)
        }
    }

    private fun nearestEdge(x: Float, y: Float): Int {
        val r = RectF(crop)
        m.mapRect(r)
        val slop = 48f * dens
        val dist = floatArrayOf(
            Math.abs(x - r.left), Math.abs(y - r.top), Math.abs(x - r.right), Math.abs(y - r.bottom)
        )
        var best = -1
        var bd = slop
        for (i in 0..3) {
            val ok = if (i % 2 == 0) (y > r.top - slop && y < r.bottom + slop)
            else (x > r.left - slop && x < r.right + slop)
            if (ok && dist[i] < bd) {
                bd = dist[i]
                best = i
            }
        }
        return best
    }

    private fun moveEdge(px: Float, py: Float) {
        val min = 24f
        when (edge) {
            0 -> crop.left = px.coerceIn(0f, crop.right - min)
            1 -> crop.top = py.coerceIn(0f, crop.bottom - min)
            2 -> crop.right = px.coerceIn(crop.left + min, bmp.width.toFloat())
            3 -> crop.bottom = py.coerceIn(crop.top + min, bmp.height.toFloat())
        }
    }

    private fun brush(): Float = maxOf(bmp.width, bmp.height) * 0.03f

    // "Hide" tool: turns the area under your finger into big blocks so text can't be read.
    private fun pixelate(cx: Float, cy: Float) {
        val r = brush()
        val left = (cx - r).toInt().coerceAtLeast(0)
        val top = (cy - r).toInt().coerceAtLeast(0)
        val right = (cx + r).toInt().coerceAtMost(bmp.width)
        val bottom = (cy + r).toInt().coerceAtMost(bmp.height)
        val w = right - left
        val h = bottom - top
        if (w <= 4 || h <= 4) return
        val block = maxOf(8, minOf(bmp.width, bmp.height) / 55)
        val region = Bitmap.createBitmap(bmp, left, top, w, h)
        val small = Bitmap.createScaledBitmap(region, maxOf(1, w / block), maxOf(1, h / block), true)
        val big = Bitmap.createScaledBitmap(small, w, h, false)
        cv.drawBitmap(big, left.toFloat(), top.toFloat(), null)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = pt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tool == "crop") {
                    edge = nearestEdge(e.x, e.y)
                } else {
                    pushUndo()
                    lx = p[0]
                    ly = p[1]
                    if (tool == "draw") {
                        stroke.color = color
                        stroke.strokeWidth = maxOf(6f, maxOf(bmp.width, bmp.height) * 0.006f)
                        cv.drawPoint(lx, ly, stroke)
                    } else {
                        pixelate(lx, ly)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (tool == "crop") {
                    if (edge >= 0) {
                        moveEdge(p[0], p[1])
                        invalidate()
                    }
                } else if (tool == "draw") {
                    cv.drawLine(lx, ly, p[0], p[1], stroke)
                    lx = p[0]
                    ly = p[1]
                    invalidate()
                } else {
                    val dx = p[0] - lx
                    val dy = p[1] - ly
                    if (dx * dx + dy * dy > (brush() / 2f) * (brush() / 2f)) {
                        pixelate(p[0], p[1])
                        lx = p[0]
                        ly = p[1]
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edge = -1
        }
        return true
    }
}

class EditActivity : Activity() {
    private lateinit var file: File
    private lateinit var ev: EditView
    private var fromPopup = false
    private val toolViews = HashMap<String, TextView>()
    private lateinit var colorRow: LinearLayout
    private lateinit var cropApply: TextView

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = Ui.text(this, label, 14f, Color.WHITE, Ui.medium())
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        t.background = if (primary) Ui.accentBg(this, 16) else Ui.glassDark(this, 16)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun loadMutable(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 2400) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            o2.inMutable = true
            val b = BitmapFactory.decodeFile(f.path, o2) ?: return null
            if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        file = File(intent.getStringExtra("path") ?: "")
        fromPopup = intent.getBooleanExtra("popup", false)
        val bmp = loadMutable(file)
        if (bmp == null) {
            Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
            close()
            return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        ev = EditView(this, bmp)
        root.addView(ev, FrameLayout.LayoutParams(MATCH, MATCH))

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val cancel = pill("✕", false) { close() }
        val undo = pill("Undo", false) { ev.undoLast() }
        val done = pill("Done", true) { save() }
        val spacer = View(this)
        top.addView(cancel)
        top.addView(undo, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(done)
        val topLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
        topLp.setMargins(dp(12), dp(12), dp(12), 0)
        root.addView(top, topLp)

        // bottom tools
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = Ui.glassDark(this, 22)
        bottom.setPadding(dp(10), dp(10), dp(10), dp(10))

        colorRow = LinearLayout(this)
        colorRow.orientation = LinearLayout.HORIZONTAL
        colorRow.gravity = Gravity.CENTER
        val colors = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(),
            0xFF0A84FF.toInt(), Color.WHITE, Color.BLACK
        )
        for (c in colors) {
            val dot = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(c)
            g.setStroke(dp(2), Color.WHITE)
            dot.background = g
            dot.setOnClickListener { ev.color = c }
            val lp = LinearLayout.LayoutParams(dp(30), dp(30))
            lp.setMargins(dp(8), dp(2), dp(8), dp(10))
            colorRow.addView(dot, lp)
        }
        bottom.addView(colorRow, LinearLayout.LayoutParams(MATCH, WRAP))

        cropApply = pill("Apply crop", true) { ev.applyCrop() }
        cropApply.visibility = View.GONE
        val cLp = LinearLayout.LayoutParams(MATCH, WRAP)
        cLp.bottomMargin = dp(8)
        bottom.addView(cropApply, cLp)

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        val names = listOf("draw" to "✏  Draw", "hide" to "▦  Hide", "crop" to "⛶  Crop")
        for ((key, label) in names) {
            val t = pill(label, false) { selectTool(key) }
            toolViews[key] = t
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            tools.addView(t, lp)
        }
        bottom.addView(tools, LinearLayout.LayoutParams(MATCH, WRAP))
        val bLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        bLp.setMargins(dp(12), 0, dp(12), dp(12))
        root.addView(bottom, bLp)

        setContentView(root)
        selectTool("draw")
    }

    private fun selectTool(t: String) {
        ev.tool = t
        for ((k, v) in toolViews) {
            v.background = if (k == t) Ui.accentBg(this, 16) else Ui.glassDark(this, 16)
        }
        colorRow.visibility = if (t == "draw") View.VISIBLE else View.GONE
        cropApply.visibility = if (t == "crop") View.VISIBLE else View.GONE
        ev.invalidate()
    }

    private fun save() {
        try {
            val bmp = ev.result()
            val lm = file.lastModified()
            val fmt = when (file.extension.lowercase()) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }
            FileOutputStream(file).use { bmp.compress(fmt, 95, it) }
            file.setLastModified(lm)
            Thumbs.invalidate(file.path)
            Ops.scan(this, listOf(file.path))
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
        }
        close()
    }

    // Back to the "Save to…" popup if we came from it.
    private fun close() {
        if (fromPopup && file.exists()) showPopupFor(applicationContext, file, null) {}
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        close()
    }
}
