package com.sorter.shots

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ---------------------------------------------------------------- theme

object Theme {
    val SWATCHES = intArrayOf(
        0xFF5B8CFF.toInt(), 0xFFFF6B5B.toInt(), 0xFFFF9A52.toInt(), 0xFFFFC533.toInt(),
        0xFFA2674C.toInt(), 0xFFCFD617.toInt(), 0xFFA3DB52.toInt(), 0xFF4CCB63.toInt(),
        0xFF18C193.toInt(), 0xFF4AB1E8.toInt(), 0xFF528BE0.toInt(), 0xFF5568E8.toInt(),
        0xFF9964EE.toInt(), 0xFFD955E5.toInt(), 0xFFFF5290.toInt(), 0xFFE64590.toInt(),
        0xFF9AA3AF.toInt(), 0xFF2DD4BF.toInt()
    )

    var mode = "dark"
    var accent = 0xFF5B8CFF.toInt()
    var dark = true
    var amoled = false

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        mode = sp.getString("mode", "dark") ?: "dark"
        accent = sp.getInt("accent", SWATCHES[0])
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        dark = when (mode) {
            "light" -> false
            "adaptive" -> night
            else -> true
        }
        amoled = mode == "amoled"
    }

    private fun shift(deg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    fun accent2(): Int = shift(28f)
    fun accent3(): Int = shift(-45f)
    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    fun bgTop(): Int = if (!dark) 0xFFF4F6FC.toInt() else if (amoled) 0xFF000000.toInt() else 0xFF0B0F1E.toInt()
    fun bgMid(): Int = if (!dark) 0xFFEBEFFA.toInt() else if (amoled) 0xFF000000.toInt() else 0xFF141A33.toInt()
    fun bgBottom(): Int = if (!dark) 0xFFF1EBFA.toInt() else if (amoled) 0xFF050509.toInt() else 0xFF1B1238.toInt()
}

// ---------------------------------------------------------------- ui helpers

object Ui {
    const val GREEN = 0xFF4ADE80.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun light(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun fg(): Int = if (Theme.dark) Color.WHITE else 0xFF1B2030.toInt()
    fun muted(): Int = if (Theme.dark) 0xFFAAB3C8.toInt() else 0xFF5E6783.toInt()

    fun text(ctx: Context, s: String, size: Float, color: Int = fg(), face: Typeface? = null): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (face != null) t.typeface = face
        return t
    }

    fun glass(ctx: Context, radius: Int): GradientDrawable {
        val colors = if (Theme.dark) intArrayOf(0x30FFFFFF, 0x12FFFFFF)
        else intArrayOf(0xCCFFFFFF.toInt(), 0x99FFFFFF.toInt())
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        g.cornerRadius = dp(ctx, radius).toFloat()
        g.setStroke(dp(ctx, 1), if (Theme.dark) 0x3CFFFFFF else 0x22000000)
        return g
    }

