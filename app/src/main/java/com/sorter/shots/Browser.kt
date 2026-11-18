package com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.util.concurrent.Executors

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
        val hit = cache.get(key)
        if (hit != null) {
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
                main.post { if (target.tag == key) target.setImageBitmap(bmp) }
            }
        }
    }
}

class FolderActivity : Activity() {
    private lateinit var dir: File
    private var files: List<File> = emptyList()
    private lateinit var title: TextView
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra("dir") ?: "")
        val d = resources.displayMetrics.density
        val sp = (4 * d).toInt()
        val cell = (resources.displayMetrics.widthPixels - sp * 4) / 3

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        title = TextView(this)
        title.textSize = 20f
        title.setPadding(sp * 4, sp * 3, sp * 4, sp * 3)
        col.addView(title)

        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(i: Int): Any = files[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val frame: FrameLayout
                if (convert is FrameLayout) {
                    frame = convert
                } else {
                    frame = FrameLayout(this@FolderActivity)
                    frame.layoutParams = AbsListView.LayoutParams(cell, cell)
                    val iv = ImageView(this@FolderActivity)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.setBackgroundColor(0xFFDDDDDD.toInt())
                    frame.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    val play = TextView(this@FolderActivity)
                    play.text = "▶"
                    play.textSize = 24f
                    play.setTextColor(Color.WHITE)
                    play.gravity = Gravity.CENTER
                    frame.addView(play, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                val iv = frame.getChildAt(0) as ImageView
                val play = frame.getChildAt(1) as TextView
                val f = files[i]
                play.visibility = if (f.isVideoFile()) View.VISIBLE else View.GONE
                Thumbs.load(f, cell, iv)
                return frame
            }
        }

        val grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = sp
        grid.verticalSpacing = sp
        grid.setPadding(sp, sp, sp, sp)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val i = Intent(this, ViewerActivity::class.java)
            i.putExtra("dir", dir.path)
            i.putExtra("index", pos)
            startActivity(i)
        }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        files = listMedia(dir)
        title.text = "${dir.name}  (${files.size})"
        adapter.notifyDataSetChanged()
    }
}

class ViewerActivity : Activity() {
    private var files: List<File> = emptyList()
    private var idx = 0
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var counter: TextView
    private lateinit var gd: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = listMedia(File(intent.getStringExtra("dir") ?: ""))
        if (files.isEmpty()) {
            finish()
            return
        }
        idx = intent.getIntExtra("index", 0).coerceIn(0, files.size - 1)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        video = VideoView(this)
        video.setMediaController(MediaController(this))
        video.visibility = View.GONE
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0x99000000.toInt())
        val prev = Button(this)
        prev.text = "◀"
        prev.setOnClickListener { go(-1) }
        val next = Button(this)
        next.text = "▶"
        next.setOnClickListener { go(1) }
        counter = TextView(this)
        counter.setTextColor(Color.WHITE)
        counter.textSize = 13f
        counter.gravity = Gravity.CENTER
        counter.maxLines = 2
        bar.addView(prev)
        bar.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(next)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (Math.abs(velocityX) > 800 && Math.abs(velocityX) > Math.abs(velocityY)) {
                    go(if (velocityX < 0) 1 else -1)
                    return true
                }
                return false
            }
        })

        setContentView(root)
        showCurrent()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gd.isInitialized) gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized) video.stopPlayback()
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
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setVideoPath(f.path)
            video.start()
        } else {
            video.stopPlayback()
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.setImageDrawable(null)
            Thread {
                val bmp = decode(f)
                runOnUiThread {
                    if (files.getOrNull(idx) == f) image.setImageBitmap(bmp)
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
