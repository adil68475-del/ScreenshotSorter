package com.sorter.shots

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IndexDb(ctx: Context) : SQLiteOpenHelper(ctx, "index.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE docs (path TEXT PRIMARY KEY, modified INTEGER, text TEXT, labels TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

// A private, on-device list of "what is written on / shown in" each screenshot.
object SearchIndex {
    private var helper: IndexDb? = null

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        var h = helper
        if (h == null) {
            h = IndexDb(ctx.applicationContext)
            helper = h
        }
        return h.writableDatabase
    }

    fun count(ctx: Context): Int = DatabaseUtils.queryNumEntries(db(ctx), "docs").toInt()

    fun known(ctx: Context): HashMap<String, Long> {
        val m = HashMap<String, Long>()
        db(ctx).rawQuery("SELECT path, modified FROM docs", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = c.getLong(1)
        }
        return m
    }

    fun put(ctx: Context, path: String, modified: Long, text: String, labels: String) {
        db(ctx).execSQL(
            "INSERT OR REPLACE INTO docs (path, modified, text, labels) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(path, modified, text, labels)
        )
    }

    fun prune(ctx: Context, keep: Set<String>) {
        val d = db(ctx)
        val gone = ArrayList<String>()
        d.rawQuery("SELECT path FROM docs", null).use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0)
                if (p !in keep) gone.add(p)
            }
        }
        for (p in gone) d.delete("docs", "path = ?", arrayOf(p))
    }

    fun removeFolder(ctx: Context, dirPath: String) {
        db(ctx).delete("docs", "path LIKE ?", arrayOf("$dirPath/%"))
    }

    fun clear(ctx: Context) {
        db(ctx).delete("docs", null, null)
    }

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    // Levenshtein distance <= max, with an early exit.
    private fun withinEdits(a: String, b: String, max: Int): Boolean {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return false
            prev = cur
        }
        return prev[b.length] <= max
    }

    // 5 = whole word, 3 = part of a word, 1 = looks like a word (typo), 0 = not found
    private fun tokenScore(t: String, hay: String, words: () -> List<String>): Int {
        if (hay.contains(t)) return if (words().any { it == t }) 5 else 3
        if (t.length < 4) return 0
        val max = if (t.length >= 7) 2 else 1
        for (w in words()) {
            if (Math.abs(w.length - t.length) <= max && withinEdits(t, w, max)) return 1
        }
        return 0
    }

    // Best matches first. Every word you typed has to be found (partial words and small typos are OK).
    fun search(ctx: Context, query: String): List<File> {
        val tokens = Ocr.normalize(query.lowercase()).split(splitter).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<File, Int>>()
        db(ctx).rawQuery("SELECT path, text, labels FROM docs", null).use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                val f = File(path)
                val parent = f.parentFile?.name ?: ""
                if (Locks.isLocked(ctx, parent)) continue
                val hay = (c.getString(1) ?: "") + " " + (c.getString(2) ?: "") + " " +
                    Ocr.normalize(f.name.lowercase()) + " " + Ocr.normalize(parent.lowercase())
                var words: List<String>? = null
                var score = 0
                var all = true
                for (t in tokens) {
                    val s = tokenScore(t, hay) {
                        var w = words
                        if (w == null) {
                            w = hay.split(splitter)
                            words = w
                        }
                        w
                    }
                    if (s == 0) {
                        all = false
                        break
                    }
                    score += s
                }
                if (all && f.exists()) hits.add(f to score)
            }
        }
        return hits
            .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenByDescending { it.first.lastModified() })
            .map { it.first }
    }
}

// Reads every screenshot once (text + objects) and keeps the results in SearchIndex.
// Works in the background, can be stopped, and only does new / changed pictures next time.
object Indexer {
    @Volatile var running = false
    @Volatile var total = 0
    @Volatile var done = 0
    @Volatile var stop = false

    // Throws the index away and reads everything again (use after adding a language).
    fun rebuild(ctx: Context) {
        if (running) return
        SearchIndex.clear(ctx)
        start(ctx)
    }

    fun start(ctx: Context) {
        if (running || !Perms.files()) return
        running = true
        stop = false
        done = 0
        total = 0
        val app = ctx.applicationContext
        Thread {
            try {
                work(app)
            } catch (_: Exception) {
            } finally {
                running = false
            }
        }.start()
    }

    // Called when new screenshots may have appeared; does nothing unless the user turned search on.
    fun auto(ctx: Context) {
        if (Prefs.sp(ctx).getBoolean("phoneSearch", false)) start(ctx)
    }

    // Screenshots anywhere on the phone, plus pictures in this app's folders.
    private fun candidates(ctx: Context): List<File> {
        val out = ArrayList<File>()
        val sel = "${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?"
        val args = arrayOf("%screenshot%", FolderPopup.BASE.path + "/%")
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DATA),
            sel, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0) ?: continue
                if (p.contains("/.")) continue // hidden folders such as the recycle bin
                val f = File(p)
                if (f.exists()) out.add(f)
            }
        }
        return out
    }

    private fun work(ctx: Context) {
        val known = SearchIndex.known(ctx)
        val files = candidates(ctx)
        SearchIndex.prune(ctx, files.map { it.path }.toSet())
        val todo = files.filter { known[it.path] != it.lastModified() }
        total = todo.size
        done = 0
        val langs = Ocr.installed(ctx)
        if (known.isEmpty()) {
            Prefs.sp(ctx).edit().putString("indexLangs", langs.joinToString(",")).apply()
        }
        val engine = if (langs.isNotEmpty()) OcrEngine(ctx, langs) else null
        try {
            for (f in todo) {
                if (stop) break
                val latch = CountDownLatch(1)
                var res: Brain.Analysis? = null
                Brain.analyze(f, true) { a ->
                    res = a
                    latch.countDown()
                }
                latch.await(25, TimeUnit.SECONDS)
                val a = res
                var text = a?.fullText ?: ""
                if (engine != null && engine.ok) {
                    val bmp = Ocr.decodeForOcr(f)
                    if (bmp != null) {
                        text = text + " " + engine.read(bmp)
                        bmp.recycle()
                    }
                }
                text = Ocr.normalize(text.lowercase()).replace(Regex("\\s+"), " ").take(6000)
                val labels = a?.labels?.joinToString(" ") ?: ""
                SearchIndex.put(ctx, f.path, f.lastModified(), text, labels)
                done++
                Thread.sleep(30) // keeps the phone cool
            }
        } finally {
            engine?.close()
        }
    }
}
