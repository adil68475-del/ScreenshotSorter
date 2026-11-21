package com.sorter.shots

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import android.content.res.ColorStateList

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// "Search my phone": type a site name, a chat name or any word from an old screenshot.
class PhoneSearchActivity : Activity() {
    private lateinit var backdrop: Backdrop
    private lateinit var searchBox: EditText
    private lateinit var statusBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            renderStatus()
            if (Indexer.running) handler.postDelayed(this, 800)
        }
    }

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
        head.addView(Ui.text(this, "Search my phone", 28f, Ui.fg(), Ui.light()))
        col.addView(head)

        val tip = Ui.text(
            this,
            "Type a site name, a chat name, or any word you remember from an old screenshot.",
            14f, Ui.muted()
        )
        tip.setPadding(dp(4), dp(12), dp(4), dp(12))
        col.addView(tip)

        searchBox = EditText(this)
        searchBox.hint = "e.g. amazon, Ahmed, receipt"
        searchBox.setTextColor(Ui.fg())
        searchBox.setHintTextColor(Ui.muted())
        searchBox.textSize = 16f
        searchBox.typeface = Ui.medium()
        searchBox.background = Ui.glass(this, 16)
        searchBox.setPadding(dp(16), dp(14), dp(16), dp(14))
        searchBox.setSingleLine()
        searchBox.inputType = InputType.TYPE_CLASS_TEXT
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setText(intent.getStringExtra("query") ?: "")
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }
        col.addView(searchBox, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(Ui.button(this, "Search", true, true) { doSearch() })

        statusBox = LinearLayout(this)
        statusBox.orientation = LinearLayout.VERTICAL
        statusBox.setPadding(dp(16), dp(14), dp(16), dp(14))
        statusBox.background = Ui.glass(this, 20)
        val sLp = LinearLayout.LayoutParams(MATCH, WRAP)
        sLp.topMargin = dp(18)
        col.addView(statusBox, sLp)

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
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        backdrop.stop()
        handler.removeCallbacks(poll)
    }

    private fun doSearch() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "Type something to search", Toast.LENGTH_SHORT).show()
            return
        }
        if (SearchIndex.count(this) == 0) {
            Toast.makeText(this, "Build the search index first (the button below)", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, FolderActivity::class.java)
        i.putExtra("mode", "phone")
        i.putExtra("query", q)
        startActivity(i)
    }

    private fun beginIndexing() {
        Prefs.sp(this).edit().putBoolean("phoneSearch", true).apply()
        Indexer.start(applicationContext)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun renderStatus() {
        statusBox.removeAllViews()
        val n = SearchIndex.count(this)
        if (!Perms.files()) {
            statusBox.addView(Ui.text(this, "Allow \"All files access\" first", 16f, Ui.fg(), Ui.medium()))
            statusBox.addView(Ui.button(this, "Open permission", true, true) { Perms.openFiles(this) })
            return
        }
        if (Indexer.running) {
            statusBox.addView(
                Ui.text(this, "Reading your screenshots…  ${Indexer.done} / ${Indexer.total}", 15f, Ui.fg(), Ui.medium())
            )
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = maxOf(1, Indexer.total)
            bar.progress = Indexer.done
            bar.progressTintList = ColorStateList.valueOf(Theme.accent)
            val bLp = LinearLayout.LayoutParams(MATCH, WRAP)
            bLp.topMargin = dp(10)
            statusBox.addView(bar, bLp)
            val note = Ui.text(
                this,
                "You can leave this screen. It keeps going in the background, and you can already search what is ready ($n so far).",
                12f, Ui.muted()
            )
            note.setPadding(0, dp(10), 0, 0)
            statusBox.addView(note)
            statusBox.addView(Ui.button(this, "Stop", false, true) { Indexer.stop = true })
            return
        }
        if (n == 0) {
            statusBox.addView(Ui.text(this, "Search is not set up yet", 16f, Ui.fg(), Ui.medium()))
            val note = Ui.text(
                this,
                "The app reads the text and objects in each screenshot once, on your phone, and remembers them. Nothing is uploaded. The first time can take 15 to 30 minutes for a few thousand screenshots.",
                13f, Ui.muted()
            )
            note.setPadding(0, dp(6), 0, dp(6))
            statusBox.addView(note)
            statusBox.addView(Ui.button(this, "Build search index", true, true) { beginIndexing() })
            return
        }
        statusBox.addView(Ui.text(this, "✓  $n screenshots ready to search", 16f, Ui.GREEN, Ui.medium()))
        val note = Ui.text(this, "New screenshots are added automatically.", 12f, Ui.muted())
        note.setPadding(0, dp(4), 0, dp(4))
        statusBox.addView(note)
        statusBox.addView(Ui.button(this, "Update now", false, true) { beginIndexing() })
        statusBox.addView(Ui.dangerButton(this, "Clear search index") {
            Sheet.show(
                this, "Clear the search index?", "Your pictures are not touched. You can build it again any time.",
                listOf(Sheet.Item("Clear", 2) {
                    Prefs.sp(this).edit().putBoolean("phoneSearch", false).apply()
                    SearchIndex.clear(this)
                    renderStatus()
                })
            )
        })
    }
}
