package com.sorter.shots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
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

    private fun notification(): Notification =
        Notification.Builder(this, "watch")
            .setContentTitle("Screenshot Sorter is running")
            .setContentText("Ready to sort your next screenshot")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch", "Watching for screenshots", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, notification())
        for (u in uris) contentResolver.registerContentObserver(u, true, observer)
        Watchdog.schedule(this)
        try {
            Trash.purgeOld(this)
        } catch (_: Exception) {
        }
    }

    // Swiped away from recent apps: ask the watchdog to make sure we are still alive.
    override fun onTaskRemoved(rootIntent: Intent?) {
        Watchdog.kick(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        popup?.dismiss()
        Watchdog.kick(this)
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
        val ai = Brain.enabled(this) && !f.isVideoFile()
        val p = FolderPopup(this, f, ai) { popup = null }
        popup = p
        p.show()
        if (ai) {
            Brain.analyze(f) { a ->
                val names = userFolders().map { it.name }
                val s = if (a != null) Brain.suggest(this, a, names) else null
                p.setAnalysis(a, s)
            }
        }
    }
}

// Starts the watcher after the phone restarts and after the app is updated.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Watchdog.schedule(ctx)
        Watchdog.ensure(ctx)
    }
}