    // Always-dark glass (used over photos).
    fun glassDark(ctx: Context, radius: Int): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x66000000, 0x44000000)
        )
        g.cornerRadius = dp(ctx, radius).toFloat()
        g.setStroke(dp(ctx, 1), 0x33FFFFFF)
        return g
    }

    fun accentBg(ctx: Context, radius: Int): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Theme.accent, Theme.accent2())
        )
        g.cornerRadius = dp(ctx, radius).toFloat()
        return g
    }

    fun selectedBg(ctx: Context, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Theme.withAlpha(Theme.accent, 0x44))
        g.cornerRadius = dp(ctx, radius).toFloat()
        g.setStroke(dp(ctx, 2), Theme.accent)
        return g
    }

    fun dangerBg(ctx: Context, radius: Int): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x66FF4D4D, 0x33FF4D4D)
        )
        g.cornerRadius = dp(ctx, radius).toFloat()
        g.setStroke(dp(ctx, 1), 0x66FF8080)
        return g
    }

    fun ripple(ctx: Context, content: GradientDrawable, radius: Int): RippleDrawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, radius).toFloat()
        val rc = if (Theme.dark) 0x40FFFFFF else 0x30000000
        return RippleDrawable(ColorStateList.valueOf(rc), content, mask)
    }

    fun round(v: View, radiusPx: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        v.clipToOutline = true
    }

    fun styleWindow(act: Activity) {
        act.window.statusBarColor = Theme.bgTop()
        act.window.navigationBarColor = Theme.bgBottom()
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        act.window.insetsController?.setSystemBarsAppearance(if (Theme.dark) 0 else flags, flags)
    }

    fun fadeIn(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(280).start()
    }

    fun panelBg(ctx: Context): GradientDrawable {
        val colors = if (!Theme.dark) intArrayOf(0xF5FFFFFF.toInt(), 0xF2EEF2FA.toInt())
        else if (Theme.amoled) intArrayOf(0xEE15151C.toInt(), 0xEE0A0A0F.toInt())
        else intArrayOf(0xEE283050.toInt(), 0xEE171C33.toInt())
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        bg.cornerRadius = dp(ctx, 26).toFloat()
        bg.setStroke(dp(ctx, 1), if (Theme.dark) 0x40FFFFFF else 0x22000000)
        return bg
    }

    fun panel(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14))
        c.background = panelBg(ctx)
        c.isClickable = true
        return c
    }

    fun button(ctx: Context, label: String, primary: Boolean, center: Boolean, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 15f, if (primary) Color.WHITE else fg(), medium())
        t.gravity = if (center) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)
        t.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13))
        val base = if (primary) accentBg(ctx, 14) else glass(ctx, 14)
        t.background = ripple(ctx, base, 14)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(ctx, 6)
        t.layoutParams = lp
        return t
    }

    fun dangerButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val b = button(ctx, label, false, true, onClick)
        b.background = ripple(ctx, dangerBg(ctx, 14), 14)
        return b
    }

    fun iconButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 18f, fg(), medium())
        t.gravity = Gravity.CENTER
        t.background = ripple(ctx, glass(ctx, 14), 14)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        val s = dp(ctx, 44)
        val lp = LinearLayout.LayoutParams(s, s)
        lp.leftMargin = dp(ctx, 8)
        t.layoutParams = lp
        return t
    }

    fun sectionLabel(ctx: Context, s: String): TextView {
        val t = text(ctx, s, 12f, muted(), medium())
        t.letterSpacing = 0.12f
        t.setPadding(dp(ctx, 4), dp(ctx, 16), 0, dp(ctx, 2))
        return t
    }

    fun styleChip(ctx: Context, t: TextView, selected: Boolean) {
        t.setTextColor(if (selected) Color.WHITE else fg())
        t.background = ripple(ctx, if (selected) accentBg(ctx, 18) else glass(ctx, 18), 18)
    }

    // Rows of pill buttons where one is selected (like the sort picker).
    fun chips(ctx: Context, options: List<String>, selected: String, perRow: Int, onPick: (String) -> Unit): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val views = ArrayList<TextView>()
        var row: LinearLayout? = null
        for ((i, opt) in options.withIndex()) {
            if (i % perRow == 0) {
                val r = LinearLayout(ctx)
                r.orientation = LinearLayout.HORIZONTAL
                val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
                rlp.topMargin = dp(ctx, 6)
                box.addView(r, rlp)
                row = r
            }
            val t = text(ctx, opt, 13f, fg(), medium())
            t.gravity = Gravity.CENTER
            t.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10))
            t.maxLines = 1
            styleChip(ctx, t, opt == selected)
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0)
            row?.addView(t, lp)
            views.add(t)
            t.setOnClickListener {
                for ((j, v) in views.withIndex()) styleChip(ctx, v, options[j] == opt)
                onPick(opt)
            }
        }
        val rem = options.size % perRow
        val last = row
        if (rem != 0 && last != null) {
            repeat(perRow - rem) {
                val sp = View(ctx)
                val lp = LinearLayout.LayoutParams(0, 1, 1f)
                lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0)
                last.addView(sp, lp)
            }
        }
        return box
    }

    fun status(ctx: Context, ok: Boolean): TextView =
        if (ok) text(ctx, "✓", 18f, GREEN, medium()) else text(ctx, "Allow ›", 14f, Theme.accent, medium())

    fun onOffPill(ctx: Context, on: Boolean): TextView {
        val t = text(ctx, if (on) "ON" else "OFF", 12f, if (on) Color.WHITE else muted(), medium())
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
        t.background = if (on) accentBg(ctx, 14) else glass(ctx, 14)
        return t
    }

    // A settings-style row: title (+ optional note) on the left, something on the right.
    fun row(ctx: Context, label: String, note: String?, trailing: View?, onClick: (() -> Unit)?): View {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(ctx, label, 15f, fg(), medium()))
        if (note != null) {
            val n = text(ctx, note, 12f, muted())
            n.setPadding(0, dp(ctx, 2), dp(ctx, 8), 0)
            col.addView(n)
        }
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) r.addView(trailing)
        val clear = GradientDrawable()
        clear.setColor(Color.TRANSPARENT)
        r.background = ripple(ctx, clear, 12)
        if (onClick != null) {
            r.isClickable = true
            r.setOnClickListener { onClick() }
        }
        return r
    }
}

// ---------------------------------------------------------------- backdrop

class Backdrop(act: Activity, content: View) {
    val root = FrameLayout(act)
    private val anims = ArrayList<ValueAnimator>()

