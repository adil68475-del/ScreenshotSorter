package com.sorter.shots

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Looks at the screenshots already on the phone and proposes folders for them.
class OrganizeActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var backdrop: Backdrop

    private val analyses = HashMap<String, Brain.Analysis>()
    private val groups = LinkedHashMap<String, MutableList<File>>()
    private val unsure = ArrayList<File>()
    private var cancelled = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        head.addView(Ui.text(this, "Organize", 30f, Ui.fg(), Ui.light()))
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
        showIntro()
    }

    override fun onResume() {
        super.onResume()
        backdrop.start()
    }

    override fun onPause() {
        super.onPause()
        backdrop.stop()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun note(s: String): TextView {
        val t = Ui.text(this, s, 14f, Ui.muted())
        t.setPadding(dp(4), dp(12), dp(4), dp(8))
        return t
    }

    private fun showIntro() {
        body.removeAllViews()
        body.addView(
            note(
                "I'll look at the screenshots already on your phone and group them (for example Food, Watchlist, Chats). " +
                    "Nothing moves until you approve each group. It runs on your phone and nothing is uploaded. " +
                    "Keep this screen open while it works."
            )
        )
        body.addView(Ui.button(this, "Start", true, true) { start() })
    }

    private fun findOld(): List<File> {
        val out = ArrayList<File>()
        val exts = setOf("jpg", "jpeg", "png", "webp")
        for (r in listOf(Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM)) {
            val root = Environment.getExternalStoragePublicDirectory(r)
            val dirs = root.listFiles()?.filter { it.isDirectory && it.name.lowercase().contains("screenshot") }
                ?: emptyList()
            for (d in dirs) {
                val items = d.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts } ?: emptyList()
                out.addAll(items)
            }
        }
        return out.sortedByDescending { it.lastModified() }.take(300)
    }

    private fun start() {
        body.removeAllViews()
        body.addView(note("Looking for screenshots…"))
        Thread {
            val list = findOld()
            runOnUiThread {
                if (list.isEmpty()) {
                    body.removeAllViews()
                    body.addView(note("No old screenshots found in your Screenshots folders."))
                } else {
                    analyzeAll(list)
                }
            }
        }.start()
    }

    private fun analyzeAll(list: List<File>) {
        body.removeAllViews()
        analyses.clear()
        groups.clear()
        unsure.clear()
        cancelled = false

        val status = Ui.text(this, "", 15f, Ui.fg(), Ui.medium())
        status.setPadding(dp(4), dp(12), 0, dp(8))
        body.addView(status)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = list.size
        bar.progressTintList = ColorStateList.valueOf(Theme.accent)
        body.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(Ui.button(this, "Stop and show what I have", false, true) { cancelled = true })

        val names = userFolders().map { it.name }

        fun step(i: Int) {
            if (cancelled || i >= list.size) {
                showResults()
                return
            }
            status.text = "Looking at ${i + 1} / ${list.size}…"
            bar.progress = i
            val f = list[i]
            Brain.analyze(f, true) { a ->
                if (a == null) {
                    unsure.add(f)
                } else {
                    analyses[f.path] = a
                    val sg = Brain.suggest(this, a, names)
                    if (sg == null) {
                        unsure.add(f)
                    } else {
                        val key = (names + groups.keys).firstOrNull { it.equals(sg.name, true) } ?: sg.name
                        groups.getOrPut(key) { ArrayList() }.add(f)
                    }
                }
                step(i + 1)
            }
        }
        step(0)
    }

    private fun showResults() {
        body.removeAllViews()
        if (groups.isEmpty() && unsure.isEmpty()) {
            body.addView(note("Nothing to organize."))
            return
        }
        body.addView(note("Here is what I found. Move a group to its folder, rename it, or skip it."))
        val sorted = groups.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
        for ((name, files) in sorted) body.addView(groupCard(name, files))
        if (unsure.isNotEmpty()) body.addView(unsureCard())
    }

    private fun thumbRow(files: List<File>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (f in files.take(4)) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.background = Ui.glass(this, 10)
            Ui.round(iv, dp(10).toFloat())
            val lp = LinearLayout.LayoutParams(dp(64), dp(64))
            lp.rightMargin = dp(8)
            row.addView(iv, lp)
            Thumbs.load(f, dp(64), iv)
        }
        return row
    }

    private fun cardBox(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = Ui.glass(this, 20)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(12)
        card.layoutParams = lp
        return card
    }

    private fun groupCard(name: String, files: MutableList<File>): LinearLayout {
        val card = cardBox()
        val isNew = userFolders().none { it.name.equals(name, true) }
        card.addView(Ui.text(this, "📁  $name" + if (isNew) "   · new folder" else "", 17f, Ui.fg(), Ui.medium()))
        val count = Ui.text(this, "${files.size} pictures", 13f, Ui.muted())
        count.setPadding(0, dp(2), 0, dp(10))
        card.addView(count)
        card.addView(thumbRow(files))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val move = Ui.button(this, "Move ${files.size} here", true, true) { moveGroup(name, files, card) }
        val rename = Ui.button(this, "Rename", false, true) {
            Sheet.input(this, "Folder name", name, "Rename") { raw ->
                val n = Ops.cleanName(raw)
                if (n.isNotEmpty()) {
                    val moved = groups.remove(name)
                    if (moved != null) {
                        groups.getOrPut(n) { ArrayList() }.addAll(moved)
                        showResults()
                    }
                }
            }
        }
        val skip = Ui.button(this, "Skip", false, true) {
            groups.remove(name)
            showResults()
        }
        move.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.6f)
        val rLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        rLp.leftMargin = dp(6)
        rename.layoutParams = rLp
        val sLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        sLp.leftMargin = dp(6)
        skip.layoutParams = sLp
        row.addView(move)
        row.addView(rename)
        row.addView(skip)
        val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
        rowLp.topMargin = dp(10)
        card.addView(row, rowLp)
        return card
    }

    private fun moveGroup(name: String, files: List<File>, card: LinearLayout) {
        val dir = File(FolderPopup.BASE, name)
        var moved = 0
        for (f in files) {
            val t = Ops.moveFile(this, f, dir) ?: continue
            moved++
            val a = analyses[f.path]
            if (a != null) {
                Brain.learn(this, name, a.features)
                Brain.index(this, t.name, a)
            }
        }
        val usage = getSharedPreferences("usage", Context.MODE_PRIVATE)
        usage.edit().putInt(name, usage.getInt(name, 0) + moved).apply()
        groups.remove(name)
        card.removeAllViews()
        card.addView(Ui.text(this, "✓  Moved $moved to $name", 16f, Ui.GREEN, Ui.medium()))
    }

    private fun unsureCard(): LinearLayout {
        val card = cardBox()
        card.addView(Ui.text(this, "❔  Not sure about ${unsure.size}", 17f, Ui.fg(), Ui.medium()))
        val hint = Ui.text(this, "I couldn't tell what these are. Pick a folder yourself, or leave them.", 13f, Ui.muted())
        hint.setPadding(0, dp(2), 0, dp(10))
        card.addView(hint)
        card.addView(thumbRow(unsure))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val pick = Ui.button(this, "Move to a folder…", false, true) {
            movePicker(this, unsure.toList()) {
                unsure.clear()
                showResults()
            }
        }
        val skip = Ui.button(this, "Leave them", false, true) {
            unsure.clear()
            showResults()
        }
        pick.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1.4f)
        val sLp = LinearLayout.LayoutParams(0, WRAP, 1f)
        sLp.leftMargin = dp(6)
        skip.layoutParams = sLp
        row.addView(pick)
        row.addView(skip)
        val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
        rowLp.topMargin = dp(10)
        card.addView(row, rowLp)
        return card
    }
}
