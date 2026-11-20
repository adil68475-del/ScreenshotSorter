package com.sorter.shots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import java.io.File

// How sure the AI must be before it saves a picture without asking.
private const val AUTO_SCORE = 14

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

    // Written every minute. If the app is opened later and the watcher is not running,
    // this timestamp tells us when a cleaner stopped it.
    private val heartbeat = object : Runnable {
        override fun run() {
            Prefs.sp(this@WatcherService).edit().putLong("beat", System.currentTimeMillis()).apply()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification =
        Notification.Builder(this, "watch")
            .setContentTitle("Screenshot Saver is running")
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
        nm.createNotificationChannel(
            NotificationChannel("auto", "Saved automatically", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, notification())
        for (u in uris) contentResolver.registerContentObserver(u, true, observer)
        Watchdog.schedule(this)
        heartbeat.run()
        handler.postDelayed({ check() }, 1500)
        val app = applicationContext
        Thread {
            try {
                Trash.purgeOld(app)
                AutoClean.run(app)
            } catch (_: Exception) {
            }
        }.start()
    }

    // Swiped away from recent apps: ask the watchdog to make sure we are still alive.
    override fun onTaskRemoved(rootIntent: Intent?) {
        Watchdog.kick(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(heartbeat)
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
        val auto = ai && Prefs.sp(this).getBoolean("autoSave", false)
        if (!auto) {
            popup = showPopupFor(this, f, null) { popup = null }
            return
        }
        // Auto mode: look first, save silently only if the AI is very sure.
        Brain.analyze(f, true) { a ->
            val s = if (a != null) Brain.suggest(this, a, userFolders().map { it.name }) else null
            val usage = getSharedPreferences("usage", Context.MODE_PRIVATE)
            val sure = a != null && s != null && s.learned && !s.isNew &&
                s.score >= AUTO_SCORE && usage.getInt(s.name, 0) >= 3
            if (sure && a != null && s != null && autoSave(f, a, s)) {
                // saved
            } else if (popup == null) {
                popup = showPopupFor(this, f, Pair(a, s)) { popup = null }
            }
        }
    }

    private fun autoSave(f: File, a: Brain.Analysis, s: Brain.Suggestion): Boolean {
        val back = f.parentFile?.path ?: return false
        val t = Ops.moveFile(this, f, File(FolderPopup.BASE, s.name)) ?: return false
        val usage = getSharedPreferences("usage", Context.MODE_PRIVATE)
        usage.edit().putInt(s.name, usage.getInt(s.name, 0) + 1).apply()
        History.add(this, t.path)
        Brain.learn(this, s.name, a.features)
        Brain.index(this, t.name, a)

        val id = (System.currentTimeMillis() % 100000).toInt() + 100
        val undo = Intent(this, UndoReceiver::class.java)
        undo.putExtra("to", t.path)
        undo.putExtra("back", back)
        undo.putExtra("nid", id)
        val pi = PendingIntent.getBroadcast(
            this, id, undo, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_revert), "Undo", pi
        ).build()
        val n = Notification.Builder(this, "auto")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Saved to ${s.name}")
            .setContentText(t.name)
            .addAction(action)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
        return true
    }
}

// "Undo" button on the auto-save notification: puts the picture back where it was.
class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val to = File(intent.getStringExtra("to") ?: return)
        val back = intent.getStringExtra("back") ?: return
        if (to.exists() && back.isNotEmpty()) {
            Ops.moveFile(ctx, to, File(back))
            Toast.makeText(ctx, "Moved back", Toast.LENGTH_SHORT).show()
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("nid", 0))
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