    init {
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Theme.bgTop(), Theme.bgMid(), Theme.bgBottom())
        )
        val dm = act.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val a1 = if (!Theme.dark) 0x66 else if (Theme.amoled) 0x44 else 0x88
        val a3 = if (!Theme.dark) 0x55 else if (Theme.amoled) 0x33 else 0x66
        orb(act, Theme.withAlpha(Theme.accent, a1), (w * 0.95f).toInt(), -w * 0.25f, h * 0.02f, 9000L)
        orb(act, Theme.withAlpha(Theme.accent2(), a1), (w * 0.85f).toInt(), w * 0.40f, h * 0.34f, 12000L)
        orb(act, Theme.withAlpha(Theme.accent3(), a3), (w * 0.75f).toInt(), -w * 0.15f, h * 0.68f, 10500L)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun orb(ctx: Context, color: Int, size: Int, x: Float, y: Float, ms: Long) {
        val v = View(ctx)
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(color)
        v.background = InsetDrawable(oval, 0.22f)
        v.setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.DECAL))
        root.addView(v, FrameLayout.LayoutParams(size, size))
        v.translationX = x
        v.translationY = y
        val amp = size * 0.12f
        val a = ValueAnimator.ofFloat(-1f, 1f)
        a.duration = ms
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        a.addUpdateListener {
            val f = it.animatedValue as Float
            v.translationX = x + f * amp
            v.translationY = y - f * amp * 0.6f
        }
        anims.add(a)
    }

    fun start() {
        for (a in anims) a.start()
    }

    fun stop() {
        for (a in anims) a.cancel()
    }
}

// ---------------------------------------------------------------- bottom sheets

object Sheet {
    class Item(val label: String, val style: Int, val onClick: () -> Unit)

    private fun host(act: Activity): FrameLayout {
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        val dim = FrameLayout(act)
        dim.setBackgroundColor(0x80000000.toInt())
        dim.isClickable = true
        content.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(160).start()
        return dim
    }

    private fun place(act: Activity, dim: FrameLayout, c: LinearLayout) {
        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        val m = Ui.dp(act, 12)
        lp.setMargins(m, m, m, m)
        dim.addView(c, lp)
        c.translationY = Ui.dp(act, 70).toFloat()
        c.alpha = 0f
        c.animate().translationY(0f).alpha(1f).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun close(dim: FrameLayout) {
        val imm = dim.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(dim.windowToken, 0)
        dim.animate().alpha(0f).setDuration(140).withEndAction {
            (dim.parent as? ViewGroup)?.removeView(dim)
        }.start()
    }

    private fun limited(act: Activity, frac: Double): ScrollView {
        val maxH = (act.resources.displayMetrics.heightPixels * frac).toInt()
        val sv = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        sv.isVerticalScrollBarEnabled = false
        return sv
    }

    fun show(act: Activity, title: String, subtitle: String?, items: List<Item>) {
        val dim = host(act)
        val c = Ui.panel(act)
        c.addView(Ui.text(act, title, 18f, Ui.fg(), Ui.medium()))
        if (subtitle != null) {
            val s = Ui.text(act, subtitle, 13f, Ui.muted())
            s.maxLines = 3
            s.ellipsize = TextUtils.TruncateAt.END
            s.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 4))
            c.addView(s)
        }
        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        for (item in items) {
            val b = Ui.button(act, item.label, item.style == 1, false) {
                close(dim)
                item.onClick()
            }
            if (item.style == 2) b.background = Ui.ripple(act, Ui.dangerBg(act, 14), 14)
            list.addView(b)
        }
        val sv = limited(act, 0.5)
        sv.addView(list)
        c.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(Ui.button(act, "Cancel", false, true) { close(dim) })
        dim.setOnClickListener { close(dim) }
        place(act, dim, c)
    }

    // A sheet whose body you fill yourself (used for "View & sort").
    fun panel(act: Activity, title: String, build: (LinearLayout) -> Unit) {
        val dim = host(act)
        val c = Ui.panel(act)
        c.addView(Ui.text(act, title, 18f, Ui.fg(), Ui.medium()))
        val body = LinearLayout(act)
        body.orientation = LinearLayout.VERTICAL
        build(body)
        val sv = limited(act, 0.68)
        sv.addView(body)
        c.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(Ui.button(act, "Done", true, true) { close(dim) })
        dim.setOnClickListener { close(dim) }
        place(act, dim, c)
    }

    fun input(act: Activity, title: String, initial: String, confirm: String, onDone: (String) -> Unit) {
        act.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val dim = host(act)
        val c = Ui.panel(act)
        c.addView(Ui.text(act, title, 18f, Ui.fg(), Ui.medium()))
        val et = EditText(act)
        et.setTextColor(Ui.fg())
        et.setHintTextColor(Ui.muted())
        et.hint = "Folder name"
        et.textSize = 16f
        et.typeface = Ui.medium()
        et.background = Ui.glass(act, 14)
        et.setPadding(Ui.dp(act, 16), Ui.dp(act, 13), Ui.dp(act, 16), Ui.dp(act, 13))
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.setText(initial)
        et.setSelection(et.text.length)
        val etLp = LinearLayout.LayoutParams(MATCH, WRAP)
        etLp.topMargin = Ui.dp(act, 12)
        c.addView(et, etLp)

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        val cancel = Ui.button(act, "Cancel", false, true) { close(dim) }
        val ok = Ui.button(act, confirm, true, true) {
            val v = et.text.toString()
            close(dim)
            onDone(v)
        }
        cancel.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val okLp = LinearLayout.LayoutParams(0, WRAP, 1.6f)
        okLp.leftMargin = Ui.dp(act, 8)
        ok.layoutParams = okLp
        row.addView(cancel)
        row.addView(ok)
        val rLp = LinearLayout.LayoutParams(MATCH, WRAP)
        rLp.topMargin = Ui.dp(act, 10)
        c.addView(row, rLp)

        dim.setOnClickListener { close(dim) }
        place(act, dim, c)
        et.requestFocus()
        et.postDelayed({
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 280)
    }
}

