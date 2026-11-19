package com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private val MEDIA_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "3gp", "webm", "mov")
private val VIDEO_EXT = setOf("mp4", "mkv", "3gp", "webm", "mov")

fun File.isVideoFile() = extension.lowercase() in VIDEO_EXT

fun listMedia(dir: File): List<File> =
    dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXT }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

object Thumbs {
    private val cache = LruCache<String, Bitmap>(150)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(f: File, size: Int, target: ImageView) {
        val key = f.path
        target.tag = key
        target.animate().cancel()
        val hit = cache.get(key)
        if (hit != null) {
            target.alpha = 1f
            target.setImageBitmap(hit)
            return
        }
        target.setImageDrawable(null)
        pool.execute {
            val bmp: Bitmap? = try {
                if (f.isVideoFile()) ThumbnailUtils.createVideoThumbnail(f, Size(size, size), null)
                else ThumbnailUtils.createImageThumbnail(f, Size(size, size), null)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                main.post {
                    if (target.tag == key) {
                        target.setImageBitmap(bmp)
                        target.alpha = 0f
                        target.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
        }
    }
}

// Move / Share / Delete menu for one picture. onChanged runs after a move or delete.
fun fileActions(act: Activity, f: File, onChanged: () -> Unit) {
    Sheet.show(
        act, "Picture", f.name,
        listOf(
            Sheet.Item("Move to…", 0) { movePicker(act, f, onChanged) },
            Sheet.Item("Share", 0) { Ops.share(act, f) },
            Sheet.Item("Delete", 2) {
                Sheet.show(
                    act, "Delete this file?", "This can't be undone.",
                    listOf(Sheet.Item("Delete", 2) {
                        Ops.deleteFile(act, f)
                        onChanged()
                    })
                )
            }
        )
    )
}

private fun movePicker(act: Activity, f: File, onChanged: () -> Unit) {
    val here = f.parentFile
    val others = FolderPopup.BASE.listFiles()
        ?.filter { it.isDirectory && it != here }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()
    val items = ArrayList<Sheet.Item>()
    for (d in others) {
        items.add(Sheet.Item("📁   ${d.name}", 0) {
            Ops.moveFile(act, f, d)
            onChanged()
        })
    }
    items.add(Sheet.Item("+  New folder…", 1) {
        Sheet.input(act, "New folder", "", "Create & move") { raw ->
            val n = Ops.cleanName(raw)
            if (n.isNotEmpty()) {
                Ops.moveFile(act, f, File(FolderPopup.BASE, n))
                onChanged()
            }
        }
    })
    Sheet.show(act, "Move to", null, items)
}

class FolderActivity : Activity() {
    private lateinit var dir: File
    private var files: List<File> = emptyList()
    private lateinit var countView: TextView
    private lateinit var adapter: BaseAdapter
    private lateinit var backdrop: Backdrop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.styleWindow(this)
        dir = File(intent.getStringExtra("dir") ?: "")
        val sp = Ui.dp(this, 6)
        val pad = Ui.dp(this, 14)
        val cell = (resources.displayMetrics.widthPixels - pad * 2 - sp * 2) / 3

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 8))
        val back = Ui.text(this, "‹", 36f, Color.WHITE, Ui.light())
        back.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 14), Ui.dp(this, 4))
        back.setOnClickListener { finish() }
        val titleView = Ui.text(this, dir.name, 24f, Color.WHITE, Ui.light())
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        countView = Ui.text(this, "", 14f, Ui.MUTED)
        head.addView(back)
        head.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(countView)
        col.addView(head)

        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(i: Int): Any = files[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val ctx = this@FolderActivity
                val frame: FrameLayout
                if (convert is FrameLayout) {
                    frame = convert
                } else {
                    frame = FrameLayout(ctx)
                    frame.layoutParams = AbsListView.LayoutParams(cell, cell)
                    frame.background = Ui.glass(ctx, 12)
                    Ui.round(frame, Ui.dp(ctx, 12).toFloat())
                    val iv = ImageView(ctx)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                    val badge = Ui.text(ctx, "▶", 12f)
                    badge.gravity = Gravity.CENTER
                    val ov = GradientDrawable()
                    ov.shape = GradientDrawable.OVAL
                    ov.setColor(0x66000000)
                    badge.background = ov
                    val bs = Ui.dp(ctx, 34)
                    frame.addView(badge, FrameLayout.LayoutParams(bs, bs, Gravity.CENTER))
                }
                val iv = frame.getChildAt(0) as ImageView
                val badge = frame.getChildAt(1)
                val f = files[i]
                badge.visibility = if (f.isVideoFile()) View.VISIBLE else View.GONE
                Thumbs.load(f, cell, iv)
                return frame
            }
        }

        val grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = sp
        grid.verticalSpacing = sp
        grid.setPadding(pad, sp, pad, sp)
        grid.clipToPadding = false
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.isVerticalScrollBarEnabled = false
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val i = Intent(this, ViewerActivity::class.java)
            i.putExtra("dir", dir.path)
            i.putExtra("index", pos)
            startActivity(i)
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in files.indices) fileActions(this, files[pos]) { reload() }
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        backdrop = Backdrop(this, col)
        setContentView(backdrop.root)
        Ui.fadeIn(col)
    }

    private fun reload() {
        files = listMedia(dir)
        countView.text = "${files.size}"
        adapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        backdrop.start()
        reload()
    }

    override fun onPause() {
        super.onPause()
        backdrop.stop()
    }
}

