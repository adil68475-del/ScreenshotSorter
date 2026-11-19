package com.sorter.shots

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class FolderPopup(
    private val ctx: Context,
    private val file: File,
    private val onClosed: () -> Unit
) {
    companion object {
        val BASE = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sorted")
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = ctx.resources.displayMetrics
    private val root = FrameLayout(ctx)
    private var shown = false

    private fun dp(v: Int) = Ui.dp(ctx, v)

    private fun card(): LinearLayout {
        root.removeAllViews()
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(16), dp(16), dp(14))
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xCC283050.toInt(), 0xBB171C33.toInt())
        )
        bg.cornerRadius = dp(26).toFloat()
        bg.setStroke(dp(1), 0x40FFFFFF)
        c.background = bg
        c.isClickable = true
        val w = minOf(dm.widthPixels - dp(40), dp(360))
        root.addView(c, FrameLayout.LayoutParams(w, WRAP, Gravity.CENTER))
        c.alpha = 0f
        c.scaleX = 0.94f
        c.scaleY = 0.94f
        c.translationY = dp(14).toFloat()
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        return c
    }

    fun show() {
        val p = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT
        )
        p.blurBehindRadius = dp(28)
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        root.setBackgroundColor(0x55000000)
        root.alpha = 0f
        root.setOnClickListener { dismiss() }
        wm.addView(root, p)
        shown = true
        showList()
        root.animate().alpha(1f).setDuration(180).start()
    }

    private fun showList() {
        val c = card()

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = Ui.glass(ctx, 12)
        Ui.round(iv, dp(12).toFloat())
        head.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        col.addView(Ui.text(ctx, "Save to…", 18f, Color.WHITE, Ui.medium()))
        val name = Ui.text(ctx, file.name, 12f, Ui.MUTED)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name)
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(head)
        Thumbs.load(file, dp(104), iv)

        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        BASE.mkdirs()
        val folders = BASE.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (folders.isEmpty()) {
            val e = Ui.text(ctx, "No folders yet. Create your first one.", 14f, Ui.MUTED)
            e.setPadding(0, dp(14), 0, dp(8))
            list.addView(e)
        }
        for (f in folders) {
            list.addView(Ui.button(ctx, "📁   ${f.name}", false, false) { moveTo(f) })
        }

        val maxH = (dm.heightPixels * 0.38).toInt()
        val sv = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list)
        val svLp = LinearLayout.LayoutParams(MATCH, WRAP)
        svLp.topMargin = dp(10)
        c.addView(sv, svLp)

        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        val newBtn = Ui.button(ctx, "+  New folder", true, true) { showNew() }
        val skip = Ui.button(ctx, "Skip", false, true) { dismiss() }
        newBtn.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.6f)
        val skipLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        skipLp.leftMargin = dp(8)
        skip.layoutParams = skipLp
        actions.addView(newBtn)
        actions.addView(skip)
        val aLp = LinearLayout.LayoutParams(MATCH, WRAP)
        aLp.topMargin = dp(8)
        c.addView(actions, aLp)
    }

    private fun showNew() {
        val c = card()
        c.addView(Ui.text(ctx, "New folder", 18f, Color.WHITE, Ui.medium()))

        val et = EditText(ctx)
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(0x99FFFFFF.toInt())
        et.hint = "Folder name"
        et.textSize = 16f
        et.typeface = Ui.medium()
        et.background = Ui.glass(ctx, 14)
        et.setPadding(dp(16), dp(13), dp(16), dp(13))
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val etLp = LinearLayout.LayoutParams(MATCH, WRAP)
        etLp.topMargin = dp(12)
        c.addView(et, etLp)

        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        val back = Ui.button(ctx, "Back", false, true) { showList() }
        val create = Ui.button(ctx, "Create & save", true, true) {
            val n = et.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (n.isNotEmpty()) {
                val dir = File(BASE, n)
                dir.mkdirs()
                moveTo(dir)
            }
        }
        back.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val cLp = LinearLayout.LayoutParams(0, WRAP, 1.6f)
        cLp.leftMargin = dp(8)
        create.layoutParams = cLp
        actions.addView(back)
        actions.addView(create)
        val aLp = LinearLayout.LayoutParams(MATCH, WRAP)
        aLp.topMargin = dp(10)
        c.addView(actions, aLp)

        et.requestFocus()
        et.postDelayed({
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun showDone(folderName: String) {
        val c = card()
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(16), dp(24), dp(16), dp(22))
        c.addView(Ui.text(ctx, "✓", 40f, Ui.GREEN, Ui.light()))
        c.addView(Ui.text(ctx, "Saved to $folderName", 16f, Color.WHITE, Ui.medium()))
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        root.postDelayed({ dismiss() }, 700)
    }

    private fun moveTo(dir: File) {
        try {
            var target = File(dir, file.name)
            if (target.exists()) target = File(dir, "${System.currentTimeMillis()}_${file.name}")
            if (!file.renameTo(target)) {
                file.copyTo(target)
                file.delete()
            }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path, target.path), null, null)
            showDone(dir.name)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't move: ${e.message}", Toast.LENGTH_LONG).show()
            dismiss()
        }
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        root.animate().alpha(0f).setDuration(140).withEndAction {
            try {
                wm.removeView(root)
            } catch (_: Exception) {
            }
            onClosed()
        }.start()
    }
}
