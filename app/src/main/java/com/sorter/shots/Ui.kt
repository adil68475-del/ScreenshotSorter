package com.sorter.shots

import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Ui {
    const val MUTED = 0xFFAAB3C8.toInt()
    const val GREEN = 0xFF4ADE80.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun light(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun text(ctx: Context, s: String, size: Float, color: Int = Color.WHITE, face: Typeface? = null): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (face != null) t.typeface = face
        return t
    }

    // Frosted-glass look: light translucent gradient + thin bright edge.
    fun glass(ctx: Context, radius: Int): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x30FFFFFF, 0x12FFFFFF)
        )
        g.cornerRadius = dp(ctx, radius).toFloat()
        g.setStroke(dp(ctx, 1), 0x3CFFFFFF)
        return g
    }

    fun accentBg(ctx: Context, radius: Int): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF5B8CFF.toInt(), 0xFF8E6BFF.toInt())
        )
        g.cornerRadius = dp(ctx, radius).toFloat()
        return g
    }

    fun ripple(ctx: Context, content: GradientDrawable, radius: Int): RippleDrawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, radius).toFloat()
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask)
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
        act.window.statusBarColor = 0xFF0B0F1E.toInt()
        act.window.navigationBarColor = 0xFF1B1238.toInt()
    }

    fun fadeIn(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(280).start()
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

    fun panel(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14))
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xEE283050.toInt(), 0xEE171C33.toInt())
        )
        bg.cornerRadius = dp(ctx, 26).toFloat()
        bg.setStroke(dp(ctx, 1), 0x40FFFFFF)
        c.background = bg
        c.isClickable = true
        return c
    }

    fun button(ctx: Context, label: String, primary: Boolean, center: Boolean, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 15f, Color.WHITE, medium())
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
}

// Dark gradient with soft, slowly drifting blurred colour blobs behind the content.
class Backdrop(act: Activity, content: View) {
    val root = FrameLayout(act)
    private val anims = ArrayList<ValueAnimator>()

    init {
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0B0F1E.toInt(), 0xFF141A33.toInt(), 0xFF1B1238.toInt())
        )
        val dm = act.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        orb(act, 0x885B8CFF.toInt(), (w * 0.95f).toInt(), -w * 0.25f, h * 0.02f, 9000L)
        orb(act, 0x888E4BFF.toInt(), (w * 0.85f).toInt(), w * 0.40f, h * 0.34f, 12000L)
        orb(act, 0x6628C9B5.toInt(), (w * 0.75f).toInt(), -w * 0.15f, h * 0.68f, 10500L)
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


// Bottom sheets drawn inside the activity (same glass look as the popup).
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

    fun show(act: Activity, title: String, subtitle: String?, items: List<Item>) {
        val dim = host(act)
        val c = Ui.panel(act)
        c.addView(Ui.text(act, title, 18f, Color.WHITE, Ui.medium()))
        if (subtitle != null) {
            val s = Ui.text(act, subtitle, 13f, Ui.MUTED)
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
        val maxH = (act.resources.displayMetrics.heightPixels * 0.5).toInt()
        val sv = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list)
        c.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        c.addView(Ui.button(act, "Cancel", false, true) { close(dim) })
        dim.setOnClickListener { close(dim) }
        place(act, dim, c)
    }

    fun input(act: Activity, title: String, initial: String, confirm: String, onDone: (String) -> Unit) {
        act.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val dim = host(act)
        val c = Ui.panel(act)
        c.addView(Ui.text(act, title, 18f, Color.WHITE, Ui.medium()))
        val et = EditText(act)
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(0x99FFFFFF.toInt())
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

// File operations shared by the screens.
object Ops {
    fun cleanName(s: String): String {
        val n = s.trim().replace(Regex("[\\/:*?\"<>|]"), "_").trimStart('.')
        return n
    }

    private fun scan(ctx: Context, paths: List<String>) {
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
            t
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't move: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun deleteFile(ctx: Context, file: File) {
        file.delete()
        scan(ctx, listOf(file.path))
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
        val olds = listMedia(dir).map { it.path }
        dir.deleteRecursively()
        scan(ctx, olds)
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
}
