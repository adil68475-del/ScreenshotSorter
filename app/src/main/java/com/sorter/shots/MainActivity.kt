package com.sorter.shots

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private lateinit var foldersBox: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var pillsRow: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var backdrop: Backdrop
    private lateinit var scroll: ScrollView
    private var scope = "All"
    private var loadedVer = 0

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        loadedVer = Prefs.sp(this).getInt("themeVer", 0)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        try {
            Trash.purgeOld(this)
        } catch (_: Exception) {
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(28), dp(22), dp(32))
        col.isFocusableInTouchMode = true

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(this, "Folders", 34f, Ui.fg(), Ui.light()), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(Ui.iconButton(this, "⇅") { showSort() })
        head.addView(Ui.iconButton(this, "⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(head)

        val sub = Ui.text(this, "Your sorted screenshots", 14f, Ui.muted())
        sub.setPadding(0, 0, 0, dp(14))
        col.addView(sub)

        // search
        searchBox = EditText(this)
        searchBox.hint = "Search pictures or folders"
        searchBox.setTextColor(Ui.fg())
        searchBox.setHintTextColor(Ui.muted())
        searchBox.textSize = 15f
        searchBox.typeface = Ui.medium()
        searchBox.background = Ui.glass(this, 16)
        searchBox.setPadding(dp(16), dp(13), dp(16), dp(13))
        searchBox.setSingleLine()
        searchBox.inputType = InputType.TYPE_CLASS_TEXT
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                openSearch()
                true
            } else {
                false
            }
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshFolders()
            }
        })
        col.addView(searchBox, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(Ui.chips(this, listOf("All", "Folders", "Pictures"), "All", 3) {
            scope = it
            refreshFolders()
        })

        pillsRow = LinearLayout(this)
        pillsRow.orientation = LinearLayout.HORIZONTAL
        val pLp = LinearLayout.LayoutParams(MATCH, WRAP)
        pLp.topMargin = dp(8)
        col.addView(pillsRow, pLp)

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(MATCH, dp(14)))

        foldersBox = LinearLayout(this)
        foldersBox.orientation = LinearLayout.VERTICAL
        col.addView(foldersBox)

        val st = Ui.text(this, "SETUP", 12f, Ui.muted(), Ui.medium())
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
        Ui.styleWindow(this)
        Ui.fadeIn(scroll)
        col.requestFocus()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.sp(this).getInt("themeVer", 0) != loadedVer) {
            recreate()
            return
        }
        backdrop.start()
        refreshSetup()
        refreshPills()
        refreshFolders()
    }

    override fun onPause() {
        super.onPause()
        if (::backdrop.isInitialized) backdrop.stop()
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        return am.getRunningServices(100).any { it.service.className == WatcherService::class.java.name }
    }

    private fun openMode(mode: String) {
        val i = Intent(this, FolderActivity::class.java)
        i.putExtra("mode", mode)
        startActivity(i)
    }

    private fun openFolder(d: File) {
        val i = Intent(this, FolderActivity::class.java)
        i.putExtra("mode", "folder")
        i.putExtra("dir", d.path)
        startActivity(i)
    }

    private fun openSearch() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "Type something to search", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, FolderActivity::class.java)
        i.putExtra("mode", "search")
        i.putExtra("query", q)
        i.putExtra("scope", scope)
        startActivity(i)
    }

    // ------------------------------------------------------------ view & sort

    private fun showSort() {
        Sheet.panel(this, "View & sort") { body ->
            val sp = Prefs.sp(this)
            body.addView(Ui.sectionLabel(this, "LAYOUT"))
            val cur = if (sp.getString("foldersLayout", "list") == "grid") "Grid" else "List"
            body.addView(Ui.chips(this, listOf("List", "Grid"), cur, 2) { v ->
                sp.edit().putString("foldersLayout", if (v == "Grid") "grid" else "list").apply()
                refreshFolders()
            })
            body.addView(Ui.sectionLabel(this, "SORT BY"))
            body.addView(Ui.chips(this, Sorter.FOLDER_KEYS, sp.getString("sortFolders", "Name") ?: "Name", 4) { v ->
                sp.edit().putString("sortFolders", v).apply()
                refreshFolders()
            })
            body.addView(Ui.sectionLabel(this, "ORDER"))
            val order = if (sp.getBoolean("descFolders", false)) "↓ Descending" else "↑ Ascending"
            body.addView(Ui.chips(this, listOf("↑ Ascending", "↓ Descending"), order, 2) { v ->
                sp.edit().putBoolean("descFolders", v.startsWith("↓")).apply()
                refreshFolders()
            })
        }
    }

    // ------------------------------------------------------------ pills

    private fun refreshPills() {
        pillsRow.removeAllViews()
        val h = Ui.button(this, "🕘  History", false, true) { openMode("history") }
        val n = Trash.count(this)
        val t = Ui.button(this, if (n > 0) "🗑  Recycle bin ($n)" else "🗑  Recycle bin", false, true) { openMode("trash") }
        val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp1.rightMargin = dp(4)
        val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp2.leftMargin = dp(4)
        pillsRow.addView(h, lp1)
        pillsRow.addView(t, lp2)
    }

    // ------------------------------------------------------------ setup card

    private fun status(ok: Boolean): TextView =
        if (ok) Ui.text(this, "✓", 18f, Ui.GREEN, Ui.medium())
        else Ui.text(this, "Allow ›", 14f, Theme.accent, Ui.medium())

    private fun refreshSetup() {
        setupBox.removeAllViews()
        val overlay = Settings.canDrawOverlays(this)
        val files = Environment.isExternalStorageManager()
        setupBox.addView(Ui.row(this, "Display over other apps", null, status(overlay)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        setupBox.addView(Ui.row(this, "All files access", null, status(files)) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        })
        val running = serviceRunning()
        val label = if (running) "Stop watching" else "Start watching"
        setupBox.addView(Ui.button(this, label, !running, true) {
            if (running) {
                stopService(Intent(this, WatcherService::class.java))
                Prefs.sp(this).edit().putBoolean("watching", false).apply()
            } else if (overlay && files) {
                startForegroundService(Intent(this, WatcherService::class.java))
                Prefs.sp(this).edit().putBoolean("watching", true).apply()
            } else {
                Toast.makeText(this, "Allow both permissions first", Toast.LENGTH_SHORT).show()
            }
            scroll.postDelayed({ refreshSetup() }, 500)
        })
    }

    // ------------------------------------------------------------ folders

    private fun refreshFolders() {
        foldersBox.removeAllViews()
        val sp = Prefs.sp(this)
        val key = sp.getString("sortFolders", "Name") ?: "Name"
        val desc = sp.getBoolean("descFolders", false)
        val gridMode = sp.getString("foldersLayout", "list") == "grid"
        var dirs = Sorter.sortFolders(userFolders(), key, desc)
        val q = searchBox.text.toString().trim().lowercase()
        if (q.isNotEmpty() && scope != "Pictures") {
            dirs = dirs.filter { it.name.lowercase().contains(q) }
        }
        if (dirs.isEmpty()) {
            val msg = if (q.isNotEmpty()) "No folder matches. Press search to look inside pictures."
            else "No folders yet. Take a screenshot and create one in the popup."
            val tv = Ui.text(this, msg, 15f, Ui.muted())
            tv.setPadding(dp(4), dp(4), dp(4), dp(4))
            foldersBox.addView(tv)
            return
        }
        if (!gridMode) {
            for ((i, d) in dirs.withIndex()) {
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.bottomMargin = dp(10)
                foldersBox.addView(folderCard(d, i, false), lp)
            }
        } else {
            var idx = 0
            for (pair in dirs.chunked(2)) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                for ((j, d) in pair.withIndex()) {
                    val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                    lp.setMargins(if (j == 0) 0 else dp(5), 0, if (j == 0) dp(5) else 0, 0)
                    row.addView(folderCard(d, idx, true), lp)
                    idx++
                }
                if (pair.size == 1) {
                    val lp = LinearLayout.LayoutParams(0, 1, 1f)
                    lp.leftMargin = dp(5)
                    row.addView(View(this), lp)
                }
                val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
                rlp.bottomMargin = dp(10)
                foldersBox.addView(row, rlp)
            }
        }
    }

    private fun folderCard(d: File, i: Int, grid: Boolean): View {
        val n = listMedia(d).size
        val card = LinearLayout(this)
        if (!grid) {
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPadding(dp(18), dp(16), dp(18), dp(16))
            card.addView(Ui.text(this, "📁", 20f))
            val name = Ui.text(this, d.name, 17f, Ui.fg(), Ui.medium())
            name.setPadding(dp(14), 0, dp(8), 0)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            card.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(Ui.text(this, "$n", 15f, Ui.muted()))
            card.addView(Ui.text(this, "  ›", 20f, Ui.muted()))
        } else {
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(16), dp(16), dp(16), dp(16))
            card.addView(Ui.text(this, "📁", 28f))
            val name = Ui.text(this, d.name, 16f, Ui.fg(), Ui.medium())
            name.setPadding(0, dp(10), 0, 0)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            card.addView(name)
            card.addView(Ui.text(this, if (n == 1) "1 item" else "$n items", 13f, Ui.muted()))
        }
        card.background = Ui.ripple(this, Ui.glass(this, 18), 18)
        card.isClickable = true
        card.setOnClickListener { openFolder(d) }
        card.setOnLongClickListener {
            folderActions(d)
            true
        }
        card.alpha = 0f
        card.translationY = dp(14).toFloat()
        card.animate().alpha(1f).translationY(0f)
            .setStartDelay(minOf(i, 8) * 45L).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
        return card
    }

    private fun folderActions(d: File) {
        val n = listMedia(d).size
        Sheet.show(
            this, d.name, "$n item(s)",
            listOf(
                Sheet.Item("Rename", 0) {
                    Sheet.input(this, "Rename folder", d.name, "Rename") { raw ->
                        val nn = Ops.cleanName(raw)
                        if (nn.isNotEmpty() && nn != d.name) {
                            if (Ops.renameDir(this, d, nn)) {
                                val usage = getSharedPreferences("usage", MODE_PRIVATE)
                                val cnt = usage.getInt(d.name, 0)
                                usage.edit().remove(d.name).putInt(nn, cnt).apply()
                                Brain.renameFolder(this, d.name, nn)
                            } else {
                                Toast.makeText(this, "Couldn't rename (name taken?)", Toast.LENGTH_SHORT).show()
                            }
                            refreshFolders()
                        }
                    }
                },
                Sheet.Item("Export as ZIP", 0) { Ops.exportZip(this, listOf(d), d.name) },
                Sheet.Item("Delete folder", 2) {
                    Sheet.show(
                        this, "Delete \"${d.name}\"?",
                        "$n item(s) go to the Recycle bin and are removed after ${Trash.DAYS} days.",
                        listOf(Sheet.Item("Delete folder", 2) {
                            Ops.deleteDir(this, d)
                            getSharedPreferences("usage", MODE_PRIVATE).edit().remove(d.name).apply()
                            Brain.forgetFolder(this, d.name)
                            refreshFolders()
                            refreshPills()
                        })
                    )
                }
            )
        )
    }
}
