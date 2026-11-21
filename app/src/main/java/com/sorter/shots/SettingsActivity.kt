package com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class SettingsActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var backdrop: Backdrop

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(20), dp(22), dp(32))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.text(this, "‹", 36f, Ui.fg(), Ui.light())
        back.setPadding(0, 0, dp(14), dp(4))
        back.setOnClickListener { finish() }
        head.addView(back)
        head.addView(Ui.text(this, "Settings", 30f, Ui.fg(), Ui.light()))
        col.addView(head)

        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        col.addView(body)

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(col)
        backdrop = Backdrop(this, sv)
        setContentView(backdrop.root)
        Ui.styleWindow(this)
        Ui.fadeIn(sv)
    }

    override fun onResume() {
        super.onResume()
        backdrop.start()
        render() // refresh the permission ticks after coming back from system settings
    }

    override fun onPause() {
        super.onPause()
        backdrop.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun bumpTheme() {
        val sp = Prefs.sp(this)
        sp.edit().putInt("themeVer", sp.getInt("themeVer", 0) + 1).apply()
    }

    private fun section(title: String): LinearLayout {
        body.addView(Ui.sectionLabel(this, title))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(6), dp(14), dp(10))
        card.background = Ui.glass(this, 20)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(6)
        body.addView(card, lp)
        return card
    }

    private fun small(s: String): TextView {
        val t = Ui.text(this, s, 13f, Ui.muted(), Ui.medium())
        t.setPadding(dp(4), dp(12), 0, 0)
        return t
    }

    private fun link(s: String): TextView = Ui.text(this, s, 14f, Theme.accent, Ui.medium())

    private fun swatches(card: LinearLayout) {
        val per = 6
        val all = Theme.SWATCHES
        var i = 0
        while (i < all.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until per) {
                val idx = i + j
                val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
                lp.setMargins(dp(3), dp(3), dp(3), dp(3))
                if (idx >= all.size) {
                    row.addView(View(this), lp)
                    continue
                }
                val c = all[idx]
                val t = Ui.text(this, if (c == Theme.accent) "✓" else "", 18f, Color.WHITE, Ui.medium())
                t.gravity = Gravity.CENTER
                val g = GradientDrawable()
                g.setColor(c)
                g.cornerRadius = dp(12).toFloat()
                t.background = g
                t.setOnClickListener {
                    Prefs.sp(this).edit().putInt("accent", c).apply()
                    bumpTheme()
                    recreate()
                }
                row.addView(t, lp)
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            i += per
        }
    }

    private fun render() {
        body.removeAllViews()
        val sp = Prefs.sp(this)

        // ---- permissions (they live here, not on the main screen)
        val pm = section("PERMISSIONS")
        pm.addView(Ui.row(this, "Display over other apps", "Shows the folder popup on your screenshot",
            Ui.status(this, Perms.overlay(this))) { Perms.openOverlay(this) })
        pm.addView(Ui.row(this, "All files access", "Lets the app move pictures into your folders",
            Ui.status(this, Perms.files())) { Perms.openFiles(this) })
        pm.addView(Ui.row(this, "Keep running in background", "Stops the phone from closing the app (recommended)",
            Ui.status(this, Perms.battery(this))) { Perms.openBattery(this) })
        if (Build.VERSION.SDK_INT >= 33) {
            pm.addView(Ui.row(this, "Notifications", "Shows the small \"running\" icon",
                Ui.status(this, Perms.notif(this))) { Perms.askNotif(this) })
        }
        pm.addView(Ui.row(this, "Protect from phone cleaners", "Stops \"boost\" and cleaner apps from closing this app",
            link("Guide ›")) { protectSheet(this) })
        val running = Watchdog.running(this)
        pm.addView(
            Ui.row(
                this, "Watcher",
                if (running) "Running in the background, all the time" else "Not running yet. Allow the permissions above.",
                if (running) Ui.text(this, "Running ✓", 14f, Ui.GREEN, Ui.medium()) else link("Start ›")
            ) {
                Watchdog.ensure(this)
                body.postDelayed({ render() }, 700)
            }
        )

        // ---- appearance
        val ap = section("APPEARANCE")
        ap.addView(small("Theme"))
        val labels = listOf("Adaptive", "Light", "Dark", "AMOLED")
        val keys = listOf("adaptive", "light", "dark", "amoled")
        val cur = labels[keys.indexOf(Theme.mode).coerceAtLeast(0)]
        ap.addView(Ui.chips(this, labels, cur, 2) { v ->
            sp.edit().putString("mode", keys[labels.indexOf(v)]).apply()
            bumpTheme()
            recreate()
        })
        ap.addView(small("Colour"))
        swatches(ap)

        // ---- layout
        val ly = section("LAYOUT")
        ly.addView(small("Folders"))
        val fl = if (sp.getString("foldersLayout", "list") == "grid") "Grid" else "List"
        ly.addView(Ui.chips(this, listOf("List", "Grid"), fl, 2) { v ->
            sp.edit().putString("foldersLayout", if (v == "Grid") "grid" else "list").apply()
        })
        ly.addView(small("Pictures"))
        val pl = if (sp.getString("picLayout", "grid") == "list") "List" else "Grid"
        ly.addView(Ui.chips(this, listOf("Grid", "List"), pl, 2) { v ->
            sp.edit().putString("picLayout", if (v == "List") "list" else "grid").apply()
        })
        ly.addView(small("Grid columns"))
        ly.addView(Ui.chips(this, listOf("2", "3", "4", "5"), sp.getInt("picCols", 3).toString(), 4) { v ->
            sp.edit().putInt("picCols", v.toInt()).apply()
        })

        // ---- AI
        val ai = section("SMART SUGGESTIONS")
        val aiOn = Brain.enabled(this)
        ai.addView(
            Ui.row(
                this, "✨  AI suggestions",
                "Looks at each new screenshot on your phone (objects + text) and suggests a folder. It learns from the folders you pick. Nothing is uploaded.",
                Ui.onOffPill(this, aiOn)
            ) {
                Brain.setEnabled(this, !aiOn)
                render()
            }
        )
        if (aiOn) {
            val autoOn = sp.getBoolean("autoSave", false)
            ai.addView(
                Ui.row(
                    this, "Auto-save when very sure",
                    "If the AI has seen this kind of picture in a folder many times, it saves it there without asking and shows an Undo button.",
                    Ui.onOffPill(this, autoOn)
                ) {
                    sp.edit().putBoolean("autoSave", !autoOn).apply()
                    render()
                }
            )
            ai.addView(Ui.row(this, "Forget what the AI learned", null, link("Reset ›")) {
                Brain.resetAll(this)
                Toast.makeText(this, "AI memory cleared", Toast.LENGTH_SHORT).show()
            })
        }

        ai.addView(
            Ui.row(
                this, "Search my phone",
                "Find any old screenshot by typing a site name, a chat name or a word from it.",
                link("Open ›")
            ) { startActivity(Intent(this, PhoneSearchActivity::class.java)) }
        )
        ai.addView(
            Ui.row(
                this, "Organize old screenshots",
                "Sorts the screenshots already on your phone into folders. You approve each group.",
                link("Start ›")
            ) { startActivity(Intent(this, OrganizeActivity::class.java)) }
        )

        // ---- general
        val gn = section("GENERAL")
        val detectOn = sp.getBoolean("detect", true)
        gn.addView(
            Ui.row(
                this, "Detect links and QR codes",
                "Shows an Open link or Copy button in the popup when a screenshot has one.",
                Ui.onOffPill(this, detectOn)
            ) {
                sp.edit().putBoolean("detect", !detectOn).apply()
                render()
            }
        )
        gn.addView(Ui.row(this, "Private folders", "Long-press a folder on the home screen and choose Lock. It is hidden from the gallery and opens with your fingerprint or PIN.", null, null))
        gn.addView(Ui.row(this, "Back up everything (ZIP)", "Saved to Downloads/ScreenshotSaver (locked folders are skipped)", link("Export ›")) {
            Ops.exportZip(this, userFolders().filter { !Locks.isLocked(this, it.name) }, "ScreenshotSaver-backup")
        })
        gn.addView(Ui.row(this, "Clear picture history", null, link("Clear ›")) {
            History.clear(this)
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        })

        // ---- recycle bin
        val rb = section("RECYCLE BIN")
        val n = Trash.count(this)
        rb.addView(
            Ui.row(
                this, "Open recycle bin", "Deleted pictures are kept ${Trash.DAYS} days. $n item(s) inside now.",
                link("Open ›")
            ) {
                val i = Intent(this, FolderActivity::class.java)
                i.putExtra("mode", "trash")
                startActivity(i)
            }
        )
        rb.addView(Ui.row(this, "Empty recycle bin", null, Ui.text(this, "Empty ›", 14f, Color.parseColor("#FF6B6B"), Ui.medium())) {
            Sheet.show(
                this, "Empty the recycle bin?", "Everything in it is deleted forever.",
                listOf(Sheet.Item("Empty now", 2) {
                    Trash.empty(this)
                    render()
                })
            )
        })
    }
}
