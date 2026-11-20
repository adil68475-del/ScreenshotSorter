package com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private lateinit var foldersBox: LinearLayout
    private lateinit var pillsRow: LinearLayout
    private lateinit var noticeBox: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var backdrop: Backdrop
    private lateinit var scroll: ScrollView
    private var gate: FrameLayout? = null
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

        noticeBox = LinearLayout(this)
        noticeBox.orientation = LinearLayout.VERTICAL
        col.addView(noticeBox, LinearLayout.LayoutParams(MATCH, WRAP))

        col.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(14)))

        foldersBox = LinearLayout(this)
        foldersBox.orientation = LinearLayout.VERTICAL
        col.addView(foldersBox)

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col)

        backdrop = Backdrop(this, scroll)
        setContentView(backdrop.root)
        Ui.styleWindow(this)
        Ui.fadeIn(scroll)
        col.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.sp(this).getInt("themeVer", 0) != loadedVer) {
            recreate()
            return
        }
        backdrop.start()
        // The watcher starts by itself as soon as the permissions are there.
        val beat = Prefs.sp(this).getLong("beat", 0L)
        val wasKilled = Perms.core(this) && !Watchdog.running(this) && beat > 0L && killedByCleaner(beat)
        if (Perms.core(this)) {
            Watchdog.schedule(this)
            Watchdog.ensure(this)
        }
        refreshNotice(if (wasKilled) beat else 0L)
        updateGate()
        refreshPills()
        refreshFolders()
    }

    override fun onPause() {
        super.onPause()
        if (::backdrop.isInitialized) backdrop.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateGate()
    }

    // ------------------------------------------------------------ "stopped by a cleaner" notice

    // True if the last sign of life is newer than the last reboot and the last app update.
    private fun killedByCleaner(beat: Long): Boolean {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val upd = try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        return beat > boot && beat > upd
    }

    private fun refreshNotice(beat: Long) {
        noticeBox.removeAllViews()
        val sp = Prefs.sp(this)
        if (beat == 0L || sp.getLong("noticeBeat", 0L) == beat) return
        val card = Ui.panel(this)
        card.addView(Ui.text(this, "⚠  Your phone stopped the watcher", 16f, Ui.fg(), Ui.medium()))
        val msg = Ui.text(
            this,
            "A cleaner or battery saver closed Screenshot Saver (last active ${fmtDate(beat)}). It is running again now. Protect it so this doesn't happen again.",
            13f, Ui.muted()
        )
        msg.setPadding(0, dp(4), 0, dp(6))
        card.addView(msg)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val protect = Ui.button(this, "Protect it", true, true) { protectSheet(this) }
        val dismiss = Ui.button(this, "Dismiss", false, true) {
            sp.edit().putLong("noticeBeat", beat).apply()
            refreshNotice(0L)
        }
        protect.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.4f)
        val dLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        dLp.leftMargin = dp(8)
        dismiss.layoutParams = dLp
        row.addView(protect)
        row.addView(dismiss)
        card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(10)
        noticeBox.addView(card, lp)
    }

    // ------------------------------------------------------------ mandatory permission pop-up

    private fun updateGate() {
        val old = gate
        if (old != null) (old.parent as? ViewGroup)?.removeView(old)
        gate = null

        val core = Perms.core(this) && Perms.battery(this)
        val allDone = core && Perms.battery(this) && Perms.notif(this)
        val sp = Prefs.sp(this)
        if (allDone) sp.edit().putBoolean("gateSeen", true).apply()
        val seen = sp.getBoolean("gateSeen", false)
        if (core && (seen || allDone)) return

        val content = findViewById<ViewGroup>(android.R.id.content)
        val dim = FrameLayout(this)
        dim.setBackgroundColor(0xDD000000.toInt())
        dim.isClickable = true

        val c = Ui.panel(this)
        c.addView(Ui.text(this, "Before we start", 22f, Ui.fg(), Ui.light()))
        val intro = Ui.text(
            this,
            "Allow these once. After that the app runs by itself in the background, all the time. There is nothing to switch on.",
            13f, Ui.muted()
        )
        intro.setPadding(0, dp(6), 0, dp(8))
        c.addView(intro)
        c.addView(
            Ui.row(this, "Display over other apps", "Shows the folder popup on top of your screenshot",
                Ui.status(this, Perms.overlay(this))) { Perms.openOverlay(this) }
        )
        c.addView(
            Ui.row(this, "All files access", "Lets the app move pictures into your folders",
                Ui.status(this, Perms.files())) { Perms.openFiles(this) }
        )
        c.addView(
            Ui.row(this, "Keep running in background", "Required: stops the phone from closing the app",
                Ui.status(this, Perms.battery(this))) { Perms.openBattery(this) }
        )
        if (Build.VERSION.SDK_INT >= 33) {
            c.addView(
                Ui.row(this, "Notifications", "Shows the small \"running\" icon",
                    Ui.status(this, Perms.notif(this))) { Perms.askNotif(this) }
            )
        }
        val tip = Ui.text(
            this,
            "Tip: if your phone has a cleaner or booster app, add Screenshot Saver to its whitelist. See Settings → Protect from phone cleaners.",
            12f, Ui.muted()
        )
        tip.setPadding(dp(4), dp(8), dp(4), dp(2))
        c.addView(tip)
        if (core) {
            c.addView(Ui.button(this, "Continue", true, true) {
                Prefs.sp(this).edit().putBoolean("gateSeen", true).apply()
                updateGate()
            })
        } else {
            val hint = Ui.text(this, "Allow the first three to continue.", 12f, Ui.muted())
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, dp(10), 0, 0)
            c.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val w = minOf(resources.displayMetrics.widthPixels - dp(40), dp(380))
        dim.addView(c, FrameLayout.LayoutParams(w, WRAP, Gravity.CENTER))
        content.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))
        gate = dim
    }

    // ------------------------------------------------------------ navigation

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
