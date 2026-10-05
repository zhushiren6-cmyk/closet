package com.felix.closet

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var settings: Settings
    private lateinit var pages: List<View>
    private lateinit var nav: BottomNavigationView

    // today
    private var outfit: Outfit? = null
    private lateinit var outfitBox: LinearLayout
    private lateinit var reasonsTv: TextView
    private lateinit var todayActions: LinearLayout
    private lateinit var wearBtn: MaterialButton
    private lateinit var recordedTv: TextView
    private lateinit var dateTv: TextView

    // closet
    private lateinit var countTv: TextView
    private lateinit var filterGroup: ChipGroup
    private lateinit var emptyTv: TextView
    private lateinit var grid: RecyclerView
    private val adapter = ClosetAdapter()
    private var filter: String? = null
    private var idleFirst = false

    // settings
    private var shown = Provider.DOUBAO
    private var filling = false
    private lateinit var providerBtn: MaterialButton
    private lateinit var providerNote: TextView
    private lateinit var endpointLayout: TextInputLayout
    private lateinit var endpointInput: TextInputEditText
    private lateinit var keyInput: TextInputEditText
    private lateinit var modelLayout: TextInputLayout
    private lateinit var modelInput: TextInputEditText
    private lateinit var statsTv: TextView

    private val pickMany = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) startImport(uris, manual = pendingManual)
    }
    private var pendingManual = false

    private val pickOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val cb = pendingPhoto
        pendingPhoto = null
        if (uri != null && cb != null) cb(uri)
    }
    private var pendingPhoto: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        settings = Settings(this)
        shown = settings.provider
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNight()

        pages = listOf(todayPage(), closetPage(), settingsPage())
        val content = FrameLayout(this)
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        nav = BottomNavigationView(this).apply {
            setBackgroundColor(col(R.color.card))
            menu.add(Menu.NONE, 0, 0, "今天").setIcon(R.drawable.ic_nav_today)
            menu.add(Menu.NONE, 1, 1, "衣橱").setIcon(R.drawable.ic_nav_closet)
            menu.add(Menu.NONE, 2, 2, "设置").setIcon(R.drawable.ic_nav_settings)
            setOnItemSelectedListener { show(it.itemId); true }
        }
        val root = column().apply { setBackgroundColor(col(R.color.bg)) }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The bottom bar pads itself for the navigation bar; only the keyboard needs room here.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(b.left, b.top, b.right, ime)
            insets
        }
        setContentView(root)
        fillModelFields()
        val start = savedInstanceState?.getInt("page") ?: if (store.items().isEmpty()) 1 else 0
        nav.selectedItemId = start
        show(start)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", nav.selectedItemId)
    }

    override fun onResume() {
        super.onResume()
        refreshCloset()
        refreshToday(regenerateIfStale = true)
        refreshStats()
    }

    private fun show(i: Int) {
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        if (i == 0) refreshToday(regenerateIfStale = true)
    }

    private fun today(): LocalDate = LocalDate.now()

    // =============================== 今天 ===============================

    private fun todayPage(): View {
        val c = column(18, 12)
        c.addView(tv("今天穿什么", 28f, bold = true), mw(top = 12))
        dateTv = tv("", 14f, col(R.color.textSub))
        c.addView(dateTv, mw(top = 2, bottom = 16))

        val cond = column(18, 14)
        cond.addView(tv("天气", 13f, col(R.color.textSub)))
        val wChips = Weather.values().map { w ->
            filterChip("${w.label} ${w.hint}").apply { isChecked = w == settings.weather; tag = w }
        }
        cond.addView(hscroll(chipGroup(wChips, single = true, oneLine = true).apply {
            isSelectionRequired = true
            setOnCheckedStateChangeListener { g, ids ->
                val w = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? Weather } ?: return@setOnCheckedStateChangeListener
                if (w != settings.weather) { settings.weather = w; regenerate() }
            }
        }))
        cond.addView(tv("场合", 13f, col(R.color.textSub)), mw(top = 8))
        val oChips = Occasion.ALL.map { o -> filterChip(o).apply { isChecked = o == settings.occasion } }
        cond.addView(hscroll(chipGroup(oChips, single = true, oneLine = true).apply {
            isSelectionRequired = true
            setOnCheckedStateChangeListener { g, ids ->
                val o = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.text?.toString() } ?: return@setOnCheckedStateChangeListener
                if (o != settings.occasion) { settings.occasion = o; regenerate() }
            }
        }))
        c.addView(card(cond), mw(bottom = 14))

        outfitBox = column()
        c.addView(outfitBox)
        reasonsTv = tv("", 13f, col(R.color.textSub))
        c.addView(reasonsTv, mw(top = 10))

        todayActions = row()
        todayActions.addView(btn("换一套", Btn.OUTLINED) { regenerate() }, weight1())
        wearBtn = btn("就穿这套") { recordWear() }
        todayActions.addView(wearBtn, weight1().apply { marginStart = dp(10) })
        c.addView(todayActions, mw(top = 14))

        recordedTv = tv("", 13f, col(R.color.ok)).apply { gravity = Gravity.CENTER }
        c.addView(recordedTv, mw(top = 10))
        c.addView(tv("点单品可以只换那一件。", 12f, col(R.color.textSub)).apply { gravity = Gravity.CENTER }, mw(top = 6, bottom = 24))

        return ScrollView(this).apply { addView(c) }
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
        dateTv.text = today().let { "${it.monthValue} 月 ${it.dayOfMonth} 日 · ${"一二三四五六日"[it.dayOfWeek.value - 1].let { d -> "周$d" }}" }
        val items = store.items().associateBy { it.id }
        val cur = outfit
        val recorded = store.wornOn(today())
        if (cur == null && recorded != null) {
            val pieces = recorded.mapNotNull { items[it] }
            if (pieces.isNotEmpty()) { outfit = Outfit(pieces, emptyList()); renderOutfit(); return }
        }
        // Refresh piece data (names/photos may have been edited) and drop deleted pieces.
        if (cur != null) {
            val fresh = cur.pieces.mapNotNull { items[it.id] }
            if (fresh.size == cur.pieces.size) { outfit = cur.copy(pieces = fresh); renderOutfit(); return }
        }
        if (regenerateIfStale) { outfit = null; regenerate() }
    }

    private fun renderMissing(msg: String) {
        outfitBox.removeAllViews()
        val c = column(20, 24)
        c.addView(tv(msg, 15f).apply { gravity = Gravity.CENTER })
        c.addView(btn("去导入") { nav.selectedItemId = 1 }, mw(top = 14))
        outfitBox.addView(card(c))
        reasonsTv.text = ""
        todayActions.visibility = View.GONE
        recordedTv.text = ""
    }

    private fun renderOutfit() {
        val o = outfit ?: return
        outfitBox.removeAllViews()
        todayActions.visibility = View.VISIBLE
        var r: LinearLayout? = null
        o.pieces.forEachIndexed { i, p ->
            if (i % 2 == 0) { r = row().also { outfitBox.addView(it, mw(bottom = 10)) } }
            val tile = pieceTile(p) { swapAt(i) }
            r!!.addView(tile, weight1().apply { if (i % 2 == 1) marginStart = dp(10) })
        }
        if (o.pieces.size % 2 == 1) r?.addView(View(this), weight1().apply { marginStart = dp(10) })
        reasonsTv.text = o.reasons.joinToString("\n") { "· $it" }
        val recorded = store.wornOn(today())?.toSet()
        val isRecorded = recorded != null && recorded == o.ids.toSet()
        recordedTv.text = when {
            isRecorded -> "✓ 已记下今天穿这套"
            recorded != null -> "今天已记录过另一套，点「就穿这套」会替换"
            else -> ""
        }
        wearBtn.text = if (isRecorded) "撤销记录" else "就穿这套"
    }

    private fun pieceTile(p: Item, onClick: () -> Unit): View {
        val c = column()
        val img = FrameLayout(this)
        fillImage(img, p, 170)
        c.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(170)))
        val t = column(12, 8)
        t.addView(tv(p.cat, 11f, col(R.color.textSub)))
        t.addView(tv(p.name, 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        c.addView(t)
        return card(c).apply {
            radius = dpf(16)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
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
        val head = row().apply { setPadding(dp(18), dp(24), dp(18), dp(4)) }
        val t = column()
        t.addView(tv("衣橱", 28f, bold = true))
        countTv = tv("", 14f, col(R.color.textSub))
        t.addView(countTv)
        head.addView(t, weight1())
        head.addView(btn("＋ 导入") { askImport() }.apply { setPadding(dp(18), 0, dp(18), 0) })
        c.addView(head)

        filterGroup = chipGroup(emptyList(), single = true, oneLine = true).apply { isSelectionRequired = true }
        c.addView(hscroll(filterGroup).apply { setPadding(dp(14), dp(8), dp(14), 0); clipToPadding = false })

        val sort = chipGroup(listOf(
            filterChip("最近添加").apply { isChecked = true },
            filterChip("最久没穿"),
        ), single = true, oneLine = true).apply {
            isSelectionRequired = true
            setOnCheckedStateChangeListener { g, ids ->
                idleFirst = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.text?.toString() == "最久没穿" } ?: false
                refreshCloset()
            }
        }
        c.addView(sort, mw().apply { marginStart = dp(18) })

        emptyTv = tv(
            "还没有衣服。\n\n点右上角「导入」，选几张购物订单截图（淘宝、京东、拼多多都行）或衣服照片，AI 会认出每一件、自动分类并裁出商品图。",
            15f, col(R.color.textSub)
        ).apply { setPadding(dp(24), dp(40), dp(24), dp(24)) }
        c.addView(emptyTv)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
            setPadding(dp(12), dp(4), dp(12), dp(16))
            clipToPadding = false
        }
        c.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return c
    }

    private fun refreshCloset() {
        val all = store.items()
        val last = store.lastWorn()
        countTv.text = if (all.isEmpty()) "空的" else "${all.size} 件"
        // Rebuild category chips with counts, keeping the selection.
        filterGroup.setOnCheckedStateChangeListener(null)
        filterGroup.removeAllViews()
        val counts = all.groupingBy { it.cat }.eachCount()
        val labels = listOf<String?>(null) + Cat.ALL.filter { (counts[it] ?: 0) > 0 }
        if (filter != null && filter !in labels) filter = null
        for (l in labels) {
            val chip = filterChip(if (l == null) "全部 ${all.size}" else "$l ${counts[l]}").apply { tag = l ?: ""; isChecked = l == filter }
            filterGroup.addView(chip)
        }
        filterGroup.setOnCheckedStateChangeListener { g, ids ->
            filter = ids.firstOrNull()?.let { (g.findViewById<Chip>(it)?.tag as? String)?.ifEmpty { null } }
            // Rebuilding the chips from inside their own callback is asking for trouble; do it next frame.
            g.post { refreshCloset() }
        }
        var list = all.filter { filter == null || it.cat == filter }
        list = if (idleFirst) list.sortedWith(compareBy<Item> { last[it.id] ?: LocalDate.MIN }.thenBy { it.added })
        else list.sortedByDescending { it.added }
        adapter.submit(list, last)
        emptyTv.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun askImport() {
        val missing = settings.missing(settings.provider)
        if (missing.isEmpty()) { pendingManual = false; launchPicker(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("还不能自动识别")
            .setMessage("$missing。\n\n自动识别需要一个能看图的模型（豆包、通义千问、智谱等）。也可以先手动添加：选照片，自己框出衣服、填信息。")
            .setPositiveButton("去设置") { _, _ -> nav.selectedItemId = 2 }
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
        i.clipData = android.content.ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        startActivity(i)
    }

    private fun editItem(item: Item) {
        val preview = store.imgFile(item)?.let { Images.thumb(it, 400) }
        val wears = store.wearCounts()[item.id] ?: 0
        showItemEditor(
            "编辑 · 穿过 $wears 次", item.fields(), preview,
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

    /** Photo if there is one, otherwise a block of the item's color with its name. */
    private fun fillImage(frame: FrameLayout, p: Item, sizeDp: Int) {
        frame.removeAllViews()
        val bmp: Bitmap? = store.imgFile(p)?.let { Images.thumb(it, dp(sizeDp)) }
        if (bmp != null) {
            frame.setBackgroundColor(col(R.color.card))
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            val c = Colors.of(p.color)
            frame.setBackgroundColor(c.hex)
            val dark = android.graphics.Color.luminance(c.hex) < 0.45f
            frame.addView(tv(p.name, 13f, if (dark) 0xFFFFFFFF.toInt() else 0xFF1F1B17.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
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
            val img = SquareFrame(this@MainActivity)
            val inner = column()
            inner.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val t = column(8, 6)
            val name = tv("", 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val sub = tv("", 11f, col(R.color.textSub))
            t.addView(name); t.addView(sub)
            inner.addView(t)
            val cardV = card(inner).apply { radius = dpf(14); isClickable = true; isFocusable = true }
            val wrap = FrameLayout(this@MainActivity).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
            wrap.addView(cardV)
            wrap.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            return H(wrap, img, name, sub)
        }

        override fun onBindViewHolder(h: H, pos: Int) {
            val p = items[pos]
            fillImage(h.img, p, 120)
            h.name.text = p.name
            val d = last[p.id]?.let { ChronoUnit.DAYS.between(it, today()) }
            h.sub.text = when {
                d == null -> "没穿过"
                d <= 0L -> "今天穿"
                else -> "$d 天前穿过"
            }
            ((h.itemView as FrameLayout).getChildAt(0)).setOnClickListener { editItem(p) }
        }
    }

    // =============================== 设置 ===============================

    private fun settingsPage(): View {
        val c = column(18, 12)
        c.addView(tv("设置", 28f, bold = true), mw(top = 12, bottom = 16))

        val m = column(20, 18)
        m.addView(tv("识别用的模型", 16f, bold = true))
        m.addView(tv("必须是能看图的模型。DeepSeek 目前不能看图，不在列表里。", 13f, col(R.color.textSub)), mw(top = 4))
        providerBtn = btn("", Btn.OUTLINED) { pickProvider() }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        m.addView(providerBtn, mw(top = 10))
        providerNote = tv("", 12f, col(R.color.textSub))
        m.addView(providerNote, mw(top = 4))

        endpointLayout = TextInputLayout(this).apply { hint = "接口地址"; helperText = "填到 /v1 即可，例如 https://api.example.com/v1" }
        endpointInput = TextInputEditText(endpointLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        endpointLayout.addView(endpointInput)
        endpointInput.doAfterTextChanged { if (!filling) settings.setEndpoint(shown, it?.toString().orEmpty()) }
        m.addView(endpointLayout, mw(top = 10))

        val keyLayout = TextInputLayout(this).apply { hint = "API Key"; helperText = "只保存在这台手机上" }
        keyInput = TextInputEditText(keyLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyLayout.addView(keyInput)
        keyLayout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        keyInput.doAfterTextChanged { if (!filling) settings.setApiKey(shown, it?.toString().orEmpty()) }
        m.addView(keyLayout, mw(top = 12))

        modelLayout = TextInputLayout(this).apply { hint = "模型名" }
        modelInput = TextInputEditText(modelLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        modelLayout.addView(modelInput)
        modelInput.doAfterTextChanged { if (!filling) settings.setModel(shown, it?.toString().orEmpty()) }
        m.addView(modelLayout, mw(top = 8))
        c.addView(card(m), mw(bottom = 14))

        val d = column(20, 18)
        d.addView(tv("数据", 16f, bold = true))
        statsTv = tv("", 13f, col(R.color.textSub))
        d.addView(statsTv, mw(top = 6))
        d.addView(tv(
            "衣服照片和穿着记录只存在这台手机上，不上传。导入时，截图会发给你选的模型服务做识别。卸载 App 会清空衣橱。",
            13f, col(R.color.textSub)
        ), mw(top = 8))
        c.addView(card(d), mw(bottom = 24))
        return ScrollView(this).apply { addView(c) }
    }

    private fun refreshStats() {
        val all = store.items()
        val last = store.lastWorn()
        val idle = all.count { it.id !in last }
        val cutoff = today().minusDays(30)
        val stale = all.count { last[it.id]?.isBefore(cutoff) == true }
        statsTv.text = if (all.isEmpty()) "衣橱还是空的。"
        else "共 ${all.size} 件，其中 $idle 件从没穿过，$stale 件超过 30 天没穿。"
    }

    private fun fillModelFields() {
        filling = true
        providerBtn.text = shown.label + "  ▾"
        providerNote.text = when {
            shown == Provider.CUSTOM -> "任何兼容 OpenAI 格式、能看图的接口都可以，比如中转站"
            shown.overseas -> "海外服务，国内网络通常需要开代理"
            else -> "国内可直连"
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
        MaterialAlertDialogBuilder(this)
            .setTitle("选择模型服务")
            .setSingleChoiceItems(all.map { it.menuLabel }.toTypedArray(), all.indexOf(shown)) { dlg, which ->
                dlg.dismiss()
                shown = all[which]
                settings.provider = shown
                fillModelFields()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Height follows width. */
class SquareFrame(ctx: android.content.Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
