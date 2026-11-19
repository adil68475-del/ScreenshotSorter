package com.sorter.shots

import android.animation.ValueAnimator
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
