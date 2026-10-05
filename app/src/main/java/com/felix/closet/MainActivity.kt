package com.felix.closet

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var settings: Settings
    private lateinit var pages: List<View>
    private val navCells = mutableListOf<Pair<TextView, View>>()
    private var page = 0

    // today
    private var outfit: Outfit? = null
    private lateinit var eyebrowTv: TextView
    private lateinit var dayTv: TextView
    private lateinit var weatherTv: TextView
    private lateinit var occasionTv: TextView
    private lateinit var outfitBox: LinearLayout
    private lateinit var reasonsTv: TextView
    private lateinit var todayActions: View
    private lateinit var wearBtn: MaterialButton
    private lateinit var undoBtn: MaterialButton
    private lateinit var recordedTv: TextView
    private lateinit var swapHint: TextView

    // closet
    private lateinit var closetEyebrow: TextView
    private lateinit var tabsBox: FrameLayout
    private lateinit var sortTv: TextView
    private lateinit var emptyBox: View
    private lateinit var grid: RecyclerView
    private val adapter = ClosetAdapter()
    private var filter: String? = null
    private var idleFirst = false

    // settings
    private var shown = Provider.DOUBAO
    private var filling = false
    private lateinit var providerTv: TextView
    private lateinit var providerNote: TextView
    private lateinit var endpointLayout: TextInputLayout
    private lateinit var endpointInput: TextInputEditText
    private lateinit var keyInput: TextInputEditText
    private lateinit var modelLayout: TextInputLayout
    private lateinit var modelInput: TextInputEditText
    private lateinit var statsTv: TextView

    private var pendingManual = false
    private val pickMany = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) startImport(uris, manual = pendingManual)
    }

    private var pendingPhoto: ((Uri) -> Unit)? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val cb = pendingPhoto
        pendingPhoto = null
        if (uri != null && cb != null) cb(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        settings = Settings(this)
        shown = settings.provider
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight()
            isAppearanceLightNavigationBars = !isNight()
        }

        pages = listOf(todayPage(), closetPage(), settingsPage())
        val content = FrameLayout(this)
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        val nav = column()
        nav.addView(hairline(), hairlineParams())
        val navRow = row()
        listOf("今天", "衣橱", "设置").forEachIndexed { i, label ->
            val cell = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, dp(10))
                isClickable = true
                setOnClickListener { show(i) }
            }
            val t = tv(label, 13f, col(R.color.textSub)).apply { letterSpacing = 0.12f }
            val dot = View(this).apply { background = oval(col(R.color.accent)) }
            cell.addView(t, ww())
            cell.addView(dot, LinearLayout.LayoutParams(dp(4), dp(4)).apply { topMargin = dp(6) })
            navCells += t to dot
            navRow.addView(cell, weight1())
        }
        nav.addView(navRow)

        val root = column().apply { setBackgroundColor(col(R.color.bg)) }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(b.left, b.top, b.right, ime)
            nav.setPadding(0, 0, 0, if (ime > 0) 0 else b.bottom)
            insets
        }
        setContentView(root)
        fillModelFields()
        show(savedInstanceState?.getInt("page") ?: if (store.items().isEmpty()) 1 else 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page)
    }

    override fun onResume() {
        super.onResume()
        refreshCloset()
        refreshToday(regenerateIfStale = true)
        refreshStats()
    }

    private fun show(i: Int) {
        page = i
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navCells.forEachIndexed { idx, (t, dot) ->
            val on = idx == i
            t.setTextColor(col(if (on) R.color.text else R.color.textSub))
            t.paint.isFakeBoldText = on
            t.invalidate()
            dot.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        if (i == 0) refreshToday(regenerateIfStale = true)
    }

    private fun today(): LocalDate = LocalDate.now()

    // =============================== 今天 ===============================

    private fun todayPage(): View {
        val c = column(22, 0)
        eyebrowTv = eyebrow("")
        c.addView(eyebrowTv, mw(top = 20))
        val head = row().apply { gravity = Gravity.BOTTOM }
        dayTv = displayNum("", 64f)
        head.addView(dayTv)
        head.addView(serifTitle("今天穿什么", 24f).apply { setPadding(0, 0, 0, dp(8)) }, ww(start = 14))
        c.addView(head, mw(top = 2))

        c.addView(hairline(), hairlineParams(top = 18))
        val cond = row()
        val (wCell, wTv) = selectorCell("天气") { pickWeather() }
        val (oCell, oTv) = selectorCell("场合") { pickOccasion() }
        weatherTv = wTv; occasionTv = oTv
        cond.addView(wCell, weight1())
        cond.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, LinearLayout.LayoutParams(dp(1) / 2 + 1, MATCH_PARENT))
        cond.addView(oCell, weight1().apply { marginStart = dp(18) })
        c.addView(cond)
        c.addView(hairline(), hairlineParams())
        updateConditions()

        outfitBox = column()
        c.addView(outfitBox, mw(top = 20))

        reasonsTv = tv("", 12f, col(R.color.textSub)).apply { setLineSpacing(0f, 1.6f) }
        c.addView(reasonsTv, mw(top = 14))

        val actions = row()
        actions.addView(link("换一套") { regenerate() })
        actions.addView(View(this), weight1())
        wearBtn = pill("就穿这套") { recordWear() }
        undoBtn = pill("撤销记录", outlined = true) { recordWear() }
        actions.addView(wearBtn, LinearLayout.LayoutParams(dp(200), WRAP_CONTENT))
        actions.addView(undoBtn, LinearLayout.LayoutParams(dp(200), WRAP_CONTENT))
        todayActions = actions
        c.addView(actions, mw(top = 14))

        recordedTv = tv("", 12f, col(R.color.accent)).apply { gravity = Gravity.END }
        c.addView(recordedTv, mw(top = 10))
        swapHint = tv("点单品，可以只换那一件", 11f, col(R.color.textSub)).apply { letterSpacing = 0.06f; gravity = Gravity.CENTER }
        c.addView(swapHint, mw(top = 18, bottom = 28))

        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(c) }
    }

    private fun updateConditions() {
        val w = settings.weather
        weatherTv.text = SpannableStringBuilder("${w.label} ${w.hint}  ").append("▾", ForegroundColorSpan(col(R.color.textSub)), 0)
        occasionTv.text = SpannableStringBuilder("${settings.occasion}  ").append("▾", ForegroundColorSpan(col(R.color.textSub)), 0)
    }

    private fun pickWeather() {
        val all = Weather.values()
        showOptions("今天天气", all.map { it.label to it.hint }, all.indexOf(settings.weather)) {
            settings.weather = all[it]; updateConditions(); regenerate()
        }
    }

    private fun pickOccasion() {
        showOptions("今天的场合", Occasion.ALL.map { it to "" }, Occasion.ALL.indexOf(settings.occasion)) {
            settings.occasion = Occasion.ALL[it]; updateConditions(); regenerate()
        }
    }

    private fun engine() = OutfitEngine(store.items(), store.lastWorn(), today())

    private fun regenerate() {
        val avoid = outfit?.ids?.toSet() ?: emptySet()
        outfit = when (val r = engine().generate(settings.weather, settings.occasion, avoid)) {
            is OutfitResult.Ok -> r.outfit
            is OutfitResult.Missing -> { renderMissing(r.message); return }
        }
        renderOutfit()
    }

    /** Shows today's recorded outfit, keeps the current one, or makes a new one if items changed. */
    private fun refreshToday(regenerateIfStale: Boolean) {
        val d = today()
        eyebrowTv.text = (d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "  ·  " +
            d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).uppercase(Locale.ENGLISH)
        dayTv.text = "%02d".format(d.dayOfMonth)
        val items = store.items().associateBy { it.id }
        val cur = outfit
        val recorded = store.wornOn(d)
        if (cur == null && recorded != null) {
            val pieces = recorded.mapNotNull { items[it] }
            if (pieces.isNotEmpty()) { outfit = Outfit(pieces, emptyList()); renderOutfit(); return }
        }
        if (cur != null) {
            val fresh = cur.pieces.mapNotNull { items[it.id] }
            if (fresh.size == cur.pieces.size) { outfit = cur.copy(pieces = fresh); renderOutfit(); return }
        }
        if (regenerateIfStale) { outfit = null; regenerate() }
    }

    private fun renderMissing(msg: String) {
        outfitBox.removeAllViews()
        val c = column().apply { setPadding(0, dp(40), 0, dp(40)); gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(tv(msg, 15f).apply { gravity = Gravity.CENTER })
        c.addView(link("去导入") { show(1) }, ww().apply { topMargin = dp(10) })
        outfitBox.addView(c)
        reasonsTv.text = ""
        todayActions.visibility = View.GONE
        recordedTv.text = ""
        swapHint.visibility = View.GONE
    }

    /** Height/width of a garment tile by category, so a coat reads bigger than a pair of shoes. */
    private fun ratio(cat: String) = when (cat) {
        Cat.DRESS -> 1.45f
        Cat.OUTER -> 1.25f
        Cat.BOTTOM -> 1.0f
        Cat.TOP -> 0.8f
        Cat.BAG -> 0.62f
        else -> 0.5f
    }

    private fun renderOutfit() {
        val o = outfit ?: return
        outfitBox.removeAllViews()
        todayActions.visibility = View.VISIBLE
        swapHint.visibility = View.VISIBLE

        // Two-column masonry: biggest pieces first, each into the shorter column.
        val order = listOf(Cat.OUTER, Cat.DRESS, Cat.TOP, Cat.BOTTOM, Cat.SHOES, Cat.BAG, Cat.ACC)
        val ordered = o.pieces.withIndex().sortedBy { order.indexOf(it.value.cat) }
        val cols = listOf(column(), column())
        val h = floatArrayOf(0f, 0f)
        for ((idx, p) in ordered) {
            val k = if (h[1] < h[0] - 0.05f) 1 else 0
            h[k] += ratio(p.cat) + 0.3f
            cols[k].addView(pieceTile(p) { swapAt(idx) }, mw(bottom = 18))
        }
        val r = row().apply { gravity = Gravity.TOP }
        r.addView(cols[0], weight1())
        r.addView(cols[1], weight1().apply { marginStart = dp(12) })
        outfitBox.addView(r)

        reasonsTv.text = o.reasons.joinToString("\n") { "—  $it" }
        reasonsTv.visibility = if (o.reasons.isEmpty()) View.GONE else View.VISIBLE
        val recorded = store.wornOn(today())?.toSet()
        val isRecorded = recorded != null && recorded == o.ids.toSet()
        recordedTv.text = when {
            isRecorded -> "✓ 已记下今天穿这套"
            recorded != null -> "今天已记录过另一套，点「就穿这套」会替换"
            else -> ""
        }
        recordedTv.visibility = if (recordedTv.text.isEmpty()) View.GONE else View.VISIBLE
        wearBtn.visibility = if (isRecorded) View.GONE else View.VISIBLE
        undoBtn.visibility = if (isRecorded) View.VISIBLE else View.GONE
    }

    private fun pieceTile(p: Item, onClick: () -> Unit): View {
        val c = column().apply { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        val img = RatioFrame(this, ratio(p.cat))
        fillItemImage(img, store, p, dp(180), padFrac = 0.1f)
        c.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        c.addView(eyebrow(p.cat, 10f), mw(top = 9))
        c.addView(tv(p.name, 13f).oneLine(), mw(top = 3))
        return c
    }

    private fun swapAt(i: Int) {
        val o = outfit ?: return
        val n = engine().swap(o, i, settings.weather, settings.occasion)
        if (n == null) { toast("这一类没有别的可换了"); return }
        outfit = n
        renderOutfit()
    }

    private fun recordWear() {
        val o = outfit ?: return
        val d = today()
        if (store.wornOn(d)?.toSet() == o.ids.toSet()) {
            store.setWorn(d, null)
            toast("已撤销")
        } else {
            store.setWorn(d, o.ids)
            toast("记下了")
        }
        renderOutfit()
        refreshCloset()
        refreshStats()
    }

    // =============================== 衣橱 ===============================

    private fun closetPage(): View {
        val c = column()
        val head = row().apply { setPadding(dp(22), dp(20), dp(22), 0); gravity = Gravity.BOTTOM }
        val t = column()
        closetEyebrow = eyebrow("")
        t.addView(closetEyebrow)
        t.addView(serifTitle("衣橱", 30f), mw(top = 6))
        head.addView(t, weight1())
        head.addView(pill("＋ 导入", outlined = true, height = 38) { askImport() })
        c.addView(head)

        tabsBox = FrameLayout(this).apply { setPadding(dp(22), dp(20), dp(22), 0) }
        c.addView(tabsBox)

        sortTv = tv("", 12f, col(R.color.textSub)).apply {
            setPadding(dp(16), dp(10), dp(22), dp(10))
            isClickable = true
            setOnClickListener {
                showOptions("排序", listOf("最近添加" to "", "最久没穿" to "闲置的排前面"), if (idleFirst) 1 else 0) {
                    idleFirst = it == 1; refreshCloset()
                }
            }
        }
        c.addView(sortTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })

        val empty = column(32, 0).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), dp(70), dp(32), 0) }
        empty.addView(serifTitle("衣橱还是空的", 22f), ww())
        empty.addView(tv(
            "选几张购物订单截图，淘宝、京东、拼多多都行，或者直接拍衣服。AI 会认出每一件，分好类，裁出商品图。",
            13f, col(R.color.textSub)
        ).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.6f) }, mw(top = 14))
        empty.addView(pill("导入第一批") { askImport() }, LinearLayout.LayoutParams(dp(200), WRAP_CONTENT).apply { topMargin = dp(28) })
        emptyBox = empty
        c.addView(empty)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
            setPadding(dp(17), 0, dp(17), dp(24))
            clipToPadding = false
        }
        c.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return c
    }

    private fun refreshCloset() {
        val all = store.items()
        val last = store.lastWorn()
        closetEyebrow.text = if (all.isEmpty()) "WARDROBE" else "WARDROBE  ·  ${all.size} PIECES"
        val counts = all.groupingBy { it.cat }.eachCount()
        val cats = listOf<String?>(null) + Cat.ALL.filter { (counts[it] ?: 0) > 0 }
        if (filter != null && filter !in cats) filter = null
        val labels = cats.map { cat ->
            val n = if (cat == null) all.size else counts[cat] ?: 0
            SpannableStringBuilder(cat ?: "全部").append(" $n", RelativeSizeSpan(0.72f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tabsBox.removeAllViews()
        tabsBox.addView(tabRow(labels, cats.indexOf(filter)) { i -> filter = cats[i]; tabsBox.post { refreshCloset() } })
        sortTv.text = (if (idleFirst) "最久没穿" else "最近添加") + "  ▾"

        var list = all.filter { filter == null || it.cat == filter }
        list = if (idleFirst) list.sortedWith(compareBy<Item> { last[it.id] ?: LocalDate.MIN }.thenBy { it.added })
        else list.sortedByDescending { it.added }
        adapter.submit(list, last)
        val empty = all.isEmpty()
        emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        grid.visibility = if (empty) View.GONE else View.VISIBLE
        tabsBox.visibility = if (empty) View.GONE else View.VISIBLE
        sortTv.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun askImport() {
        val missing = settings.missing(settings.provider)
        if (missing.isEmpty()) { pendingManual = false; launchPicker(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("还不能自动识别")
            .setMessage("$missing。\n\n自动识别需要一个能看图的模型（豆包、通义千问、智谱等）。也可以先手动添加：选照片，自己框出衣服、填信息。")
            .setPositiveButton("去设置") { _, _ -> show(2) }
            .setNeutralButton("手动添加") { _, _ -> pendingManual = true; launchPicker() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchPicker() =
        pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun startImport(uris: List<Uri>, manual: Boolean) {
        val i = Intent(this, ImportActivity::class.java)
            .putParcelableArrayListExtra(ImportActivity.EXTRA_URIS, ArrayList(uris))
            .putExtra(ImportActivity.EXTRA_MANUAL, manual)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        startActivity(i)
    }

    private fun editItem(item: Item) {
        val preview = store.imgFile(item)?.let { Images.thumb(it, 400) }
        val wears = store.wearCounts()[item.id] ?: 0
        showItemEditor(
            if (wears == 0) "还没穿过" else "穿过 $wears 次", item.fields(), preview,
            extra = listOf("换照片" to { changePhoto(item) }, "删除" to { confirmDelete(item) }),
        ) { f ->
            store.update(item.copy(name = f.name, cat = f.cat, color = f.color, warmth = f.warmth, occasions = f.occasions))
            refreshCloset()
            refreshToday(regenerateIfStale = false)
        }
    }

    private fun changePhoto(item: Item) {
        pendingPhoto = { uri ->
            val bmp = runCatching { Images.decodeForImport(this, uri) }.getOrNull()
            if (bmp == null) toast("读不出这张图")
            else showCropDialog(bmp, android.graphics.Rect(0, 0, bmp.width, bmp.height), "框出这件衣服") { box ->
                val name = item.img ?: "${item.id}.jpg"
                val f = java.io.File(store.imgDir, name)
                Images.saveJpeg(Images.crop(bmp, box), f)
                Images.invalidate(f)
                store.update(item.copy(img = name))
                refreshCloset()
                refreshToday(regenerateIfStale = false)
            }
        }
        pickOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun confirmDelete(item: Item) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${item.name}」？")
            .setMessage("这件和它的照片会从衣橱里删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                store.delete(item.id)
                if (outfit?.ids?.contains(item.id) == true) outfit = null
                refreshCloset()
                refreshToday(regenerateIfStale = true)
                refreshStats()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class ClosetAdapter : RecyclerView.Adapter<ClosetAdapter.H>() {
        private var items: List<Item> = emptyList()
        private var last: Map<String, LocalDate> = emptyMap()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Item>, lastWorn: Map<String, LocalDate>) {
            items = list; last = lastWorn; notifyDataSetChanged()
        }

        inner class H(v: View, val img: FrameLayout, val name: TextView, val sub: TextView) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val c = column().apply {
                setPadding(dp(5), 0, dp(5), dp(18))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val img = RatioFrame(this@MainActivity, 1f)
            c.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val name = tv("", 12f).oneLine()
            val sub = tv("", 10f, col(R.color.textSub)).oneLine().apply { letterSpacing = 0.04f }
            c.addView(name, mw(top = 8))
            c.addView(sub, mw(top = 2))
            return H(c, img, name, sub)
        }

        override fun onBindViewHolder(h: H, pos: Int) {
            val p = items[pos]
            fillItemImage(h.img, store, p, dp(120))
            h.name.text = p.name
            val d = last[p.id]?.let { ChronoUnit.DAYS.between(it, today()) }
            h.sub.text = when {
                d == null -> "●  没穿过"
                d <= 0L -> "今天"
                else -> "$d 天前"
            }
            h.sub.setTextColor(col(if (d == null) R.color.accent else R.color.textSub))
            h.itemView.setOnClickListener { editItem(p) }
        }
    }

    // =============================== 设置 ===============================

    private fun settingsPage(): View {
        val c = column(22, 0)
        c.addView(eyebrow("SETTINGS"), mw(top = 20))
        c.addView(serifTitle("设置", 30f), mw(top = 6, bottom = 30))

        c.addView(eyebrow("识别用的模型"))
        c.addView(hairline(), hairlineParams(top = 8))
        val prov = row().apply {
            setPadding(0, dp(16), 0, dp(16))
            isClickable = true
            setOnClickListener { pickProvider() }
        }
        providerTv = tv("", 16f)
        prov.addView(providerTv, weight1())
        prov.addView(tv("▾", 14f, col(R.color.textSub)))
        c.addView(prov)
        c.addView(hairline(), hairlineParams())
        providerNote = tv("", 12f, col(R.color.textSub))
        c.addView(providerNote, mw(top = 10))
        c.addView(tv("必须是能看图的模型。DeepSeek 目前不能看图，不在列表里。", 12f, col(R.color.textSub)), mw(top = 4))

        val (eL, eI) = field("接口地址", "填到 /v1 即可，例如 https://api.example.com/v1")
        endpointLayout = eL; endpointInput = eI
        endpointInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        endpointInput.doAfterTextChanged { if (!filling) settings.setEndpoint(shown, it?.toString().orEmpty()) }
        c.addView(endpointLayout, mw(top = 18))

        val (kL, kI) = field("API Key", "只保存在这台手机上")
        keyInput = kI
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        kL.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        keyInput.doAfterTextChanged { if (!filling) settings.setApiKey(shown, it?.toString().orEmpty()) }
        c.addView(kL, mw(top = 18))

        val (mL, mI) = field("模型名")
        modelLayout = mL; modelInput = mI
        modelInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        modelInput.doAfterTextChanged { if (!filling) settings.setModel(shown, it?.toString().orEmpty()) }
        c.addView(modelLayout, mw(top = 12))

        c.addView(eyebrow("数据"), mw(top = 40))
        c.addView(hairline(), hairlineParams(top = 8))
        statsTv = tv("", 15f)
        c.addView(statsTv, mw(top = 16))
        c.addView(tv(
            "衣服照片和穿着记录只存在这台手机上，不上传。导入时，截图会发给你选的模型服务做识别。卸载 App 会清空衣橱。",
            12f, col(R.color.textSub)
        ).apply { setLineSpacing(0f, 1.6f) }, mw(top = 10, bottom = 40))
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(c) }
    }

    private fun refreshStats() {
        val all = store.items()
        val last = store.lastWorn()
        val idle = all.count { it.id !in last }
        val cutoff = today().minusDays(30)
        val stale = all.count { last[it.id]?.isBefore(cutoff) == true }
        statsTv.text = if (all.isEmpty()) "衣橱还是空的。"
        else "共 ${all.size} 件，$idle 件从没穿过，$stale 件超过 30 天没穿。"
    }

    private fun fillModelFields() {
        filling = true
        providerTv.text = shown.label
        providerNote.text = when {
            shown == Provider.CUSTOM -> "任何兼容 OpenAI 格式、能看图的接口都可以，比如中转站。"
            shown.overseas -> "海外服务，国内网络通常需要开代理。"
            else -> "国内可直连。"
        }
        val custom = shown == Provider.CUSTOM
        endpointLayout.visibility = if (custom) View.VISIBLE else View.GONE
        endpointInput.setText(settings.rawEndpoint(shown))
        keyInput.setText(settings.apiKey(shown))
        modelInput.setText(settings.rawModel(shown))
        modelLayout.helperText = if (custom) "必填，填该接口支持看图的模型名" else "留空使用默认：${shown.defaultModel}"
        filling = false
    }

    private fun pickProvider() {
        val all = Provider.values()
        showOptions("识别用的模型", all.map { it.label to (if (it.overseas) "需代理" else "") }, all.indexOf(shown)) {
            shown = all[it]
            settings.provider = shown
            fillModelFields()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
