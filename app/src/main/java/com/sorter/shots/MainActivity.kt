package com.sorter.shots

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var foldersBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        val t1 = TextView(this)
        t1.text = "My folders"
        t1.textSize = 22f
        t1.setPadding(0, 0, 0, pad / 2)
        col.addView(t1)

        foldersBox = LinearLayout(this)
        foldersBox.orientation = LinearLayout.VERTICAL
        col.addView(foldersBox)

        val t2 = TextView(this)
        t2.text = "Setup"
        t2.textSize = 22f
        t2.setPadding(0, pad, 0, pad / 2)
        col.addView(t2)

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(0, 0, 0, pad / 2)
        col.addView(status)

        fun btn(label: String, action: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { action() }
            col.addView(b)
        }

        btn("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Allow all files access") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("3. Start watching") {
            if (Settings.canDrawOverlays(this) && Environment.isExternalStorageManager()) {
                startForegroundService(Intent(this, WatcherService::class.java))
            }
            refresh()
        }
        btn("Stop watching") {
            stopService(Intent(this, WatcherService::class.java))
            refresh()
        }

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshFolders()
    }

    private fun refresh() {
        val o = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        val f = if (Environment.isExternalStorageManager()) "✓" else "✗"
        status.text = "Display over apps: $o\nAll files access: $f"
    }

    private fun refreshFolders() {
        foldersBox.removeAllViews()
        val dirs = FolderPopup.BASE.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        if (dirs.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No folders yet. Take a screenshot and make one in the popup."
            tv.textSize = 15f
            foldersBox.addView(tv)
        }
        for (d in dirs) {
            val n = listMedia(d).size
            val b = Button(this)
            b.text = "📁  ${d.name}   ($n)"
            b.isAllCaps = false
            b.textSize = 16f
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.setOnClickListener {
                val i = Intent(this, FolderActivity::class.java)
                i.putExtra("dir", d.path)
                startActivity(i)
            }
            foldersBox.addView(b)
        }
    }
}
