package com.sorter.shots

import android.app.Activity
import android.content.Context
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

// scope: "All", "Folders" (match folder names) or "Pictures" (file names + what the AI saw)
fun searchFiles(ctx: Context, q: String, scope: String): List<File> {
    val ql = q.trim().lowercase()
    if (ql.isEmpty()) return emptyList()
    val idx = ctx.getSharedPreferences("index", Context.MODE_PRIVATE)
    val out = ArrayList<File>()
    for (d in userFolders()) {
        if (Locks.isLocked(ctx, d.name)) continue
        val folderHit = d.name.lowercase().contains(ql)
        for (f in listMedia(d)) {
            val nameHit = f.name.lowercase().contains(ql) ||
                (idx.getString(f.name, "") ?: "").contains(ql)
            val ok = when (scope) {
                "Folders" -> folderHit
                "Pictures" -> nameHit
                else -> folderHit || nameHit
            }
            if (ok) out.add(f)
        }
    }
    return out
}

// modes: folder | search | history | trash
fun loadFiles(ctx: Context, mode: String, dirPath: String, query: String, scope: String): List<File> {
    val sp = Prefs.sp(ctx)
    val key = sp.getString("sortPics", "Date") ?: "Date"
    val desc = sp.getBoolean("descPics", true)
    return when (mode) {
        "trash" -> Trash.entries(ctx)
        "history" -> History.list(ctx).map { File(it) }.filter {
            it.isFile && !Locks.isLocked(ctx, it.parentFile?.name ?: "")
        }
        "search" -> Sorter.sortPics(searchFiles(ctx, query, scope), key, desc)
        "phone" -> SearchIndex.search(ctx, query)
        else -> Sorter.sortPics(listMedia(File(dirPath)), key, desc)
    }
}

object Thumbs {
    private val cache = LruCache<String, Bitmap>(150)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun invalidate(path: String) {
        cache.remove(path)
    }

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

// ---------------------------------------------------------------- actions

// Move / Share / Delete menu for pictures. onChanged runs after a move or delete.
fun fileActions(act: Activity, f: File, onChanged: () -> Unit) {
    Sheet.show(
        act, "Picture", f.name,
        listOfNotNull(
            if (f.isVideoFile()) null else Sheet.Item("Edit", 0) {
                val i = Intent(act, EditActivity::class.java)
                i.putExtra("path", f.path)
                act.startActivity(i)
            },
            Sheet.Item("Move to…", 0) { movePicker(act, listOf(f), onChanged) },
            Sheet.Item("Share", 0) { Ops.share(act, f) },
            Sheet.Item("Delete", 2) {
                Sheet.show(
                    act, "Delete this file?", "It goes to the Recycle bin and is removed after 30 days.",
                    listOf(Sheet.Item("Move to Recycle bin", 2) {
                        Ops.deleteFile(act, f)
                        onChanged()
                    })
                )
            }
        )
    )
}

fun trashActions(act: Activity, f: File, onChanged: () -> Unit) {
    Sheet.show(
        act, "Recycle bin", "${f.name.substringAfter('_', f.name)} · ${Trash.daysLeft(act, f)} days left",
        listOf(
            Sheet.Item("Restore", 1) {
                Trash.restore(act, f)
                onChanged()
            },
            Sheet.Item("Delete forever", 2) {
                Trash.deleteForever(act, f)
                onChanged()
            }
        )
    )
}

fun movePicker(act: Activity, fs: List<File>, onChanged: () -> Unit) {
    val parents = fs.map { it.parentFile }.toSet()
    val others = userFolders()
        .filter { !(parents.size == 1 && it == parents.first()) }
        .sortedBy { it.name.lowercase() }
    val items = ArrayList<Sheet.Item>()
    for (d in others) {
        items.add(Sheet.Item("📁   ${d.name}", 0) {
            for (f in fs) Ops.moveFile(act, f, d)
            onChanged()
        })
    }
    items.add(Sheet.Item("+  New folder…", 1) {
        Sheet.input(act, "New folder", "", "Create & move") { raw ->
            val n = Ops.cleanName(raw)
            if (n.isNotEmpty()) {
                for (f in fs) Ops.moveFile(act, f, File(FolderPopup.BASE, n))
                onChanged()
            }
        }
    })
    Sheet.show(act, if (fs.size == 1) "Move to" else "Move ${fs.size} items to", null, items)
}

// ---------------------------------------------------------------- grid / list of pictures

class FolderActivity : Activity() {
    private var mode = "folder"
    private lateinit var dir: File
    private var query = ""
    private var scope = "All"
    private var files: List<File> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selecting = false
    private var listLayout = false
    private var cols = 3
    private var gap = 0
    private var pad = 0

