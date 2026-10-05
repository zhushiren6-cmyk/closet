package com.felix.closet

/** One piece of clothing. [img] is a file name under filesDir/img, or null when there is no photo. */
data class Item(
    val id: String,
    val name: String,
    val cat: String,
    val color: String,
    val warmth: Int,            // 1 薄 · 2 适中 · 3 厚
    val occasions: List<String>, // empty = any occasion
    val img: String?,
    val added: Long,
)

object Cat {
    const val TOP = "上装"
    const val BOTTOM = "下装"
    const val DRESS = "连衣裙"
    const val OUTER = "外套"
    const val SHOES = "鞋子"
    const val BAG = "包"
    const val ACC = "配饰"
    val ALL = listOf(TOP, BOTTOM, DRESS, OUTER, SHOES, BAG, ACC)

    /** Maps whatever the model said to one of [ALL]. Order matters: 半身裙 before 裙, 外套 before 衣. */
    fun normalize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s in ALL) return s
        val rules = listOf(
            listOf("半身裙", "短裙", "长裙", "百褶裙", "裤", "下装") to BOTTOM,
            listOf("连衣裙", "裙") to DRESS,
            listOf("外套", "夹克", "大衣", "风衣", "羽绒", "西装", "开衫", "棉服", "冲锋衣", "马甲") to OUTER,
            listOf("鞋", "靴") to SHOES,
            listOf("包") to BAG,
            listOf("帽", "围巾", "腰带", "皮带", "项链", "耳", "手链", "戒指", "袜", "手套", "眼镜", "配饰", "饰") to ACC,
        )
        for ((keys, cat) in rules) if (keys.any { s.contains(it) }) return cat
        return TOP
    }
}

object Warmth {
    val LABELS = listOf("薄", "适中", "厚")
    fun label(w: Int) = LABELS[w.coerceIn(1, 3) - 1]
}

object Occasion {
    val ALL = listOf("日常", "通勤", "约会", "运动", "正式")
}

/** Fixed color vocabulary. Neutrals go with anything; 花色 (pattern) counts double when judging clashes. */
object Colors {
    data class C(val name: String, val hex: Int, val neutral: Boolean)

    val ALL = listOf(
        C("黑", 0xFF222222.toInt(), true),
        C("白", 0xFFF2F0EA.toInt(), true),
        C("灰", 0xFF9A9A9A.toInt(), true),
        C("米", 0xFFE6D9C0.toInt(), true),
        C("卡其", 0xFFC3A982.toInt(), true),
        C("驼", 0xFFB38B5D.toInt(), true),
        C("棕", 0xFF7A5233.toInt(), true),
        C("藏青", 0xFF22304A.toInt(), true),
        C("牛仔蓝", 0xFF5B7BA0.toInt(), true),
        C("蓝", 0xFF3F6FD0.toInt(), false),
        C("绿", 0xFF4F7D57.toInt(), false),
        C("红", 0xFFB83A3A.toInt(), false),
        C("粉", 0xFFE3A1B4.toInt(), false),
        C("黄", 0xFFE2C24A.toInt(), false),
        C("橙", 0xFFE0823A.toInt(), false),
        C("紫", 0xFF7D5BA6.toInt(), false),
        C("花色", 0xFFB0A090.toInt(), false),
    )
    val NAMES = ALL.map { it.name }

    fun of(name: String): C = ALL.firstOrNull { it.name == name } ?: ALL[2]

    fun normalize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s in NAMES) return s
        val rules = listOf(
            listOf("花", "格", "条纹", "印花", "拼色", "波点", "迷彩") to "花色",
            listOf("米", "奶", "杏") to "米",
            listOf("藏青", "深蓝", "海军", "藏蓝") to "藏青",
            listOf("牛仔", "丹宁") to "牛仔蓝",
            listOf("卡其") to "卡其",
            listOf("驼") to "驼",
            listOf("咖", "棕", "褐", "巧克力") to "棕",
            listOf("灰") to "灰",
            listOf("黑") to "黑",
            listOf("白") to "白",
            listOf("蓝") to "蓝",
            listOf("绿") to "绿",
            listOf("粉") to "粉",
            listOf("红") to "红",
            listOf("黄") to "黄",
            listOf("橙", "橘") to "橙",
            listOf("紫") to "紫",
        )
        for ((keys, c) in rules) if (keys.any { s.contains(it) }) return c
        return "灰"
    }
}
