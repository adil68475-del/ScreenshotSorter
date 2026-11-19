package com.sorter.shots

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
    private val aiOn: Boolean,
    private val onClosed: () -> Unit
) {
    companion object {
        val BASE = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sorted")
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = ctx.resources.displayMetrics
    private val root = FrameLayout(ctx)
    private var shown = false

    // AI state
    private var analysis: Brain.Analysis? = null
    private var suggestion: Brain.Suggestion? = null
    private var aiDone = false
    private var slot: LinearLayout? = null

    private fun dp(v: Int) = Ui.dp(ctx, v)

    private fun card(): LinearLayout {
        root.removeAllViews()
        slot = null
        val c = Ui.panel(ctx)
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
        Theme.load(ctx)
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

    // Called by the service when the on-device AI has looked at the picture.
    fun setAnalysis(a: Brain.Analysis?, s: Brain.Suggestion?) {
        analysis = a
        suggestion = s
        aiDone = true
        if (shown) fillSlot()
    }

    private fun fillSlot() {
        val s = slot ?: return
        s.removeAllViews()
        if (!aiDone) {
            val t = Ui.text(ctx, "✨  Looking at your screenshot…", 13f, Ui.muted())
            t.setPadding(dp(4), dp(12), 0, dp(2))
            t.alpha = 0.4f
            t.animate().alpha(1f).setDuration(700).start()
            s.addView(t)
            return
        }
        val sg = suggestion ?: return
        val label = if (sg.isNew) "✨  Create \"${sg.name}\" and save" else "✨  Save to ${sg.name}"
        val b = Ui.button(ctx, label, true, true) { moveTo(File(BASE, sg.name)) }
        (b.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        s.addView(b)
        val why = Ui.text(ctx, sg.reason, 11f, Ui.muted())
        why.gravity = Gravity.CENTER
        why.setPadding(0, dp(4), 0, 0)
        s.addView(why, LinearLayout.LayoutParams(MATCH, WRAP))
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
        col.addView(Ui.text(ctx, "Save to…", 18f, Ui.fg(), Ui.medium()))
        val name = Ui.text(ctx, file.name, 12f, Ui.muted())
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name)
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(head)
        Thumbs.load(file, dp(104), iv)

        if (aiOn) {
            val sl = LinearLayout(ctx)
            sl.orientation = LinearLayout.VERTICAL
            c.addView(sl, LinearLayout.LayoutParams(MATCH, WRAP))
            slot = sl
            fillSlot()
        }

        FolderPopup.BASE.mkdirs()
        val folders = userFolders().sortedBy { it.name.lowercase() }
        val usage = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val quick = folders.filter { usage.getInt(it.name, 0) > 0 }.maxByOrNull { usage.getInt(it.name, 0) }
        if (quick != null) {
            val qb = Ui.button(ctx, "⚡  Quick save to ${quick.name}", !aiOn, true) { moveTo(quick) }
            (qb.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
            c.addView(qb)
        }

        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        if (folders.isEmpty()) {
            val e = Ui.text(ctx, "No folders yet. Create your first one.", 14f, Ui.muted())
            e.setPadding(0, dp(14), 0, dp(8))
            list.addView(e)
        }
        for (f in folders) {
            list.addView(Ui.button(ctx, "📁   ${f.name}", false, false) { moveTo(f) })
        }

        val maxH = (dm.heightPixels * 0.34).toInt()
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
        val newBtn = Ui.button(ctx, "+  New folder", false, true) { showNew() }
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
        c.addView(Ui.text(ctx, "New folder", 18f, Ui.fg(), Ui.medium()))

        val et = EditText(ctx)
        et.setTextColor(Ui.fg())
        et.setHintTextColor(Ui.muted())
        et.hint = "Folder name"
        et.textSize = 16f
        et.typeface = Ui.medium()
        et.background = Ui.glass(ctx, 14)
        et.setPadding(dp(16), dp(13), dp(16), dp(13))
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val s = suggestion
        if (s != null && s.isNew) {
            et.setText(s.name)
            et.setSelection(et.text.length)
        }
        val etLp = LinearLayout.LayoutParams(MATCH, WRAP)
        etLp.topMargin = dp(12)
        c.addView(et, etLp)

        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        val back = Ui.button(ctx, "Back", false, true) { showList() }
        val create = Ui.button(ctx, "Create & save", true, true) {
            val n = Ops.cleanName(et.text.toString())
            if (n.isNotEmpty()) {
                moveTo(File(BASE, n))
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
        c.addView(Ui.text(ctx, "Saved to $folderName", 16f, Ui.fg(), Ui.medium()))
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        root.postDelayed({ dismiss() }, 700)
    }

    private fun moveTo(dir: File) {
        val target = Ops.moveFile(ctx, file, dir)
        if (target == null) {
            dismiss()
            return
        }
        val usage = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        usage.edit().putInt(dir.name, usage.getInt(dir.name, 0) + 1).apply()
        History.add(ctx, target.path)
        val a = analysis
        if (aiOn && a != null) {
            Brain.learn(ctx, dir.name, a.features)
            Brain.index(ctx, target.name, a)
        }
        showDone(dir.name)
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