    private lateinit var backBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var countView: TextView
    private lateinit var grid: GridView
    private lateinit var selBar: LinearLayout
    private lateinit var adapter: BaseAdapter
    private lateinit var backdrop: Backdrop

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun baseTitle(): String = when (mode) {
        "search" -> "Search: $query"
        "phone" -> "Phone: $query"
        "history" -> "History"
        "trash" -> "Recycle bin"
        else -> dir.name
    }

    private fun cellSize(): Int {
        val w = resources.displayMetrics.widthPixels
        return (w - pad * 2 - gap * (cols - 1)) / cols
    }

    private fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun metaFor(f: File): String {
        val parts = ArrayList<String>()
        if (mode == "trash") parts.add("${Trash.daysLeft(this, f)} days left")
        parts.add(fmtSize(f.length()))
        if (f.isVideoFile()) {
            val l = Sorter.length(f)
            if (l > 0) parts.add(fmtDuration(l))
        }
        parts.add(fmtDate(f.lastModified()))
        if (mode != "folder" && mode != "trash") {
            val p = f.parentFile?.name
            if (p != null) parts.add(p)
        }
        return parts.joinToString(" · ")
    }

    private fun buildCell(ctx: Context): FrameLayout {
        val cell = cellSize()
        val frame = FrameLayout(ctx)
        frame.layoutParams = AbsListView.LayoutParams(cell, cell)
        frame.background = Ui.glass(ctx, 12)
        Ui.round(frame, Ui.dp(ctx, 12).toFloat())
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val badge = Ui.text(ctx, "▶", 12f, Color.WHITE)
        badge.gravity = Gravity.CENTER
        badge.background = oval(0x66000000)
        val bs = Ui.dp(ctx, 34)
        frame.addView(badge, FrameLayout.LayoutParams(bs, bs, Gravity.CENTER))
        val check = Ui.text(ctx, "✓", 13f, Color.WHITE, Ui.medium())
        check.gravity = Gravity.CENTER
        check.background = oval(Theme.accent)
        val cs = Ui.dp(ctx, 24)
        val clp = FrameLayout.LayoutParams(cs, cs, Gravity.TOP or Gravity.END)
        clp.setMargins(0, Ui.dp(ctx, 6), Ui.dp(ctx, 6), 0)
        frame.addView(check, clp)
        return frame
    }

