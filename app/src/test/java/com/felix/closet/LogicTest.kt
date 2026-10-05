package com.felix.closet

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class LogicTest {

    private val today = LocalDate.of(2026, 10, 5)
    private var n = 0
    private fun item(cat: String, color: String = "黑", warmth: Int = 2, occ: List<String> = emptyList(), name: String = "$cat${n}") =
        Item("id${n++}", name, cat, color, warmth, occ, null, 0)

    private fun ok(r: OutfitResult): Outfit {
        assertTrue("expected an outfit, got $r", r is OutfitResult.Ok)
        return (r as OutfitResult.Ok).outfit
    }

    private val basic = listOf(
        item(Cat.TOP, "白", 1), item(Cat.TOP, "灰", 2), item(Cat.TOP, "黑", 3),
        item(Cat.BOTTOM, "牛仔蓝", 2), item(Cat.BOTTOM, "黑", 1), item(Cat.BOTTOM, "卡其", 3),
        item(Cat.OUTER, "驼", 3), item(Cat.OUTER, "藏青", 1),
        item(Cat.SHOES, "白"), item(Cat.BAG, "黑"),
    )

    @Test fun emptyWardrobeExplainsWhatIsMissing() {
        val r = OutfitEngine(emptyList(), emptyMap(), today).generate(Weather.WARM, "日常")
        assertTrue(r is OutfitResult.Missing)
        val r2 = OutfitEngine(listOf(item(Cat.TOP)), emptyMap(), today).generate(Weather.WARM, "日常")
        assertTrue((r2 as OutfitResult.Missing).message.contains("下装"))
    }

    @Test fun alwaysTopAndBottomOrDressAndNoDuplicates() {
        repeat(300) { seed ->
            val o = ok(OutfitEngine(basic, emptyMap(), today, Random(seed)).generate(Weather.values()[seed % 4], "日常"))
            val cats = o.pieces.map { it.cat }
            assertTrue(cats.toString(), (Cat.TOP in cats && Cat.BOTTOM in cats) || Cat.DRESS in cats)
            assertEquals(cats.size, cats.toSet().size)
            assertEquals(o.ids.size, o.ids.toSet().size)
        }
    }

    @Test fun hotNeverHasOuterColdAlwaysDoes() {
        repeat(200) { seed ->
            val hot = ok(OutfitEngine(basic, emptyMap(), today, Random(seed)).generate(Weather.HOT, "日常"))
            assertFalse(hot.pieces.any { it.cat == Cat.OUTER })
            assertTrue(hot.pieces.filter { it.cat in setOf(Cat.TOP, Cat.BOTTOM) }.all { it.warmth < 3 })
            val cold = ok(OutfitEngine(basic, emptyMap(), today, Random(seed)).generate(Weather.COLD, "日常"))
            assertTrue(cold.pieces.any { it.cat == Cat.OUTER })
        }
    }

    @Test fun recentlyWornIsRarelyPicked() {
        val tops = (0 until 3).map { item(Cat.TOP, "白", 2) }
        val items = tops + item(Cat.BOTTOM, "黑", 2)
        val worn = mapOf(tops[0].id to today.minusDays(1))
        var hits = 0
        repeat(400) { seed ->
            val o = ok(OutfitEngine(items, worn, today, Random(seed)).generate(Weather.WARM, "日常"))
            if (tops[0].id in o.ids) hits++
        }
        assertTrue("worn-yesterday top picked $hits/400", hits < 40)
    }

    @Test fun onlyDressesStillWorksAndSportExcludesDresses() {
        val items = listOf(item(Cat.DRESS, "粉", 1), item(Cat.SHOES))
        ok(OutfitEngine(items, emptyMap(), today).generate(Weather.HOT, "约会"))
        val r = OutfitEngine(items, emptyMap(), today).generate(Weather.HOT, "运动")
        assertTrue(r is OutfitResult.Missing)
    }

    @Test fun winterOnlyWardrobeInSummerMakesDoAndSaysSo() {
        val items = listOf(item(Cat.TOP, "黑", 3, name = "厚毛衣"), item(Cat.BOTTOM, "黑", 3, name = "加绒裤"))
        val o = ok(OutfitEngine(items, emptyMap(), today).generate(Weather.HOT, "日常"))
        assertEquals(2, o.pieces.size)
        assertTrue(o.reasons.toString(), o.reasons.any { it.contains("凑合") })
    }

    @Test fun swapChangesOnlyThatSlot() {
        val o = ok(OutfitEngine(basic, emptyMap(), today, Random(1)).generate(Weather.WARM, "日常"))
        val idx = o.pieces.indexOfFirst { it.cat == Cat.TOP || it.cat == Cat.DRESS }
        val s = OutfitEngine(basic, emptyMap(), today, Random(2)).swap(o, idx, Weather.WARM, "日常")
        assertNotNull(s)
        assertTrue(s!!.pieces[idx].id != o.pieces[idx].id)
        assertEquals(o.pieces.filterIndexed { i, _ -> i != idx }, s.pieces.filterIndexed { i, _ -> i != idx })
        val single = listOf(item(Cat.TOP), item(Cat.BOTTOM))
        val o2 = ok(OutfitEngine(single, emptyMap(), today).generate(Weather.WARM, "日常"))
        assertNull(OutfitEngine(single, emptyMap(), today).swap(o2, 0, Weather.WARM, "日常"))
    }

    @Test fun colorScorePenalisesClashes() {
        val e = OutfitEngine(emptyList(), emptyMap(), today)
        assertEquals(1.0, e.colorScore(listOf(item(Cat.TOP, "红"), item(Cat.BOTTOM, "黑"))), 1e-9)
        assertTrue(e.colorScore(listOf(item(Cat.TOP, "红"), item(Cat.BOTTOM, "绿"), item(Cat.SHOES, "黄"))) < 0.5)
    }

    // ---------------- parsing ----------------

    @Test fun parsesFencedJsonAndScalesBoxes() {
        val raw = """```json
            {"items":[{"name":"黑色连帽卫衣","cat":"上装","color":"黑色","warmth":2,"occasions":["日常","运动","胡说"],"box":[100,200,300,400]}]}
            ```"""
        val (items, _) = VisionParse.parse(raw, 1000, 2000)
        assertEquals(1, items.size)
        val d = items[0]
        assertEquals("黑", d.color)
        assertEquals(listOf("日常", "运动"), d.occasions)
        val b = d.box!!
        // 0–1000 relative -> pixels, plus a 3% margin.
        assertTrue(b[0] in 90..100 && b[2] in 300..310 && b[1] in 380..400 && b[3] in 800..815)
    }

    @Test fun parsesBareArrayPixelsAndFractions() {
        val (a, _) = VisionParse.parse("""结果：[{"name":"牛仔裤","box":[0.1,0.1,0.5,0.4]}]""", 800, 1000)
        assertEquals(Cat.BOTTOM, a[0].cat)
        assertEquals("牛仔蓝", a[0].color)
        assertNotNull(a[0].box)
        assertNotNull(VisionParse.box(JSONArray("[1200,1500,1900,2600]"), 2000, 4000))
        assertNull(VisionParse.box(JSONArray("[10,10,12,12]"), 1000, 1000))
        assertNull(VisionParse.box(JSONArray("[1,2,3]"), 1000, 1000))
        assertNull(VisionParse.box(null, 1000, 1000))
        // swapped corners are fine
        assertNotNull(VisionParse.box(JSONArray("[500,600,100,200]"), 1000, 1000))
    }

    @Test fun emptyResultKeepsNoteAndGarbageThrows() {
        val (a, note) = VisionParse.parse("""{"items":[],"note":"不是服饰"}""", 100, 100)
        assertTrue(a.isEmpty()); assertEquals("不是服饰", note)
        try { VisionParse.parse("我无法识别", 100, 100); assertTrue(false) } catch (e: VisionException) { }
        try { VisionParse.parse("{\"items\": [", 100, 100); assertTrue(false) } catch (e: VisionException) { }
    }

    @Test fun normalizers() {
        assertEquals(Cat.BOTTOM, Cat.normalize("百褶半身裙"))
        assertEquals(Cat.DRESS, Cat.normalize("碎花连衣裙"))
        assertEquals(Cat.OUTER, Cat.normalize("羽绒服"))
        assertEquals(Cat.SHOES, Cat.normalize("马丁靴"))
        assertEquals(Cat.TOP, Cat.normalize("???"))
        assertEquals("米", Colors.normalize("米白色"))
        assertEquals("藏青", Colors.normalize("深蓝"))
        assertEquals("花色", Colors.normalize("红白条纹"))
        assertEquals("灰", Colors.normalize(""))
    }
}
