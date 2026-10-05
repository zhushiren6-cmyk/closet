package com.felix.closet

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The design system, in code. Warm white page, one grey-beige backdrop behind every garment, hairlines
 * instead of cards, a serif for page titles, near-black for the one primary action, sage only for state.
 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density
fun Context.col(id: Int): Int = getColor(id)
fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

object Fonts {
    private var serif: Typeface? = null
    private var display: Typeface? = null
    /** Subset of Noto Serif SC: only the characters of fixed page titles. Other glyphs fall back to the system font. */
    fun serif(c: Context): Typeface =
        serif ?: (runCatching { Typeface.createFromAsset(c.assets, "fonts/serif_sc.ttf") }.getOrNull() ?: Typeface.SERIF).also { serif = it }
    /** Cormorant Garamond digits, for the big date. */
    fun display(c: Context): Typeface =
        display ?: (runCatching { Typeface.createFromAsset(c.assets, "fonts/display.ttf") }.getOrNull() ?: Typeface.SERIF).also { display = it }
}

fun Context.tv(s: CharSequence, size: Float = 15f, color: Int = col(R.color.text), bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.25f)
        includeFontPadding = false
    }

fun Context.serifTitle(s: String, size: Float = 30f): TextView =
    tv(s, size).apply { typeface = Fonts.serif(this@serifTitle); letterSpacing = 0.02f }

fun Context.displayNum(s: String, size: Float): TextView =
    tv(s, size).apply { typeface = Fonts.display(this@displayNum); setLineSpacing(0f, 1f) }

/** Small spaced caps label. */
fun Context.eyebrow(s: String, size: Float = 11f): TextView =
    tv(s, size, col(R.color.textSub)).apply { letterSpacing = 0.2f }

fun TextView.oneLine(): TextView = apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

fun Context.hairline(): View = View(this).apply { setBackgroundColor(col(R.color.line)) }
fun Context.hairlineParams(top: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, dp(1) / 2 + 1)).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

fun Context.column(padH: Int = 0, padV: Int = padH): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

fun Context.row(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

fun Context.mw(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

fun Context.ww(start: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(start) }

fun weight1(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun oval(color: Int): GradientDrawable =
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

/** The one primary action: near-black capsule. [outlined] gives the quiet secondary version. */
fun Context.pill(label: String, outlined: Boolean = false, height: Int = 48, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        isAllCaps = false
        insetTop = 0; insetBottom = 0
        minHeight = dp(height); minimumHeight = dp(height)
        cornerRadius = dp(height / 2)
        textSize = if (height >= 44) 15f else 13f
        letterSpacing = 0.08f
        elevation = 0f
        stateListAnimator = null
        if (outlined) {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(col(R.color.ink))
            setTextColor(col(R.color.ink))
            rippleColor = ColorStateList.valueOf(col(R.color.line))
        } else {
            backgroundTintList = ColorStateList.valueOf(col(R.color.ink))
            setTextColor(col(R.color.onInk))
        }
        setPadding(dp(20), 0, dp(20), 0)
        setOnClickListener { onClick() }
    }

/** Underlined text action. */
fun Context.link(label: String, size: Float = 14f, color: Int = col(R.color.text), onClick: () -> Unit): TextView =
    tv(label, size, color).apply {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        letterSpacing = 0.04f
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** Grey-beige backdrop every garment sits on. */
fun Context.backdrop(): GradientDrawable = rounded(col(R.color.tile), dpf(4))

/** Height = width × ratio. */
@SuppressLint("ViewConstructor")
class RatioFrame(ctx: Context, private val ratio: Float) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w * ratio).toInt(), MeasureSpec.EXACTLY))
    }
}

/**
 * Puts the item's photo on the backdrop, whole and centred (never cropped), or a swatch of its color.
 * [padFrac] is the breathing room around the garment.
 */
fun Context.fillItemImage(frame: FrameLayout, store: Store, item: Item, targetPx: Int, padFrac: Float = 0.12f) {
    frame.removeAllViews()
    frame.background = backdrop()
    frame.clipToOutline = true
    val bmp = store.imgFile(item)?.let { Images.thumb(it, targetPx) }
    if (bmp != null) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
        frame.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.post {
            val p = (minOf(frame.width, frame.height) * padFrac).toInt()
            iv.setPadding(p, p, p, p)
        }
    } else {
        val sw = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Colors.of(item.color).hex)
                setStroke(dp(1), col(R.color.line))
            }
        }
        frame.addView(sw, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
    }
}