    private fun buildRow(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        row.layoutParams = AbsListView.LayoutParams(MATCH, Ui.dp(ctx, 76))
        val thumb = FrameLayout(ctx)
        thumb.background = Ui.glass(ctx, 12)
        Ui.round(thumb, Ui.dp(ctx, 12).toFloat())
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        row.addView(thumb, LinearLayout.LayoutParams(Ui.dp(ctx, 60), Ui.dp(ctx, 60)))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), 0)
        val name = Ui.text(ctx, "", 15f, Ui.fg(), Ui.medium())
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        val meta = Ui.text(ctx, "", 12f, Ui.muted())
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        col.addView(meta)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val check = Ui.text(ctx, "✓", 14f, Color.WHITE, Ui.medium())
        check.gravity = Gravity.CENTER
        check.background = oval(Theme.accent)
        row.addView(check, LinearLayout.LayoutParams(Ui.dp(ctx, 26), Ui.dp(ctx, 26)))
        return row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        mode = intent.getStringExtra("mode") ?: "folder"
        dir = File(intent.getStringExtra("dir") ?: "")
        query = intent.getStringExtra("query") ?: ""
        scope = intent.getStringExtra("scope") ?: "All"
        val prefs = Prefs.sp(this)
        listLayout = prefs.getString("picLayout", "grid") == "list"
        cols = prefs.getInt("picCols", 3)
        gap = dp(6)
        pad = dp(14)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(10), dp(12), dp(14), dp(8))
        backBtn = Ui.text(this, "‹", 36f, Ui.fg(), Ui.light())
        backBtn.setPadding(dp(10), 0, dp(14), dp(4))
        backBtn.setOnClickListener {
            if (selecting) exitSelect() else finish()
        }
        titleView = Ui.text(this, baseTitle(), 24f, Ui.fg(), Ui.light())
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        countView = Ui.text(this, "", 14f, Ui.muted())
        countView.setPadding(dp(8), 0, dp(4), 0)
        head.addView(backBtn)
        head.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(countView)
        head.addView(Ui.iconButton(this, "⇅") { showOptions() })
        col.addView(head)

        if (mode == "trash") {
            val note = Ui.text(this, "Deleted pictures stay here for ${Trash.DAYS} days, then are removed for good.", 12f, Ui.muted())
            note.setPadding(dp(24), 0, dp(24), dp(6))
            col.addView(note)
        }

        adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(i: Int): Any = files[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (listLayout) 1 else 0
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val ctx = this@FolderActivity
                val f = files[i]
                val sel = selected.contains(f.path)
                if (listLayout) {
                    val row = (convert as? LinearLayout) ?: buildRow(ctx)
                    val thumb = row.getChildAt(0) as FrameLayout
                    val iv = thumb.getChildAt(0) as ImageView
                    val tcol = row.getChildAt(1) as LinearLayout
                    (tcol.getChildAt(0) as TextView).text =
                        if (mode == "trash") f.name.substringAfter('_', f.name) else f.name
                    (tcol.getChildAt(1) as TextView).text = metaFor(f)
                    row.getChildAt(2).visibility = if (sel) View.VISIBLE else View.GONE
                    row.background = if (sel) Ui.selectedBg(ctx, 16) else Ui.glass(ctx, 16)
                    Thumbs.load(f, Ui.dp(ctx, 60), iv)
                    return row
                }
                val frame = (convert as? FrameLayout) ?: buildCell(ctx)
                val iv = frame.getChildAt(0) as ImageView
                frame.getChildAt(1).visibility = if (f.isVideoFile()) View.VISIBLE else View.GONE
                frame.getChildAt(2).visibility = if (sel) View.VISIBLE else View.GONE
                frame.foreground = if (sel) ColorDrawable(Theme.withAlpha(Theme.accent, 0x55)) else null
                Thumbs.load(f, cellSize(), iv)
                return frame
            }
        }

        grid = GridView(this)
        grid.setPadding(pad, gap, pad, gap)
        grid.clipToPadding = false
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.isVerticalScrollBarEnabled = false
        applyLayout()
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (pos in files.indices) {
                if (selecting) {
                    toggle(files[pos])
                } else {
                    val i = Intent(this, ViewerActivity::class.java)
                    i.putExtra("mode", mode)
                    i.putExtra("dir", dir.path)
                    i.putExtra("query", query)
                    i.putExtra("scope", scope)
                    i.putExtra("index", pos)
                    startActivity(i)
                }
            }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in files.indices) {
                selecting = true
                toggle(files[pos])
            }
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        selBar = LinearLayout(this)
        selBar.orientation = LinearLayout.HORIZONTAL
        selBar.background = Ui.glass(this, 22)
        selBar.setPadding(dp(6), dp(6), dp(6), dp(6))
        fun barBtn(btn: TextView) {
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            btn.layoutParams = lp
            selBar.addView(btn)
        }
        if (mode == "trash") {
            barBtn(Ui.button(this, "Restore", true, true) { restoreSelected() })
            barBtn(Ui.dangerButton(this, "Delete") { deleteSelected() })
        } else {
            barBtn(Ui.button(this, "Move", false, true) {
                movePicker(this, selectedFiles()) {
                    exitSelect()
                    reload()
                }
            })
            barBtn(Ui.button(this, "Share", false, true) { Ops.shareMany(this, selectedFiles()) })
            barBtn(Ui.dangerButton(this, "Delete") { deleteSelected() })
        }
        barBtn(Ui.button(this, "All", false, true) { selectAll() })
        selBar.visibility = View.GONE
        val barLp = LinearLayout.LayoutParams(MATCH, WRAP)
        barLp.setMargins(dp(12), 0, dp(12), dp(12))
        col.addView(selBar, barLp)

        backdrop = Backdrop(this, col)
        setContentView(backdrop.root)
        Ui.styleWindow(this)
        Ui.fadeIn(col)
    }

    private fun applyLayout() {
        grid.numColumns = if (listLayout) 1 else cols
        grid.horizontalSpacing = if (listLayout) 0 else gap
        grid.verticalSpacing = gap
        grid.adapter = adapter
    }

    private fun selectedFiles(): List<File> = files.filter { selected.contains(it.path) }

    private fun toggle(f: File) {
        if (!selected.add(f.path)) selected.remove(f.path)
        if (selected.isEmpty()) selecting = false
        refreshHeader()
    }

    private fun selectAll() {
        for (f in files) selected.add(f.path)
        selecting = selected.isNotEmpty()
        refreshHeader()
    }

    private fun exitSelect() {
        selected.clear()
        selecting = false
        refreshHeader()
    }

    private fun deleteSelected() {
        val fs = selectedFiles()
        if (fs.isEmpty()) return
        if (mode == "trash") {
            Sheet.show(
                this, "Delete ${fs.size} item(s) forever?", "This can't be undone.",
                listOf(Sheet.Item("Delete forever", 2) {
                    for (f in fs) Trash.deleteForever(this, f)
                    exitSelect()
                    reload()
                })
            )
        } else {
            Sheet.show(
                this, "Delete ${fs.size} item(s)?",
                "They go to the Recycle bin and are removed after ${Trash.DAYS} days.",
                listOf(Sheet.Item("Move to Recycle bin", 2) {
                    for (f in fs) Ops.deleteFile(this, f)
                    exitSelect()
                    reload()
                })
            )
        }
    }

    private fun restoreSelected() {
        for (f in selectedFiles()) Trash.restore(this, f)
        exitSelect()
        reload()
    }

    private fun refreshHeader() {
        backBtn.text = if (selecting) "✕" else "‹"
        titleView.text = if (selecting) "${selected.size} selected" else baseTitle()
        countView.text = if (selecting) "" else "${files.size}"
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun reload() {
        files = loadFiles(this, mode, dir.path, query, scope)
        val keep = files.map { it.path }.toSet()
        selected.retainAll(keep)
        if (selected.isEmpty()) selecting = false
        refreshHeader()
    }

    private fun showOptions() {
        val sortable = mode == "folder" || mode == "search"
        Sheet.panel(this, if (sortable) "View & sort" else "View") { body ->
            body.addView(Ui.sectionLabel(this, "LAYOUT"))
            body.addView(Ui.chips(this, listOf("Grid", "List"), if (listLayout) "List" else "Grid", 2) { v ->
                listLayout = v == "List"
                Prefs.sp(this).edit().putString("picLayout", if (listLayout) "list" else "grid").apply()
                applyLayout()
            })
            body.addView(Ui.sectionLabel(this, "GRID COLUMNS"))
            body.addView(Ui.chips(this, listOf("2", "3", "4", "5"), cols.toString(), 4) { v ->
                cols = v.toInt()
                Prefs.sp(this).edit().putInt("picCols", cols).apply()
                applyLayout()
            })
            if (sortable) {
                val sp = Prefs.sp(this)
                body.addView(Ui.sectionLabel(this, "SORT BY"))
                body.addView(Ui.chips(this, Sorter.PIC_KEYS, sp.getString("sortPics", "Date") ?: "Date", 4) { v ->
                    sp.edit().putString("sortPics", v).apply()
                    reload()
                })
                body.addView(Ui.sectionLabel(this, "ORDER"))
                val cur = if (sp.getBoolean("descPics", true)) "↓ Descending" else "↑ Ascending"
                body.addView(Ui.chips(this, listOf("↑ Ascending", "↓ Descending"), cur, 2) { v ->
                    sp.edit().putBoolean("descPics", v.startsWith("↓")).apply()
                    reload()
                })
            }
            if (mode == "trash") {
                body.addView(Ui.sectionLabel(this, "RECYCLE BIN"))
                body.addView(Ui.dangerButton(this, "Empty recycle bin") {
                    Sheet.show(
                        this, "Empty the recycle bin?", "Everything in it is deleted forever.",
                        listOf(Sheet.Item("Empty now", 2) {
                            Trash.empty(this)
                            reload()
                        })
                    )
                })
            }
        }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selecting) exitSelect() else super.onBackPressed()
    }
}

// ---------------------------------------------------------------- full-screen viewer

class ViewerActivity : Activity() {
    private var mode = "folder"
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
        Theme.load(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        mode = intent.getStringExtra("mode") ?: "folder"
        files = loadFiles(
            this, mode,
            intent.getStringExtra("dir") ?: "",
            intent.getStringExtra("query") ?: "",
            intent.getStringExtra("scope") ?: "All"
        )
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
        barLayout.background = Ui.glassDark(this, 22)
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

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // Coming back from the editor: show the updated picture.
        if (resumedOnce && ::image.isInitialized && idx in files.indices) showCurrent()
        resumedOnce = true
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
        val f = files[idx]
        if (mode == "trash") {
            trashActions(this, f) { removeCurrent() }
        } else {
            fileActions(this, f) { removeCurrent() }
        }
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
        val shownName = if (mode == "trash") f.name.substringAfter('_', f.name) else f.name
        counter.text = "${idx + 1} / ${files.size}\n$shownName"
        if (mode != "trash") History.add(this, f.path)
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
