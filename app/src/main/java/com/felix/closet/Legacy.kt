package com.felix.closet

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The first Android versions (v0.1.1–0.1.x) were native: clothes in filesDir/closet.json, one JPEG per item in
 * filesDir/img, settings in the "settings" SharedPreferences. Since the app became a shell around the web app,
 * that data is handed to the web side once, in the web app's own backup format plus a settings block.
 */
object Legacy {
    private val PROVIDERS = listOf("DOUBAO", "QWEN", "ZHIPU", "KIMI", "SILICONFLOW", "OPENAI", "GEMINI", "OPENROUTER", "CUSTOM")
    private val WEATHER = listOf("HOT", "WARM", "COOL", "COLD")

    /**
     * [closetJson]: contents of closet.json (null if absent); [image]: bytes for an image file name;
     * [prefs]: the old settings. Returns null when there is nothing worth moving.
     */
    fun toBackup(closetJson: String?, image: (String) -> ByteArray?, prefs: Map<String, *>): JSONObject? {
        val items = JSONArray()
        val images = JSONObject()
        val wears = JSONObject()
        val root = closetJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        root?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val bytes = o.optString("img").takeIf { it.isNotEmpty() }?.let(image)
                if (bytes != null) images.put(id, dataUrl(bytes))
                items.put(
                    JSONObject()
                        .put("id", id).put("name", o.optString("name"))
                        .put("cat", o.optString("cat", "上装")).put("color", o.optString("color", "灰"))
                        .put("warmth", o.optInt("warmth", 2).coerceIn(1, 3))
                        .put("occasions", o.optJSONArray("occasions") ?: JSONArray())
                        .put("material", "").put("img", bytes != null).put("added", o.optLong("added"))
                )
            }
        }
        root?.optJSONObject("wears")?.let { w -> for (k in w.keys()) w.optJSONArray(k)?.let { wears.put(k, it) } }

        val key = JSONObject(); val model = JSONObject(); val endpoint = JSONObject()
        for (p in PROVIDERS) {
            (prefs["key_$p"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { key.put(p, it) }
            (prefs["model_$p"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { model.put(p, it) }
            (prefs["endpoint_$p"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { endpoint.put(p, it) }
        }
        if (items.length() == 0 && key.length() == 0) return null

        val settings = JSONObject().put("key", key).put("model", model).put("endpoint", endpoint)
        (prefs["provider"] as? String)?.takeIf { it in PROVIDERS }?.let { settings.put("provider", it) }
        (prefs["weather"] as? String)?.takeIf { it in WEATHER }?.let { settings.put("weather", it) }
        (prefs["occasion"] as? String)?.takeIf { it.isNotEmpty() }?.let { settings.put("occasion", it) }

        return JSONObject().put("app", "closet").put("version", 1).put("items", items).put("wears", wears)
            .put("images", images).put("settings", settings)
    }

    private fun dataUrl(b: ByteArray): String {
        val png = b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
        return "data:image/${if (png) "png" else "jpeg"};base64," + Base64.getEncoder().encodeToString(b)
    }
}