// ---------------------------------------------------------------- file operations

object Ops {
    fun cleanName(s: String): String =
        s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trimStart('.')

    fun scan(ctx: Context, paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }

    fun moveFile(ctx: Context, file: File, dir: File): File? {
        return try {
            dir.mkdirs()
            var t = File(dir, file.name)
            if (t.exists()) t = File(dir, "${System.currentTimeMillis()}_${file.name}")
            if (!file.renameTo(t)) {
                file.copyTo(t)
                file.delete()
            }
            scan(ctx, listOf(file.path, t.path))
            History.replace(ctx, file.path, t.path)
            t
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't move: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    // "Delete" = move to the recycle bin (kept 30 days).
    fun deleteFile(ctx: Context, file: File) {
        if (!Trash.move(ctx, file)) Toast.makeText(ctx, "Couldn't delete", Toast.LENGTH_SHORT).show()
    }

    fun renameDir(ctx: Context, dir: File, newName: String): Boolean {
        val target = File(dir.parentFile, newName)
        if (target.exists()) return false
        val olds = listMedia(dir).map { it.path }
        if (!dir.renameTo(target)) return false
        val news = olds.map { File(target, File(it).name).path }
        scan(ctx, olds + news)
        return true
    }

    fun deleteDir(ctx: Context, dir: File) {
        for (f in listMedia(dir)) Trash.move(ctx, f)
        dir.deleteRecursively()
        scan(ctx, listOf(dir.path))
    }

    private fun contentUri(ctx: Context, f: File): Uri? {
        val base = if (f.isVideoFile()) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            base,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA}=?",
            arrayOf(f.path),
            null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(base, c.getLong(0))
        }
        return null
    }

    fun share(act: Activity, f: File) {
        val uri = contentUri(act, f)
        if (uri == null) {
            Toast.makeText(act, "Can't share this file yet", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = if (f.isVideoFile()) "video/*" else "image/*"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(i, "Share"))
    }

    fun shareMany(act: Activity, fs: List<File>) {
        val uris = ArrayList<Uri>()
        for (f in fs) {
            val u = contentUri(act, f)
            if (u != null) uris.add(u)
        }
        if (uris.isEmpty()) {
            Toast.makeText(act, "Can't share these files yet", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND_MULTIPLE)
        i.type = "*/*"
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(i, "Share"))
    }

    // ZIP into Downloads/ScreenshotSorter
    fun exportZip(act: Activity, dirs: List<File>, label: String) {
        Toast.makeText(act, "Exporting…", Toast.LENGTH_SHORT).show()
        Thread {
            var result: File? = null
            var err: String? = null
            try {
                val outDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ScreenshotSorter"
                )
                outDir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val zip = File(outDir, "$label-$stamp.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                    zos.setLevel(Deflater.NO_COMPRESSION)
                    for (d in dirs) {
                        for (f in listMedia(d)) {
                            zos.putNextEntry(ZipEntry("${d.name}/${f.name}"))
                            FileInputStream(f).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                result = zip
            } catch (e: Exception) {
                err = e.message
            }
            val r = result
            val e = err
            act.runOnUiThread {
                if (r != null) {
                    scan(act, listOf(r.path))
                    Toast.makeText(act, "Saved to Downloads/ScreenshotSorter/${r.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(act, "Export failed: $e", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
