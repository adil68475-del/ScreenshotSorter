package com.sorter.shots

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, pad) }
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
    }

    private fun refresh() {
        val o = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        val f = if (Environment.isExternalStorageManager()) "✓" else "✗"
        status.text = "Display over apps: $o\nAll files access: $f\n\nDo steps 1 and 2, then tap 3."
    }
}