/** Round check: sage filled when on, hairline ring when off. */
@SuppressLint("ViewConstructor")
class RoundCheck(ctx: Context, on: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = on
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.col(R.color.accent) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1) * 1.5f; color = ctx.col(R.color.textSub)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2); color = ctx.col(R.color.onInk)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    init {
        isClickable = true
        setOnClickListener { checked = !checked; onChange(checked) }
    }
    override fun onMeasure(w: Int, h: Int) { val s = context.dp(44); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = context.dpf(11)
        if (checked) {
            c.drawCircle(cx, cy, r, fill)
            val u = r / 11f
            c.drawLine(cx - 4.5f * u, cy + 0.2f * u, cx - 1.2f * u, cy + 3.5f * u, tick)
            c.drawLine(cx - 1.2f * u, cy + 3.5f * u, cx + 5f * u, cy - 3.5f * u, tick)
        } else c.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
    }
}

/** Text tabs: selected is ink with an underline, the rest are grey. Wrapped for horizontal scroll, with a hairline under. */
fun Context.tabRow(labels: List<CharSequence>, selected: Int, onSelect: (Int) -> Unit): View {
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    labels.forEachIndexed { i, l ->
        val on = i == selected
        val cell = column()
        cell.addView(tv(l, 14f, col(if (on) R.color.text else R.color.textSub), bold = on).apply { setPadding(0, dp(6), 0, dp(8)) })
        cell.addView(View(this).apply { setBackgroundColor(if (on) col(R.color.ink) else Color.TRANSPARENT) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1) * 3 / 2 + 1))
        cell.setOnClickListener { if (!on) onSelect(i) }
        r.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(22) })
    }
    val box = column()
    box.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(r)
    })
    box.addView(hairline(), hairlineParams())
    return box
}

/** "label / value ▾" cell that opens a picker. Returns the cell and the value view. */
fun Context.selectorCell(label: String, onClick: () -> Unit): Pair<View, TextView> {
    val c = column().apply { setPadding(0, dp(12), 0, dp(12)); isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
    c.addView(eyebrow(label, 10f))
    val v = tv("", 15f).oneLine()
    c.addView(v, mw(top = 4))
    return c to v
}

/** Bottom sheet list. Each option is (title, note). The selected one carries a sage dot. */
fun Context.showOptions(title: String, options: List<Pair<String, String>>, selected: Int, onPick: (Int) -> Unit) {
    val d = BottomSheetDialog(this)
    val c = column(24, 8)
    c.addView(eyebrow(title), mw(top = 10, bottom = 4))
    options.forEachIndexed { i, (t, note) ->
        val r = row().apply { setPadding(0, dp(14), 0, dp(14)); isClickable = true }
        r.addView(tv(t, 16f, bold = i == selected))
        if (note.isNotEmpty()) r.addView(tv(note, 13f, col(R.color.textSub)), ww(start = 10))
        r.addView(View(this), weight1())
        if (i == selected) r.addView(View(this).apply { background = oval(col(R.color.accent)) }, LinearLayout.LayoutParams(dp(7), dp(7)))
        r.setOnClickListener { d.dismiss(); if (i != selected) onPick(i) }
        c.addView(r)
        if (i < options.size - 1) c.addView(hairline(), hairlineParams())
    }
    c.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
    d.setContentView(c)
    d.show()
}

/** Quiet outlined text field. */
fun Context.field(hint: String, helper: String? = null): Pair<TextInputLayout, TextInputEditText> {
    val til = TextInputLayout(this).apply {
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        val r = dpf(6)
        setBoxCornerRadii(r, r, r, r)
        boxStrokeColor = col(R.color.ink)
        this.hint = hint
        if (helper != null) helperText = helper
    }
    val et = TextInputEditText(til.context)
    til.addView(et)
    return til to et
}

// Chips are only used inside the item editor.

fun Context.filterChip(label: String): Chip =
    Chip(this).apply {
        setChipDrawable(
            ChipDrawable.createFromAttributes(this@filterChip, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
        )
        text = label
        isCheckable = true
        chipStrokeColor = ColorStateList.valueOf(col(R.color.line))
        chipCornerRadius = dpf(16)
    }

fun Context.chipGroup(chips: List<Chip>, single: Boolean = false): ChipGroup =
    ChipGroup(this).apply {
        isSingleSelection = single
        chipSpacingVertical = dp(2)
        chips.forEach { addView(it) }
    }
