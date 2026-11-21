package com.sorter.shots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Second text reader (Tesseract) for scripts the built-in reader can't do, such as Arabic.
class OcrEngine(ctx: Context, langs: List<String>) {
    private val api = TessBaseAPI()
    val ok: Boolean = api.init(ctx.filesDir.path, langs.joinToString("+"))

    init {
        if (ok) api.setPageSegMode(11) // sparse text: finds scattered text such as chat bubbles
    }

    fun read(bmp: Bitmap): String {
        if (!ok) return ""
        return try {
            api.setImage(bmp)
            api.getUTF8Text() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun close() {
        try {
            api.recycle()
        } catch (_: Exception) {
        }
    }
}

object Ocr {
    val LANGS = listOf("ara" to "Arabic", "urd" to "Urdu", "fas" to "Persian")

    @Volatile var downloading: String? = null
    @Volatile var progress = 0

    // Tesseract looks for <filesDir>/tessdata/<code>.traineddata
    private fun dataDir(ctx: Context) = File(ctx.filesDir, "tessdata")

    fun has(ctx: Context, code: String): Boolean {
        val f = File(dataDir(ctx), "$code.traineddata")
        return f.exists() && f.length() > 100_000
    }

    fun installed(ctx: Context): List<String> = LANGS.map { it.first }.filter { has(ctx, it) }

    fun remove(ctx: Context, code: String) {
        File(dataDir(ctx), "$code.traineddata").delete()
    }

    // One-time download of a language file. done() runs on the main thread.
    fun download(ctx: Context, code: String, done: (Boolean) -> Unit) {
        if (downloading != null) return
        downloading = code
        progress = 0
        val app = ctx.applicationContext
        Thread {
            var ok = false
            try {
                val dir = dataDir(app)
                dir.mkdirs()
                val tmp = File(dir, "$code.part")
                val url = URL("https://github.com/tesseract-ocr/tessdata_fast/raw/main/$code.traineddata")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode == 200) {
                    val len = conn.contentLength
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(32768)
                            var sum = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sum += n
                                if (len > 0) progress = (sum * 100 / len).toInt()
                            }
                        }
                    }
                    if (tmp.length() > 100_000 && tmp.renameTo(File(dir, "$code.traineddata"))) ok = true
                }
                conn.disconnect()
                if (!ok) tmp.delete()
            } catch (_: Exception) {
            }
            downloading = null
            Handler(Looper.getMainLooper()).post { done(ok) }
        }.start()
    }

    // Bigger than the version used for objects, because small Arabic text needs more pixels.
    fun decodeForOcr(f: File): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 2000) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        BitmapFactory.decodeFile(f.path, o2)
    } catch (e: Exception) {
        null
    }

    // So that "أحمد" and "احمد", or text with and without vowel marks, match each other.
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '\u064B'..'\u065F', '\u0670', '\u0640' -> {}
                '\u0623', '\u0625', '\u0622', '\u0671' -> sb.append('\u0627')
                '\u0649', '\u06CC' -> sb.append('\u064A')
                '\u0629' -> sb.append('\u0647')
                '\u06A9' -> sb.append('\u0643')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
