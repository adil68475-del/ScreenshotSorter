package com.sorter.shots

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

// Folders the user made (the hidden .trash folder is not one of them).
fun userFolders(): List<File> =
    FolderPopup.BASE.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()

fun fmtSize(b: Long): String = when {
    b >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", b / 1e9)
    b >= 1_000_000L -> String.format(Locale.US, "%.1f MB", b / 1e6)
    b >= 1_000L -> String.format(Locale.US, "%d KB", b / 1000)
    else -> "$b B"
}

fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

object Sorter {
    val PIC_KEYS = listOf("Name", "Date", "Size", "Resolution", "Length", "Type", "Folder")
    val FOLDER_KEYS = listOf("Name", "Date", "Size", "Items")

    private val resCache = HashMap<String, Long>()
    private val lenCache = HashMap<String, Long>()

    fun resolution(f: File): Long = resCache.getOrPut(f.path) {
        if (f.isVideoFile()) {
            0L
        } else {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            o.outWidth.toLong() * o.outHeight.toLong()
        }
    }

    fun length(f: File): Long = lenCache.getOrPut(f.path) {
        if (!f.isVideoFile()) {
            0L
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                r.release()
            }
        }
    }

    fun sortPics(files: List<File>, key: String, desc: Boolean): List<File> {
        val cmp: Comparator<File> = when (key) {
            "Name" -> compareBy<File> { it.name.lowercase() }
            "Size" -> compareBy<File> { it.length() }
            "Resolution" -> compareBy<File> { resolution(it) }
            "Length" -> compareBy<File> { length(it) }
            "Type" -> compareBy<File>({ it.extension.lowercase() }, { it.name.lowercase() })
            "Folder" -> compareBy<File>({ it.parentFile?.name?.lowercase() ?: "" }, { it.name.lowercase() })
            else -> compareBy<File> { it.lastModified() }
        }
        val sorted = files.sortedWith(cmp)
        return if (desc) sorted.reversed() else sorted
    }

    fun sortFolders(dirs: List<File>, key: String, desc: Boolean): List<File> {
        val stat = HashMap<String, LongArray>() // [newest, bytes, count]
        for (d in dirs) {
            val items = listMedia(d)
            stat[d.path] = longArrayOf(
                items.maxOfOrNull { it.lastModified() } ?: d.lastModified(),
                items.sumOf { it.length() },
                items.size.toLong()
            )
        }
        val cmp: Comparator<File> = when (key) {
            "Date" -> compareBy<File> { stat[it.path]?.get(0) ?: 0L }
            "Size" -> compareBy<File> { stat[it.path]?.get(1) ?: 0L }
            "Items" -> compareBy<File> { stat[it.path]?.get(2) ?: 0L }
            else -> compareBy<File> { it.name.lowercase() }
        }
        val sorted = dirs.sortedWith(cmp)
        return if (desc) sorted.reversed() else sorted
    }
}

// Pictures you saved, moved or opened recently (newest first).
object History {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<String> =
        (sp(ctx).getString("items", "") ?: "").split("\n").filter { it.isNotEmpty() }

    private fun save(ctx: Context, l: List<String>) {
        sp(ctx).edit().putString("items", l.joinToString("\n")).apply()
    }

    fun add(ctx: Context, path: String) {
        val cur = list(ctx).toMutableList()
        cur.remove(path)
        cur.add(0, path)
        while (cur.size > 200) cur.removeAt(cur.size - 1)
        save(ctx, cur)
    }

    fun replace(ctx: Context, old: String, new: String) {
        val cur = list(ctx).toMutableList()
        val i = cur.indexOf(old)
        if (i >= 0) {
            cur[i] = new
            save(ctx, cur)
        }
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}

// Deleted pictures wait here for 30 days. Hidden from the gallery (.nomedia).
object Trash {
    const val DAYS = 30
    val DIR = File(FolderPopup.BASE, ".trash")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("trash", Context.MODE_PRIVATE)

    fun entries(ctx: Context): List<File> =
        listMedia(DIR).sortedByDescending { deletedAt(ctx, it) }

    fun count(ctx: Context): Int = listMedia(DIR).size

    private fun meta(ctx: Context, f: File): String? = sp(ctx).getString(f.name, null)

    fun originalOf(ctx: Context, f: File): File? {
        val m = meta(ctx, f) ?: return null
        val i = m.lastIndexOf('|')
        return if (i > 0) File(m.substring(0, i)) else null
    }

    fun deletedAt(ctx: Context, f: File): Long {
        val m = meta(ctx, f) ?: return f.lastModified()
        val i = m.lastIndexOf('|')
        return if (i > 0) (m.substring(i + 1).toLongOrNull() ?: f.lastModified()) else f.lastModified()
    }

    fun daysLeft(ctx: Context, f: File): Int {
        val age = (System.currentTimeMillis() - deletedAt(ctx, f)) / 86_400_000L
        return (DAYS - age).toInt().coerceAtLeast(0)
    }

    fun move(ctx: Context, f: File): Boolean {
        return try {
            DIR.mkdirs()
            try {
                File(DIR, ".nomedia").createNewFile()
            } catch (_: Exception) {
            }
            val tname = "${System.currentTimeMillis()}_${f.name}"
            val t = File(DIR, tname)
            if (!f.renameTo(t)) {
                f.copyTo(t)
                f.delete()
            }
            sp(ctx).edit().putString(tname, "${f.path}|${System.currentTimeMillis()}").apply()
            Ops.scan(ctx, listOf(f.path))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun restore(ctx: Context, f: File): Boolean {
        return try {
            val orig = originalOf(ctx, f)
            val cleanName = f.name.substringAfter('_', f.name)
            var target = orig ?: File(File(FolderPopup.BASE, "Restored"), cleanName)
            target.parentFile?.mkdirs()
            if (target.exists()) target = File(target.parentFile, "${System.currentTimeMillis()}_${target.name}")
            if (!f.renameTo(target)) {
                f.copyTo(target)
                f.delete()
            }
            sp(ctx).edit().remove(f.name).apply()
            Ops.scan(ctx, listOf(target.path))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteForever(ctx: Context, f: File) {
        f.delete()
        sp(ctx).edit().remove(f.name).apply()
    }

    fun empty(ctx: Context) {
        for (f in listMedia(DIR)) f.delete()
        sp(ctx).edit().clear().apply()
    }

    fun purgeOld(ctx: Context) {
        val cutoff = System.currentTimeMillis() - DAYS * 86_400_000L
        for (f in listMedia(DIR)) {
            if (deletedAt(ctx, f) < cutoff) deleteForever(ctx, f)
        }
    }
}
