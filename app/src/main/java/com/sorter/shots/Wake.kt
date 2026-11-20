package com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

// Quick Settings tile. The system starts this service whenever the quick panel opens,
// which gives the app a chance to restart the watcher even after a cleaner stopped it.
class SortTile : TileService() {
    override fun onStartListening() {
        wake()
    }

    override fun onClick() {
        wake()
        if (!Perms.core(this)) {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(i)
        }
    }

    private fun wake() {
        Watchdog.schedule(this)
        Watchdog.ensure(this)
        val t = qsTile ?: return
        val ok = Perms.core(this)
        t.state = if (ok) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Screenshot Saver"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (ok) "Watching" else "Needs setup"
        t.updateTile()
    }
}

// "Share → Screenshot Saver": works even if the watcher is not running, and wakes it up.
class ShareActivity : Activity() {
    @Suppress("DEPRECATION")
    private fun streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        Watchdog.schedule(this)
        Watchdog.ensure(this)
        val uri = streamUri()
        if (uri == null || !Perms.overlay(this)) {
            Toast.makeText(this, "Open Screenshot Saver and allow the permissions first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Thread {
            val f = resolve(uri)
            runOnUiThread {
                if (f == null) {
                    Toast.makeText(this, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
                } else {
                    val app = applicationContext
                    val ai = Brain.enabled(app) && !f.isVideoFile()
                    val p = FolderPopup(app, f, ai) {}
                    p.show()
                    if (ai) {
                        Brain.analyze(f) { a ->
                            val names = userFolders().map { it.name }
                            val s = if (a != null) Brain.suggest(app, a, names) else null
                            p.setAnalysis(a, s)
                        }
                    }
                }
                finish()
            }
        }.start()
    }

    private fun resolve(uri: Uri): File? {
        try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0)
                    if (p != null) {
                        val f = File(p)
                        if (f.exists()) return f
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Not a normal media file: copy it into a hidden holding folder, the popup moves it from there.
        return try {
            val dir = File(FolderPopup.BASE, ".incoming")
            dir.mkdirs()
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { if (now - it.lastModified() > 86_400_000L) it.delete() }
            val mime = contentResolver.getType(uri)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val out = File(dir, "shared_$now.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            if (out.exists() && out.length() > 0) out else null
        } catch (e: Exception) {
            null
        }
    }
}
