package com.felix.closet

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.random.Random

enum class Weather(val label: String, val hint: String) {
    HOT("炎热", "28° 以上"),
    WARM("温暖", "20–28°"),
    COOL("凉爽", "10–20°"),
    COLD("寒冷", "10° 以下"),
}

data class Outfit(val pieces: List<Item>, val reasons: List<String>) {
    val ids: List<String> get() = pieces.map { it.id }
}

sealed class OutfitResult {
    data class Ok(val outfit: Outfit) : OutfitResult()
    data class Missing(val message: String) : OutfitResult()
}

/**
 * Pure outfit logic, no Android dependencies so it runs in JVM unit tests.
 *
 * Every candidate piece gets a weight = recency × warmth fit × occasion fit. Hard rules decide the slots
 * (dress or top+bottom, outer by weather, shoes/bag when owned); several random draws are scored
 * (geometric mean of weights × color harmony) and the best one wins.
 */
class OutfitEngine(
    private val items: List<Item>,
    private val lastWorn: Map<String, LocalDate>,
    private val today: LocalDate,
    private val rnd: Random = Random.Default,
) {
    private val base = setOf(Cat.TOP, Cat.BOTTOM, Cat.DRESS)

    fun daysSince(id: String): Long? = lastWorn[id]?.let { ChronoUnit.DAYS.between(it, today) }

    fun recency(id: String): Double {
        val d = daysSince(id) ?: return 1.3
        return when {
            d <= 0 -> 0.03
            d == 1L -> 0.1
            d <= 3 -> 0.35
            d <= 6 -> 0.7
            else -> 1.0 + minOf(d, 60L) / 120.0
        }
    }

    fun warmthFit(it: Item, w: Weather): Double {
        val k = it.warmth.coerceIn(1, 3)
        return when {
            it.cat in base -> when (w) {
                Weather.HOT -> doubleArrayOf(1.0, 0.2, 0.0)[k - 1]
                Weather.WARM -> doubleArrayOf(1.0, 0.8, 0.05)[k - 1]
                Weather.COOL -> doubleArrayOf(0.6, 1.0, 0.5)[k - 1]
                Weather.COLD -> doubleArrayOf(0.3, 0.9, 1.0)[k - 1]
            }
            it.cat == Cat.OUTER -> when (w) {
                Weather.HOT, Weather.WARM -> 0.0
                Weather.COOL -> doubleArrayOf(1.0, 1.0, 0.3)[k - 1]
                Weather.COLD -> doubleArrayOf(0.2, 0.7, 1.0)[k - 1]
            }
            else -> 1.0
        }
    }

    fun occasionFit(it: Item, occasion: String): Double = when {
        occasion == "运动" && it.cat == Cat.DRESS -> 0.0
        it.occasions.isEmpty() || occasion in it.occasions -> 1.0
        else -> 0.15
    }

    private fun weight(it: Item, w: Weather, occasion: String, avoid: Set<String>): Double =
        recency(it.id) * warmthFit(it, w) * occasionFit(it, occasion) * (if (it.id in avoid) 0.4 else 1.0)

    private class Pool(val items: List<Item>, val weights: DoubleArray, val relaxed: Boolean)

    /** Candidates of one category. Falls back to a "make do" pool when weather/occasion rule everything out. */
    private fun pool(cat: String, w: Weather, occasion: String, avoid: Set<String>, exclude: Set<String> = emptySet()): Pool? {
        val all = items.filter { it.cat == cat && it.id !in exclude }
        if (all.isEmpty()) return null
        val ws = all.map { weight(it, w, occasion, avoid) }
        if (ws.any { it > 0 }) return Pool(all, ws.toDoubleArray(), false)
        if (occasion == "运动" && cat == Cat.DRESS) return null
        return Pool(all, all.map { recency(it.id) * 0.05 }.toDoubleArray(), true)
    }

    private fun draw(p: Pool): Pair<Item, Double>? {
        val total = p.weights.sum()
        if (total <= 0) return null
        var r = rnd.nextDouble() * total
        for (i in p.items.indices) {
            r -= p.weights[i]
            if (r <= 0 && p.weights[i] > 0) return p.items[i] to p.weights[i]
        }
        val i = p.weights.indices.last { p.weights[it] > 0 }
        return p.items[i] to p.weights[i]
    }

    fun colorScore(pieces: List<Item>): Double {
        var loud = 0
        val seen = HashSet<String>()
        for (p in pieces) {
            if (p.cat == Cat.BAG || p.cat == Cat.ACC) continue
            val c = Colors.of(p.color)
            if (c.neutral || !seen.add(c.name)) continue
            loud += if (c.name == "花色") 2 else 1
        }
        return when (loud) { 0, 1 -> 1.0; 2 -> 0.75; 3 -> 0.35; else -> 0.15 }
    }

    /** [avoid]: ids of the outfit currently on screen, so "换一套" actually changes things. */
    fun generate(w: Weather, occasion: String, avoid: Set<String> = emptySet()): OutfitResult {
        val tops = pool(Cat.TOP, w, occasion, avoid)
        val bottoms = pool(Cat.BOTTOM, w, occasion, avoid)
        val dresses = pool(Cat.DRESS, w, occasion, avoid)
        val canSep = tops != null && bottoms != null
        if (!canSep && dresses == null) {
            val miss = when {
                tops == null && bottoms == null -> "衣橱里还没有上装和下装"
                tops == null -> "衣橱里还没有上装"
                else -> "衣橱里还没有下装"
            }
            return OutfitResult.Missing("$miss，先去「衣橱」导入几件吧")
        }
        val outers = pool(Cat.OUTER, w, occasion, avoid)
        val shoes = pool(Cat.SHOES, w, occasion, avoid)
        val bags = pool(Cat.BAG, w, occasion, avoid)

        var best: List<Pair<Item, Double>>? = null
        var bestScore = -1.0
        repeat(24) {
            val pick = mutableListOf<Pair<Item, Double>>()
            val useDress = dresses != null && (!canSep || rnd.nextDouble() < 0.3)
            if (useDress) pick += draw(dresses!!) ?: return@repeat
            else { pick += draw(tops!!) ?: return@repeat; pick += draw(bottoms!!) ?: return@repeat }
            val wantOuter = when (w) { Weather.COLD -> true; Weather.COOL -> rnd.nextDouble() < 0.7; else -> false }
            if (wantOuter && outers != null && !outers.relaxed) draw(outers)?.let { pick += it }
            shoes?.let { p -> draw(p)?.let { pick += it } }
            bags?.let { p -> if (rnd.nextDouble() < 0.6) draw(p)?.let { pick += it } }
            val gm = pick.map { it.second }.fold(1.0) { a, b -> a * b }.pow(1.0 / pick.size)
            val score = gm * colorScore(pick.map { it.first })
            if (score > bestScore) { bestScore = score; best = pick }
        }
        val pieces = best?.map { it.first } ?: return OutfitResult.Missing("这次没搭出来，再点一次试试")
        return OutfitResult.Ok(Outfit(pieces, reasons(pieces, w, occasion, listOfNotNull(tops, bottoms, dresses), outers)))
    }

    /** Replace the piece at [index] with another of the same category. Null when there is nothing else. */
    fun swap(o: Outfit, index: Int, w: Weather, occasion: String): Outfit? {
        val cur = o.pieces.getOrNull(index) ?: return null
        val p = pool(cur.cat, w, occasion, emptySet(), exclude = setOf(cur.id)) ?: return null
        val (next, _) = draw(p) ?: return null
        val pieces = o.pieces.toMutableList().also { it[index] = next }
        return Outfit(pieces, reasons(pieces, w, occasion, emptyList(), null))
    }

    private fun reasons(pieces: List<Item>, w: Weather, occasion: String, basePools: List<Pool>, outers: Pool?): List<String> {
        val out = mutableListOf<String>()
        val hasOuter = pieces.any { it.cat == Cat.OUTER }
        when (w) {
            Weather.COLD -> out += if (hasOuter) "天冷，加了外套" else "天冷该加外套，但衣橱里还没有合适的外套"
            Weather.COOL -> if (hasOuter) out += "早晚凉，带件外套"
            Weather.HOT -> out += "天热，优先薄款"
            Weather.WARM -> {}
        }
        for (p in pieces) {
            if (p.cat !in base) continue
            if (warmthFit(p, w) == 0.0) out += "没有适合${w.label}天的${p.cat}，先用「${p.name}」凑合"
            else if (occasionFit(p, occasion) < 1.0) out += "「${p.name}」不太适合$occasion，衣橱里这类可选的少"
        }
        if (basePools.any { it.relaxed } && out.none { it.contains("凑合") }) out += "合适的单品不多，有几件是凑合的"
        var notes = 0
        for (p in pieces) {
            if (notes >= 2) break
            val d = daysSince(p.id)
            if (d == null) { out += "「${p.name}」还没穿过"; notes++ }
            else if (d >= 21) { out += "「${p.name}」已经 $d 天没穿了"; notes++ }
        }
        return out
    }
}
