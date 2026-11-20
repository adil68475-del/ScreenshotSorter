package com.sorter.shots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File

// On-device "AI": ML Kit looks at the picture (what is in it + any text), and the app
// remembers which folder you chose for pictures like it. Nothing is uploaded.
object Brain {
    class Analysis(val labels: List<String>, val words: List<String>, val links: List<String> = emptyList()) {
        val features: List<String> get() = labels.map { "l:$it" } + words.map { "w:$it" }
    }

    class Suggestion(
        val name: String,
        val isNew: Boolean,
        val reason: String,
        val score: Int = 0,
        val learned: Boolean = false
    )

    private val main = Handler(Looper.getMainLooper())
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val scanner by lazy { BarcodeScanning.getClient() }
    private val urlRe = Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE)

    private fun settings(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun store(ctx: Context) = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = settings(ctx).getBoolean("ai", false)
    fun setEnabled(ctx: Context, on: Boolean) {
        settings(ctx).edit().putBoolean("ai", on).apply()
    }

    // Labels that say nothing useful about the topic of a screenshot.
    private val generic = setOf(
        "text", "font", "screenshot", "rectangle", "line", "number", "parallel", "circle", "pattern",
        "technology", "gadget", "electronic device", "mobile phone", "communication device",
        "portable communications device", "multimedia", "display device", "screen", "software",
        "web page", "website", "document", "paper", "material property", "black", "white", "red",
        "blue", "green", "yellow", "orange", "pink", "purple", "brown", "grey", "gray", "colorfulness",
        "darkness", "monochrome", "symmetry", "art", "graphics", "design", "electric blue", "magenta",
        "event", "product", "brand", "logo", "icon", "advertising", "media", "label", "rectangle"
    )

    private val bodyLabels = setOf(
        "skin", "hair", "smile", "face", "head", "forehead", "chin", "eyebrow", "nose", "cheek",
        "eyelash", "mouth", "lip", "neck", "shoulder", "jaw", "selfie", "hand", "finger", "beard",
        "facial hair", "human body", "arm", "leg", "muscle", "eye", "ear", "gesture", "human"
    )

    // label -> folder idea
    private val ideas: Map<String, String> = buildMap {
        for (l in listOf("poster", "movie", "film", "cinema", "film poster")) put(l, "Watchlist")
        for (l in listOf("food", "dish", "cuisine", "meal", "recipe", "dessert", "pizza", "fast food",
            "baked goods", "vegetable", "fruit", "drink", "breakfast", "ingredient", "snack", "cake")) put(l, "Food")
        for (l in listOf("dog", "cat", "pet", "puppy", "kitten", "bird", "animal", "horse", "fish")) put(l, "Pets")
        for (l in listOf("car", "vehicle", "motorcycle", "automotive", "wheel", "truck", "motor vehicle")) put(l, "Cars")
        for (l in listOf("sky", "mountain", "beach", "sea", "tree", "flower", "nature", "landscape",
            "sunset", "cloud", "lake", "forest", "plant", "water")) put(l, "Nature")
        for (l in listOf("clothing", "dress", "fashion", "shoe", "jeans", "sleeve", "outerwear",
            "footwear", "jacket", "sneakers", "handbag")) put(l, "Fashion")
        for (l in listOf("building", "architecture", "city", "skyscraper", "house", "tower", "landmark",
            "bridge", "street")) put(l, "Places")
        for (l in listOf("laptop", "computer", "programming", "code", "keyboard", "circuit")) put(l, "Tech")
        for (l in listOf("cartoon", "illustration", "anime", "drawing", "painting", "sketch", "comics")) put(l, "Art")
        for (l in listOf("selfie", "smile", "face", "hair", "forehead", "skin", "crowd", "people")) put(l, "People")
        for (l in listOf("furniture", "interior design", "room", "couch", "table", "kitchen")) put(l, "Home ideas")
        for (l in listOf("book", "publication", "novel")) put(l, "Books")
        for (l in listOf("game", "video game", "pc game", "action-adventure game", "screenshot game")) put(l, "Games")
    }

    private val wordIdeas = mapOf(
        "whatsapp" to "Chats", "telegram" to "Chats", "messenger" to "Chats", "signal" to "Chats",
        "instagram" to "Social", "facebook" to "Social", "tiktok" to "Social", "twitter" to "Social",
        "snapchat" to "Social", "reddit" to "Social", "linkedin" to "Social",
        "netflix" to "Watchlist", "imdb" to "Watchlist", "trailer" to "Watchlist", "episode" to "Watchlist",
        "amazon" to "Shopping", "aliexpress" to "Shopping", "daraz" to "Shopping", "ebay" to "Shopping",
        "checkout" to "Shopping", "order" to "Shopping", "cart" to "Shopping",
        "recipe" to "Food", "ingredients" to "Food", "calories" to "Food",
        "bank" to "Payments", "payment" to "Payments", "transaction" to "Payments", "balance" to "Payments",
        "invoice" to "Payments", "receipt" to "Payments",
        "flight" to "Travel", "hotel" to "Travel", "booking" to "Travel", "ticket" to "Travel"
    )

    private val stop = setOf(
        "this", "that", "with", "from", "have", "your", "what", "when", "will", "about", "there",
        "their", "just", "like", "more", "than", "them", "then", "been", "were", "would", "could",
        "should", "into", "some", "also", "only", "very", "here", "back", "home", "share", "save",
        "send", "reply", "today", "yesterday", "message", "messages", "search", "settings", "news",
        "open", "close", "next", "done", "menu", "view", "more", "less", "none", "time", "date",
        "year", "week", "month", "photo", "photos", "video", "videos", "type", "post", "edit"
    )

    // wantLabels = false is the cheaper mode (only text + QR codes, for link detection).
    fun analyze(f: File, wantLabels: Boolean, done: (Analysis?) -> Unit) {
        Thread {
            val bmp = decodeSmall(f)
            if (bmp == null) {
                main.post { done(null) }
                return@Thread
            }
            val img = InputImage.fromBitmap(bmp, 0)
            labelStep(img, wantLabels) { labels ->
                textStep(img) { text ->
                    codeStep(img) { codes ->
                        done(Analysis(labels, keywords(text), linksFrom(text, codes)))
                    }
                }
            }
        }.start()
    }

    private fun labelStep(img: InputImage, want: Boolean, cb: (List<String>) -> Unit) {
        if (!want) {
            cb(emptyList())
            return
        }
        labeler.process(img)
            .addOnSuccessListener { ls ->
                cb(
                    ls.filter { it.confidence >= 0.6f }
                        .map { it.text.lowercase() }
                        .filter { it !in generic }
                        .distinct()
                        .take(8)
                )
            }
            .addOnFailureListener { cb(emptyList()) }
    }

    private fun textStep(img: InputImage, cb: (String) -> Unit) {
        recognizer.process(img)
            .addOnSuccessListener { t -> cb(t.text) }
            .addOnFailureListener { cb("") }
    }

    private fun codeStep(img: InputImage, cb: (List<String>) -> Unit) {
        scanner.process(img)
            .addOnSuccessListener { list -> cb(list.mapNotNull { it.rawValue }) }
            .addOnFailureListener { cb(emptyList()) }
    }

    private fun linksFrom(text: String, codes: List<String>): List<String> {
        val out = ArrayList<String>()
        for (c in codes) if (c.isNotBlank() && c !in out) out.add(c.trim())
        for (m in urlRe.findAll(text)) {
            val u = m.value.trimEnd('.', ',', ')', ';', ':')
            if (u.length > 6 && u !in out) out.add(u)
        }
        return out.take(3)
    }

    private fun decodeSmall(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(f.path, o2)
        } catch (e: Exception) {
            null
        }
    }

    private fun keywords(text: String): List<String> {
        val counts = HashMap<String, Int>()
        for (w in text.lowercase().split(Regex("[^a-z]+"))) {
            if (w.length in 4..14 && w !in stop) counts[w] = (counts[w] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .take(10)
            .map { it.key }
    }

    // ---- learning ----

    private fun load(ctx: Context): JSONObject =
        try {
            JSONObject(store(ctx).getString("model", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun save(ctx: Context, m: JSONObject) {
        store(ctx).edit().putString("model", m.toString()).apply()
    }

    fun learn(ctx: Context, folder: String, features: List<String>) {
        val m = load(ctx)
        val f = m.optJSONObject(folder) ?: JSONObject()
        for (x in features) f.put(x, f.optInt(x, 0) + 1)
        if (f.length() > 300) {
            val drop = ArrayList<String>()
            val keys = f.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (f.optInt(k) <= 1) drop.add(k)
            }
            for (k in drop) {
                if (f.length() <= 300) break
                f.remove(k)
            }
        }
        m.put(folder, f)
        save(ctx, m)
    }

    fun renameFolder(ctx: Context, old: String, new: String) {
        val m = load(ctx)
        val f = m.optJSONObject(old) ?: return
        m.remove(old)
        m.put(new, f)
        save(ctx, m)
    }

    fun forgetFolder(ctx: Context, name: String) {
        val m = load(ctx)
        m.remove(name)
        save(ctx, m)
    }

    fun resetAll(ctx: Context) {
        store(ctx).edit().clear().apply()
        ctx.getSharedPreferences("index", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---- search index (what the AI saw in each saved picture) ----

    fun index(ctx: Context, fileName: String, a: Analysis) {
        val text = (a.labels + a.words).joinToString(" ").take(240)
        ctx.getSharedPreferences("index", Context.MODE_PRIVATE).edit().putString(fileName, text).apply()
    }

    // ---- suggestions ----

    fun suggest(ctx: Context, a: Analysis, existing: List<String>): Suggestion? {
        val model = load(ctx)
        var best: String? = null
        var bestScore = 0
        for (name in existing) {
            val f = model.optJSONObject(name) ?: continue
            var s = 0
            for (l in a.labels) s += 2 * minOf(f.optInt("l:$l", 0), 3)
            for (w in a.words) s += minOf(f.optInt("w:$w", 0), 3)
            if (s > bestScore) {
                bestScore = s
                best = name
            }
        }
        if (best != null && bestScore >= 4) {
            return Suggestion(best, false, "Looks like your \"$best\" pictures", bestScore, true)
        }
        val idea = ideaFor(a) ?: return null
        val match = existing.firstOrNull { it.equals(idea, ignoreCase = true) }
        if (match != null) return Suggestion(match, false, "Looks like a $idea picture")
        return Suggestion(idea, true, "Looks like a $idea picture")
    }

    private fun ideaFor(a: Analysis): String? {
        for (l in a.labels) {
            val i = ideas[l]
            if (i != null) return i
        }
        for (w in a.words) {
            val i = wordIdeas[w]
            if (i != null) return i
        }
        val top = a.labels.firstOrNull { it !in bodyLabels } ?: return null
        return top.replaceFirstChar { it.uppercase() }
    }
}
