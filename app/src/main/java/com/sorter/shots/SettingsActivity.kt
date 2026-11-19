package com.sorter.shots

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
        render()
    }

    override fun onResume() {
        super.onResume()
        backdrop.start()
    }

    override fun onPause() {
        super.onPause()
        backdrop.stop()
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
        t.setPadding(dp(4), dp(12), 0, dp(0))
        return t
    }

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
            ai.addView(Ui.row(this, "Forget what the AI learned", null, Ui.text(this, "Reset ›", 14f, Theme.accent, Ui.medium())) {
                Brain.resetAll(this)
                Toast.makeText(this, "AI memory cleared", Toast.LENGTH_SHORT).show()
            })
        }

        // ---- general
        val gn = section("GENERAL")
        val auto = sp.getBoolean("autostart", true)
        gn.addView(
            Ui.row(
                this, "Start after restart",
                "Turns the watcher back on when the phone restarts. Some phones also need Auto-start allowed in phone settings.",
                Ui.onOffPill(this, auto)
            ) {
                sp.edit().putBoolean("autostart", !auto).apply()
                render()
            }
        )
        gn.addView(Ui.row(this, "Back up everything (ZIP)", "Saved to Downloads/ScreenshotSorter", Ui.text(this, "Export ›", 14f, Theme.accent, Ui.medium())) {
            Ops.exportZip(this, userFolders(), "ScreenshotSorter-backup")
        })
        gn.addView(Ui.row(this, "Clear picture history", null, Ui.text(this, "Clear ›", 14f, Theme.accent, Ui.medium())) {
            History.clear(this)
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        })

        // ---- recycle bin
        val rb = section("RECYCLE BIN")
        val n = Trash.count(this)
        rb.addView(
            Ui.row(
                this, "Open recycle bin", "Deleted pictures are kept ${Trash.DAYS} days. $n item(s) inside now.",
                Ui.text(this, "Open ›", 14f, Theme.accent, Ui.medium())
            ) {
                val i = android.content.Intent(this, FolderActivity::class.java)
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