class ViewerActivity : Activity() {
    private var files: List<File> = emptyList()
    private var idx = 0
    private var barOn = true
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var counter: TextView
    private lateinit var bar: View
    private lateinit var gd: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector

    // zoom state
    private var currentBitmap: Bitmap? = null
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var minScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var panReady = false
    private var multiTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        files = listMedia(File(intent.getStringExtra("dir") ?: ""))
        if (files.isEmpty()) {
            finish()
            return
        }
        idx = intent.getIntExtra("index", 0).coerceIn(0, files.size - 1)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.MATRIX
        root.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))

        video = VideoView(this)
        video.setMediaController(MediaController(this))
        video.visibility = View.GONE
        root.addView(video, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        val barLayout = LinearLayout(this)
        barLayout.orientation = LinearLayout.HORIZONTAL
        barLayout.gravity = Gravity.CENTER_VERTICAL
        barLayout.background = Ui.glass(this, 22)
        val prev = Ui.text(this, "‹", 32f, Color.WHITE, Ui.light())
        prev.setPadding(Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 6))
        prev.setOnClickListener { go(-1) }
        val next = Ui.text(this, "›", 32f, Color.WHITE, Ui.light())
        next.setPadding(Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 6))
        next.setOnClickListener { go(1) }
        val more = Ui.text(this, "⋯", 26f, Color.WHITE, Ui.medium())
        more.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 6))
        more.setOnClickListener { openActions() }
        counter = Ui.text(this, "", 13f, Color.WHITE, Ui.medium())
        counter.gravity = Gravity.CENTER
        counter.maxLines = 2
        counter.ellipsize = TextUtils.TruncateAt.MIDDLE
        barLayout.addView(prev)
        barLayout.addView(counter, LinearLayout.LayoutParams(0, WRAP, 1f))
        barLayout.addView(next)
        barLayout.addView(more)
        bar = barLayout
        val barLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
        val mg = Ui.dp(this, 12)
        barLp.setMargins(mg, mg, mg, 0)
        root.addView(barLayout, barLp)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (currentBitmap == null) return false
                val cur = curScale()
                val target = (cur * d.scaleFactor).coerceIn(minScale, minScale * 6f)
                val f = target / cur
                val loc = IntArray(2)
                image.getLocationInWindow(loc)
                m.postScale(f, f, d.focusX - loc[0], d.focusY - loc[1])
                fixBounds()
                image.imageMatrix = m
                return true
            }
        })

        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (multiTouch || isZoomed()) return false
                if (Math.abs(velocityX) > 800 && Math.abs(velocityX) > Math.abs(velocityY)) {
                    go(if (velocityX < 0) 1 else -1)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleBar()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (image.visibility != View.VISIBLE || currentBitmap == null) return false
                if (curScale() > minScale * 1.1f) {
                    fitImage()
                } else {
                    val loc = IntArray(2)
                    image.getLocationInWindow(loc)
                    m.postScale(2.5f, 2.5f, e.x - loc[0], e.y - loc[1])
                    fixBounds()
                    image.imageMatrix = m
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!scaleDetector.isInProgress && !multiTouch) openActions()
            }
        })

        setContentView(root)
        showCurrent()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gd.isInitialized && ::scaleDetector.isInitialized) {
            val imageMode = image.visibility == View.VISIBLE
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    multiTouch = false
                    panReady = true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    multiTouch = true
                    panReady = false
                }
            }
            if (ev.pointerCount > 1) multiTouch = true
            if (imageMode) scaleDetector.onTouchEvent(ev)
            gd.onTouchEvent(ev)
            if (imageMode && ev.actionMasked == MotionEvent.ACTION_MOVE &&
                ev.pointerCount == 1 && isZoomed() && !scaleDetector.isInProgress
            ) {
                if (panReady) {
                    m.postTranslate(ev.x - lastX, ev.y - lastY)
                    fixBounds()
                    image.imageMatrix = m
                } else {
                    panReady = true
                }
            }
            lastX = ev.x
            lastY = ev.y
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized) video.stopPlayback()
    }

    private fun curScale(): Float {
        m.getValues(vals)
        return vals[Matrix.MSCALE_X]
    }

    private fun isZoomed(): Boolean =
        image.visibility == View.VISIBLE && currentBitmap != null && curScale() > minScale * 1.02f

    private fun fitImage() {
        val bmp = currentBitmap ?: return
        val vw = image.width.toFloat()
        val vh = image.height.toFloat()
        if (vw == 0f || vh == 0f) {
            image.post { fitImage() }
            return
        }
        val s = minOf(vw / bmp.width, vh / bmp.height)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
        minScale = s
        image.imageMatrix = m
    }

    private fun fixBounds() {
        val bmp = currentBitmap ?: return
        m.getValues(vals)
        val s = vals[Matrix.MSCALE_X]
        val tx = vals[Matrix.MTRANS_X]
        val ty = vals[Matrix.MTRANS_Y]
        val cw = bmp.width * s
        val ch = bmp.height * s
        val vw = image.width.toFloat()
        val vh = image.height.toFloat()
        val nx = if (cw <= vw) (vw - cw) / 2f else tx.coerceIn(vw - cw, 0f)
        val ny = if (ch <= vh) (vh - ch) / 2f else ty.coerceIn(vh - ch, 0f)
        m.postTranslate(nx - tx, ny - ty)
    }

    private fun openActions() {
        if (idx !in files.indices) return
        fileActions(this, files[idx]) { removeCurrent() }
    }

    private fun removeCurrent() {
        val list = files.toMutableList()
        if (idx in list.indices) list.removeAt(idx)
        files = list
        if (files.isEmpty()) {
            finish()
            return
        }
        if (idx >= files.size) idx = files.size - 1
        showCurrent()
    }

    private fun toggleBar() {
        barOn = !barOn
        if (barOn) {
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(180).start()
        } else {
            bar.animate().alpha(0f).setDuration(180).withEndAction { bar.visibility = View.GONE }.start()
        }
    }

    private fun go(delta: Int) {
        val n = idx + delta
        if (n in files.indices) {
            idx = n
            showCurrent()
        }
    }

    private fun showCurrent() {
        val f = files[idx]
        counter.text = "${idx + 1} / ${files.size}\n${f.name}"
        if (f.isVideoFile()) {
            currentBitmap = null
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setVideoPath(f.path)
            video.start()
        } else {
            video.stopPlayback()
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.setImageDrawable(null)
            currentBitmap = null
            Thread {
                val bmp = decode(f)
                runOnUiThread {
                    if (files.getOrNull(idx) == f && bmp != null) {
                        currentBitmap = bmp
                        image.setImageBitmap(bmp)
                        fitImage()
                        image.alpha = 0f
                        image.animate().alpha(1f).setDuration(220).start()
                    }
                }
            }.start()
        }
    }

    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        val dm = resources.displayMetrics
        var s = 1
        while (o.outWidth / (s * 2) >= dm.widthPixels && o.outHeight / (s * 2) >= dm.heightPixels) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeFile(f.path, o2)
    }
}
