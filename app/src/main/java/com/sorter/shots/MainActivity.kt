package com.sorter.shots

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private lateinit var foldersBox: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var backdrop: Backdrop
    private lateinit var scroll: ScrollView

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.styleWindow(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(34), dp(22), dp(32))

        col.addView(Ui.text(this, "Folders", 36f, Color.WHITE, Ui.light()))
        val sub = Ui.text(this, "Your sorted screenshots", 14f, Ui.MUTED)
        sub.setPadding(0, 0, 0, dp(18))
        col.addView(sub)

        foldersBox = LinearLayout(this)
        foldersBox.orientation = LinearLayout.VERTICAL
        col.addView(foldersBox)

        val st = Ui.text(this, "SETUP", 12f, Ui.MUTED, Ui.medium())
        st.letterSpacing = 0.12f
        st.setPadding(dp(4), dp(26), 0, dp(8))
        col.addView(st)

        setupBox = LinearLayout(this)
        setupBox.orientation = LinearLayout.VERTICAL
        setupBox.setPadding(dp(14), dp(8), dp(14), dp(12))
        setupBox.background = Ui.glass(this, 20)
        col.addView(setupBox)

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col)

        backdrop = Backdrop(this, scroll)
        setContentView(backdrop.root)
        Ui.fadeIn(scroll)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        backdrop.start()
        refreshSetup()
        refreshFolders()
    }

    override fun onPause() {
        super.onPause()
        backdrop.stop()
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        return am.getRunningServices(100).any { it.service.className == WatcherService::class.java.name }
    }

    private fun setupRow(label: String, ok: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(4), dp(13), dp(4), dp(13))
        r.addView(Ui.text(this, label, 15f, Color.WHITE, Ui.medium()), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (ok) {
            r.addView(Ui.text(this, "✓", 18f, Ui.GREEN, Ui.medium()))
        } else {
            r.addView(Ui.text(this, "Allow ›", 14f, 0xFF8FB0FF.toInt(), Ui.medium()))
        }
        val clear = GradientDrawable()
        clear.setColor(Color.TRANSPARENT)
        r.background = Ui.ripple(this, clear, 12)
        r.isClickable = true
        r.setOnClickListener { onClick() }
        return r
    }

    private fun refreshSetup() {
        setupBox.removeAllViews()
        val overlay = Settings.canDrawOverlays(this)
        val files = Environment.isExternalStorageManager()
        setupBox.addView(setupRow("Display over other apps", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        setupBox.addView(setupRow("All files access", files) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        })
        val running = serviceRunning()
        val label = if (running) "Stop watching" else "Start watching"
        setupBox.addView(Ui.button(this, label, !running, true) {
            if (running) {
                stopService(Intent(this, WatcherService::class.java))
            } else if (overlay && files) {
                startForegroundService(Intent(this, WatcherService::class.java))
            } else {
                Toast.makeText(this, "Allow both permissions first", Toast.LENGTH_SHORT).show()
            }
            scroll.postDelayed({ refreshSetup() }, 500)
        })
    }

    private fun refreshFolders() {
        foldersBox.removeAllViews()
        val dirs = FolderPopup.BASE.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        if (dirs.isEmpty()) {
            val tv = Ui.text(this, "No folders yet. Take a screenshot and create one in the popup.", 15f, Ui.MUTED)
            tv.setPadding(dp(4), dp(4), dp(4), dp(4))
            foldersBox.addView(tv)
        }
        for ((i, d) in dirs.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), dp(16), dp(18), dp(16))
            row.background = Ui.ripple(this, Ui.glass(this, 18), 18)
            row.addView(Ui.text(this, "📁", 20f))
            val name = Ui.text(this, d.name, 17f, Color.WHITE, Ui.medium())
            name.setPadding(dp(14), 0, dp(8), 0)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(Ui.text(this, "${listMedia(d).size}", 15f, Ui.MUTED))
            row.addView(Ui.text(this, "  ›", 20f, Ui.MUTED))
            row.isClickable = true
            row.setOnClickListener {
                val intent = Intent(this, FolderActivity::class.java)
                intent.putExtra("dir", d.path)
                startActivity(intent)
            }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.bottomMargin = dp(10)
            foldersBox.addView(row, lp)
            row.alpha = 0f
            row.translationY = dp(14).toFloat()
            row.animate().alpha(1f).translationY(0f)
                .setStartDelay(minOf(i, 8) * 45L).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }
}
