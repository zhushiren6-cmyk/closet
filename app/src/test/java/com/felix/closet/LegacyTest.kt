package com.felix.closet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTest {
    private val closet = """
        {"version":1,
         "items":[{"id":"a","name":"黑色T恤","cat":"上装","color":"黑","warmth":1,"occasions":["日常"],"img":"a.jpg","added":5},
                  {"id":"b","name":"牛仔裤","cat":"下装","color":"牛仔蓝","warmth":7,"occasions":[],"img":"","added":6},
                  {"id":"","name":"坏数据"}],
         "wears":{"2026-10-01":["a","b"]}}
    """.trimIndent()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    @Test fun convertsItemsImagesWearsAndKeys() {
        val prefs = mapOf("provider" to "KIMI", "key_KIMI" to " sk-1 ", "key_DOUBAO" to "", "model_QWEN" to "qwen-x",
            "weather" to "COOL", "occasion" to "通勤")
        val out = Legacy.toBackup(closet, { if (it == "a.jpg") jpeg else null }, prefs)!!
        assertEquals("closet", out.getString("app"))
        val items = out.getJSONArray("items")
        assertEquals(2, items.length())
        val a = items.getJSONObject(0)
        assertEquals("黑色T恤", a.getString("name")); assertTrue(a.getBoolean("img"))
        val b = items.getJSONObject(1)
        assertEquals(3, b.getInt("warmth")); assertEquals(false, b.getBoolean("img"))
        assertTrue(out.getJSONObject("images").getString("a").startsWith("data:image/jpeg;base64,/9g"))
        assertEquals(false, out.getJSONObject("images").has("b"))
        assertEquals(2, out.getJSONObject("wears").getJSONArray("2026-10-01").length())
        val s = out.getJSONObject("settings")
        assertEquals("KIMI", s.getString("provider")); assertEquals("sk-1", s.getJSONObject("key").getString("KIMI"))
        assertEquals(false, s.getJSONObject("key").has("DOUBAO")); assertEquals("qwen-x", s.getJSONObject("model").getString("QWEN"))
        assertEquals("COOL", s.getString("weather")); assertEquals("通勤", s.getString("occasion"))
    }

    @Test fun nothingToMove() {
        assertNull(Legacy.toBackup(null, { null }, emptyMap<String, Any>()))
        assertNull(Legacy.toBackup("not json", { null }, mapOf("provider" to "KIMI")))
    }

    @Test fun keysAloneAreWorthMoving() {
        val out = Legacy.toBackup(null, { null }, mapOf("key_QWEN" to "sk-q"))!!
        assertEquals(0, out.getJSONArray("items").length())
        assertEquals("sk-q", out.getJSONObject("settings").getJSONObject("key").getString("QWEN"))
    }
}
