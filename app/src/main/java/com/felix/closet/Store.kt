package com.felix.closet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Everything lives in one JSON file in app-private storage, plus one JPEG per item in filesDir/img.
 * Wear history is the source of truth for "last worn" and "times worn"; nothing is stored twice.
 */
class Store private constructor(private val dir: File) {

    private val file = File(dir, "closet.json")
    val imgDir = File(dir, "img").apply { mkdirs() }

    private val items = mutableListOf<Item>()
    /** date (yyyy-MM-dd) -> item ids worn that day */
    private val wears = sortedMapOf<String, List<String>>()

    init { load() }

    @Synchronized fun items(): List<Item> = items.toList()
    @Synchronized fun item(id: String): Item? = items.firstOrNull { it.id == id }

    @Synchronized fun add(list: List<Item>) { items.addAll(list); save() }

    @Synchronized fun update(item: Item) {
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) { items[i] = item; save() }
    }

    @Synchronized fun delete(id: String) {
        val it = items.firstOrNull { x -> x.id == id } ?: return
        items.remove(it)
        it.img?.let { name -> File(imgDir, name).delete() }
        save()
    }

    fun imgFile(item: Item): File? = item.img?.let { File(imgDir, it) }?.takeIf { it.exists() }

    @Synchronized fun wornOn(date: LocalDate): List<String>? = wears[date.toString()]

    /** null clears the record for that day. */
    @Synchronized fun setWorn(date: LocalDate, ids: List<String>?) {
        if (ids.isNullOrEmpty()) wears.remove(date.toString()) else wears[date.toString()] = ids
        save()
    }

    /** id -> most recent date worn. Days after [before] (exclusive) are ignored when given. */
    @Synchronized fun lastWorn(before: LocalDate? = null): Map<String, LocalDate> {
        val out = HashMap<String, LocalDate>()
        for ((d, ids) in wears) {
            val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: continue
            if (before != null && !date.isBefore(before)) continue
            for (id in ids) { val p = out[id]; if (p == null || date.isAfter(p)) out[id] = date }
        }
        return out
    }

    @Synchronized fun wearCounts(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (ids in wears.values) for (id in ids) out[id] = (out[id] ?: 0) + 1
        return out
    }

    // ---------------- persistence ----------------

    private fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: run {
            // Never silently overwrite a file we could not read.
            file.copyTo(File(dir, "closet.broken.${System.currentTimeMillis()}.json"), overwrite = true)
            return
        }
        root.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val occ = mutableListOf<String>()
                o.optJSONArray("occasions")?.let { a -> for (j in 0 until a.length()) occ.add(a.optString(j)) }
                items.add(
                    Item(
                        id = id,
                        name = o.optString("name"),
                        cat = Cat.normalize(o.optString("cat")),
                        color = Colors.normalize(o.optString("color")),
                        warmth = o.optInt("warmth", 2).coerceIn(1, 3),
                        occasions = occ.filter { it in Occasion.ALL },
                        img = o.optString("img").ifEmpty { null },
                        added = o.optLong("added"),
                    )
                )
            }
        }
        root.optJSONObject("wears")?.let { w ->
            for (k in w.keys()) {
                val a = w.optJSONArray(k) ?: continue
                wears[k] = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("cat", it.cat).put("color", it.color)
                    .put("warmth", it.warmth).put("occasions", JSONArray(it.occasions))
                    .put("img", it.img ?: "").put("added", it.added)
            )
        }
        val w = JSONObject()
        for ((d, ids) in wears) w.put(d, JSONArray(ids))
        val root = JSONObject().put("version", 1).put("items", arr).put("wears", w)
        // Write-then-rename so a crash mid-write cannot corrupt the wardrobe.
        val tmp = File(dir, "closet.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx.applicationContext.filesDir).also { inst = it }
        }
    }
}
