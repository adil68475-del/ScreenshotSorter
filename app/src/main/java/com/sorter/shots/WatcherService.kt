package com.sorter.shots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class WatcherService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPath: String? = null
    private var popup: FolderPopup? = null

    private val uris = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            check()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch", "Watching for screenshots", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "watch")
            .setContentTitle("Screenshot Sorter is watching")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
        startForeground(1, n)
        for (u in uris) contentResolver.registerContentObserver(u, true, observer)
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        popup?.dismiss()
        super.onDestroy()
    }

    private fun check() {
        if (popup != null) return
        for (u in uris) {
            val f = latest(u) ?: continue
            val p = f.absolutePath
            if (p == lastPath) continue
            lastPath = p
            waitStable(f, -1L, 0)
            return
        }
    }

    private fun latest(uri: Uri): File? {
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val proj = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.IS_PENDING
        )
        contentResolver.query(uri, proj, args, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val path = c.getString(0) ?: return null
            val added = c.getLong(1)
            val pending = c.getInt(2)
            if (pending == 1) return null
            if (System.currentTimeMillis() / 1000 - added > 20) return null
            val lower = path.lowercase()
            val looksRight = listOf("screenshot", "screenrecord", "screen_record", "screen record", "recorder")
                .any { lower.contains(it) }
            if (!looksRight) return null
            if (path.startsWith(FolderPopup.BASE.path)) return null
            val f = File(path)
            return if (f.exists()) f else null
        }
        return null
    }

    // Wait until the file stops growing (matters for screen recordings).
    private fun waitStable(f: File, prev: Long, tries: Int) {
        handler.postDelayed({
            if (!f.exists()) return@postDelayed
            val size = f.length()
            if ((size > 0 && size == prev) || tries > 60) show(f) else waitStable(f, size, tries + 1)
        }, 1200)
    }

    private fun show(f: File) {
        if (popup != null || !Settings.canDrawOverlays(this)) return
        popup = FolderPopup(this, f) { popup = null }
        popup?.show()
    }
}

class FolderPopup(
    private val ctx: Context,
    private val file: File,
    private val onClosed: () -> Unit
) {
    companion object {
        val BASE = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sorted")
        private const val BLUE = 0xFF2D6CDF.toInt()
        private const val GREEN = 0xFF2E9E5B.toInt()
        private const val GRAY = 0xFF444B5A.toInt()
        private const val MUTED = 0xFF9AA3B2.toInt()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = ctx.resources.displayMetrics
    private val root = FrameLayout(ctx)
    private var shown = false

    private fun dp(v: Int) = (v * dm.density).toInt()

    private fun bg(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(t: String, size: Float, color: Int): TextView {
        val tv = TextView(ctx)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        return tv
    }

    private fun button(text: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(Color.WHITE)
        b.background = bg(color)
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun card(): LinearLayout {
        root.removeAllViews()
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        c.background = bg(0xFF1F2430.toInt(), 20)
        c.isClickable = true
        root.addView(
            c,
            FrameLayout.LayoutParams(dm.widthPixels - dp(48), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        return c
    }

    fun show() {
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        root.setBackgroundColor(0xAA000000.toInt())
        root.setOnClickListener { dismiss() }
        wm.addView(root, p)
        shown = true
        showList()
    }

    private fun showList() {
        val c = card()
        c.addView(label("Save this to…", 20f, Color.WHITE))
        c.addView(label(file.name, 12f, MUTED))

        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        BASE.mkdirs()
        val folders = BASE.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (folders.isEmpty()) {
            val empty = label("No folders yet. Make one below.", 14f, MUTED)
            empty.setPadding(0, dp(12), 0, dp(4))
            list.addView(empty)
        }
        for (f in folders) list.addView(button(f.name, BLUE) { moveTo(f) })

        val maxH = (dm.heightPixels * 0.45).toInt()
        val sv = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        sv.addView(list)
        c.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        c.addView(button("+ New folder", GREEN) { showNew() })
        c.addView(button("Keep where it is", GRAY) { dismiss() })
    }

    private fun showNew() {
        val c = card()
        c.addView(label("New folder name", 20f, Color.WHITE))
        val et = EditText(ctx)
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(0xFF7A8294.toInt())
        et.hint = "e.g. Memes"
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        c.addView(et)
        c.addView(button("Create and save here", GREEN) {
            val name = et.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (name.isNotEmpty()) {
                val dir = File(BASE, name)
                dir.mkdirs()
                moveTo(dir)
            }
        })
        c.addView(button("Back", GRAY) { showList() })
        et.requestFocus()
        et.postDelayed({
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
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
            Toast.makeText(ctx, "Saved to ${dir.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't move: ${e.message}", Toast.LENGTH_LONG).show()
        }
        dismiss()
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        try { wm.removeView(root) } catch (_: Exception) {}
        onClosed()
    }
}
