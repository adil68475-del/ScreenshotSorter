package com.sorter.shots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
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
