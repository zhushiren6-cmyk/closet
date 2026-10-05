package com.felix.closet

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID

/** One recognised (or hand-boxed) item waiting to be confirmed. */
class Draft(val src: File, var box: Rect?, var f: Fields, var thumb: Bitmap?, var selected: Boolean, var note: String = "")

/**
 * Takes picked screenshots/photos, recognises each one in turn, and lets the user check, fix and re-box
 * every item before anything is saved. Nothing reaches the wardrobe until 「入库」.
 */
class ImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_MANUAL = "manual"
    }

    private inner class Section(val index: Int, val uri: Uri) {
        val view = column()
        val head = eyebrow("第 ${index + 1} 张")
        val actions = row()
        private val statusRow = row().apply { setPadding(0, dp(16), 0, dp(16)) }
        private val dot = View(this@ImportActivity)
        val status = tv("排队中…", 13f, col(R.color.textSub))
        val draftsBox = column()
        @Volatile var src: File? = null
        val drafts = mutableListOf<Draft>()
        @Volatile var busy = false
        @Volatile var done = false

        init {
            val h = row()
            h.addView(head, weight1())
            h.addView(actions)
            view.addView(h)
            view.addView(hairline(), hairlineParams(top = 2))
            statusRow.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
            statusRow.addView(status, weight1().apply { marginStart = dp(10) })
            view.addView(statusRow)
            view.addView(draftsBox)
            setStatus("排队中…")
        }

        /** null hides the status line. */
        fun setStatus(text: String?, warn: Boolean = false) {
            statusRow.visibility = if (text == null) View.GONE else View.VISIBLE
            status.text = text.orEmpty()
            val c = col(if (warn) R.color.warn else R.color.textSub)
            status.setTextColor(c)
            dot.background = oval(if (warn) c else col(R.color.accent))
        }
    }

    private lateinit var store: Store
    private lateinit var settings: Settings
    private val sections = mutableListOf<Section>()
    private lateinit var saveBtn: MaterialButton
    private lateinit var summaryTv: TextView
    private var manual = false
    private var saved = false
    private val workDir by lazy { File(cacheDir, "import").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        settings = Settings(this)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNight()
        manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
        val uris: List<Uri> = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)) ?: arrayListOf()
        if (uris.isEmpty()) { finish(); return }
        workDir.listFiles()?.forEach { it.delete() }

        val list = column(22, 0)
        list.addView(tv("←", 22f, col(R.color.textSub)).apply {
            setPadding(0, dp(14), dp(16), dp(4))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, ww())
        list.addView(serifTitle(if (manual) "手动添加" else "确认识别结果", 28f), mw(top = 12))
        list.addView(tv(
            if (manual) "每张图先当成一件衣服。点「调整框选」框出衣服，点一行填名称和类别。"
            else "逐张识别，一张大约 5–30 秒。点一行可修改名称和类别，没认出来的可以手动框。",
            13f, col(R.color.textSub)
        ), mw(top = 8, bottom = 26))
        uris.forEachIndexed { i, u ->
            val s = Section(i, u)
            sections += s
            list.addView(s.view, mw(bottom = 28))
        }

        val bar = column().apply { setBackgroundColor(col(R.color.bg)) }
        bar.addView(hairline(), hairlineParams())
        val barRow = row().apply { setPadding(dp(22), dp(14), dp(22), dp(14)) }
        summaryTv = tv("", 13f, col(R.color.textSub))
        barRow.addView(summaryTv, weight1())
        saveBtn = pill("入库") { commit() }
        barRow.addView(saveBtn, LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(barRow)

        val root = column().apply { setBackgroundColor(col(R.color.bg)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
        updateSummary()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pending = sections.sumOf { s -> s.drafts.count { it.selected } }
                if (saved || pending == 0) { finish(); return }
                MaterialAlertDialogBuilder(this@ImportActivity)
                    .setTitle("放弃这 $pending 件？")
                    .setMessage("还没入库，退出后识别结果不会保存。")
                    .setPositiveButton("放弃") { _, _ -> finish() }
                    .setNegativeButton("继续编辑", null)
                    .show()
            }
        })

        Thread { sections.forEach { process(it) } }.start()
    }

    // ---------------- work (background thread) ----------------

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }

    private fun process(s: Section) {
        s.busy = true
        ui { s.setStatus(if (manual) "读取中…" else "识别中…"); s.actions.removeAllViews() }
        try {
            val bmp = try {
                Images.decodeForImport(this, s.uri)
            } catch (e: Throwable) {
                ui { fail(s, "读不出这张图（${e.javaClass.simpleName}）", null, canRetry = false) }
                return
            }
            val srcFile = File(workDir, "src_${s.index}.jpg")
            Images.saveJpeg(bmp, srcFile, 90)
            s.src = srcFile
            if (manual) {
                val box = Rect(0, 0, bmp.width, bmp.height)
                val d = Draft(srcFile, box, Fields("新单品", Cat.TOP, "灰", 2, mutableListOf()), Images.crop(bmp, box), true)
                ui { s.drafts += d; renderSection(s, "") }
                return
            }
            val (found, note) = try {
                VisionClient(settings).recognize(Images.jpegBase64(bmp), bmp.width, bmp.height)
            } catch (e: VisionException) {
                ui { fail(s, e.message ?: "识别失败", e.raw, canRetry = true) }
                return
            } catch (e: Throwable) {
                ui { fail(s, "识别出错：${e.javaClass.simpleName} ${e.message ?: ""}", null, canRetry = true) }
                return
            }
            val drafts = found.map { det ->
                val r = det.box?.let { Rect(it[0], it[1], it[2], it[3]) }
                Draft(srcFile, r, Fields(det.name, det.cat, det.color, det.warmth, det.occasions.toMutableList()),
                    r?.let { Images.crop(bmp, it) }, true)
            }
            ui {
                markDuplicates(drafts)
                s.drafts += drafts
                renderSection(s, note)
            }
        } finally {
            s.busy = false
            s.done = true
            ui { updateSummary() }
        }
    }

    /** Same name as something already in the wardrobe or earlier in this batch: probably the same piece. */
    private fun markDuplicates(new: List<Draft>) {
        val names = store.items().map { it.name }.toMutableSet()
        sections.flatMap { it.drafts }.forEach { names += it.f.name }
        for (d in new) {
            if (d.f.name in names) { d.selected = false; d.note = "可能重复：已有同名单品，默认不入库" }
            names += d.f.name
        }
    }

    // ---------------- rendering ----------------

    private fun fail(s: Section, msg: String, raw: String?, canRetry: Boolean) {
        s.setStatus(msg, warn = true)
        s.draftsBox.removeAllViews()
        if (!raw.isNullOrBlank()) {
            s.draftsBox.addView(tv("服务返回：${raw.take(300)}", 11f, col(R.color.textSub)).apply { setTextIsSelectable(true) }, mw(bottom = 8))
        }
        s.actions.removeAllViews()
        if (canRetry) s.actions.addView(link("重试", 12f) { retry(s) })
        if (s.src != null) s.actions.addView(link("手动框一件", 12f) { addByHand(s) }, ww(start = if (canRetry) 16 else 0))
        updateSummary()
    }

    private fun retry(s: Section) {
        if (s.busy) return
        s.setStatus("识别中…")
        s.draftsBox.removeAllViews()
        s.done = false
        s.busy = true
        updateSummary()
        Thread { process(s) }.start()
    }

    private fun renderSection(s: Section, note: String) {
        s.head.text = if (manual || s.drafts.isEmpty()) "第 ${s.index + 1} 张" else "第 ${s.index + 1} 张 · 认出 ${s.drafts.size} 件"
        s.setStatus(if (s.drafts.isEmpty()) "没认出服饰" + (if (note.isNotBlank()) "：$note" else "") else null)
        s.draftsBox.removeAllViews()
        s.drafts.forEach { d ->
            s.draftsBox.addView(draftRow(s, d))
            s.draftsBox.addView(hairline(), hairlineParams())
        }
        s.actions.removeAllViews()
        if (s.src != null) s.actions.addView(link("手动框一件", 12f) { addByHand(s) })
        updateSummary()
    }

    private fun draftRow(s: Section, d: Draft): View {
        val r = row().apply {
            setPadding(0, dp(14), 0, dp(14))
            alpha = if (d.selected) 1f else 0.5f
        }
        val img = FrameLayout(this).apply { background = backdrop(); clipToOutline = true }
        val t = d.thumb
        if (t != null) img.addView(ImageView(this).apply {
            setImageBitmap(t); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        else img.addView(View(this).apply { background = colorDot(d.f.color, 22) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        r.addView(img, LinearLayout.LayoutParams(dp(64), dp(64)))

        val info = column(14, 0)
        info.addView(tv(d.f.name, 15f).apply { maxLines = 2 })
        val meta = "${d.f.cat} · ${d.f.color} · ${Warmth.label(d.f.warmth)}" +
            (if (d.f.occasions.isNotEmpty()) " · " + d.f.occasions.joinToString("/") else "")
        info.addView(tv(meta, 12f, col(R.color.textSub)), mw(top = 4))
        if (d.note.isNotEmpty()) info.addView(tv(d.note, 11f, col(R.color.warn)), mw(top = 4))
        val boxLink = if (d.box == null) link("没框到商品图，点这里框选", 12f, col(R.color.warn)) { rebox(s, d) }
        else link("调整框选", 12f) { rebox(s, d) }
        info.addView(boxLink.apply { setPadding(0, dp(4), dp(8), dp(2)) }, ww())
        r.addView(info, weight1())

        r.addView(RoundCheck(this, d.selected) { v -> d.selected = v; r.alpha = if (v) 1f else 0.5f; updateSummary() })

        r.setOnClickListener {
            showItemEditor("修改", d.f.copy(occasions = d.f.occasions.toMutableList()), d.thumb) { nf ->
                d.f = nf
                renderSection(s, "")
            }
        }
        return r
    }

    private fun rebox(s: Section, d: Draft) {
        val src = Images.loadFull(d.src) ?: run { toast("原图已失效，请重新导入"); return }
        showCropDialog(src, d.box, "框出「${d.f.name}」的图片") { box ->
            d.box = box
            d.thumb = Images.crop(src, box)
            renderSection(s, "")
        }
    }

    private fun addByHand(s: Section) {
        val f = s.src ?: return
        val src = Images.loadFull(f) ?: run { toast("原图已失效，请重新导入"); return }
        showCropDialog(src, null, "框出一件衣服") { box ->
            val d = Draft(f, box, Fields("新单品", Cat.TOP, "灰", 2, mutableListOf()), Images.crop(src, box), true)
            s.drafts += d
            renderSection(s, "")
            showItemEditor("这是什么？", d.f, d.thumb) { nf -> d.f = nf; renderSection(s, "") }
        }
    }

    private fun updateSummary() {
        val sel = sections.sumOf { s -> s.drafts.count { it.selected } }
        val running = sections.count { !it.done }
        summaryTv.text = if (running > 0) "还有 $running 张在处理" else "已选 $sel 件"
        saveBtn.text = if (sel > 0) "入库 $sel 件" else "入库"
        saveBtn.isEnabled = sel > 0
        saveBtn.alpha = if (sel > 0) 1f else 0.3f
    }

    // ---------------- commit ----------------

    private fun commit() {
        val chosen = sections.flatMap { it.drafts }.filter { it.selected }
        if (chosen.isEmpty()) return
        val now = System.currentTimeMillis()
        val items = chosen.mapIndexed { i, d ->
            val id = UUID.randomUUID().toString()
            val img = d.thumb?.let { b ->
                val name = "$id.jpg"
                Images.saveJpeg(b, File(store.imgDir, name))
                name
            }
            Item(id, d.f.name, d.f.cat, d.f.color, d.f.warmth, d.f.occasions.toList(), img, now + i)
        }
        store.add(items)
        saved = true
        toast("已入库 ${items.size} 件")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) workDir.listFiles()?.forEach { it.delete() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
