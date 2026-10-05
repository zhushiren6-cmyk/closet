package com.felix.closet

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * Shows the whole image at screen width (tall screenshots scroll) with an adjustable box.
 * Drag a corner to resize, drag inside to move, tap anywhere outside to move the box there.
 * Touches outside the box are left to the parent ScrollView so the page still scrolls.
 */
@SuppressLint("ViewConstructor")
class CropView(ctx: Context, private val bmp: Bitmap, init: Rect?) : View(ctx) {

    /** In bitmap pixels. */
    val box = RectF()
    private var scale = 1f
    private val handle = ctx.dpf(28)
    private val minSide = 24f

    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.dpf(2)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private enum class Mode { NONE, MOVE, CORNER, TAP }
    private var mode = Mode.NONE
    private var corner = 0 // 0 tl, 1 tr, 2 bl, 3 br
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    init {
        if (init != null && init.width() > 0 && init.height() > 0) box.set(init)
        else {
            val s = minOf(bmp.width, bmp.height) * 0.5f
            box.set((bmp.width - s) / 2, minOf(bmp.height * 0.1f, bmp.height - s), (bmp.width + s) / 2, minOf(bmp.height * 0.1f, bmp.height - s) + s)
        }
    }

    fun boxRect(): Rect = Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())

    /** Vertical center of the box in view pixels, so the dialog can scroll to it. */
    fun boxCenterY(): Int = (box.centerY() * scale).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        scale = w.toFloat() / bmp.width
        setMeasuredDimension(w, (bmp.height * scale).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.save(); c.scale(scale, scale); c.drawBitmap(bmp, 0f, 0f, bmpPaint); c.restore()
        val l = box.left * scale; val t = box.top * scale; val r = box.right * scale; val b = box.bottom * scale
        c.drawRect(0f, 0f, width.toFloat(), t, dim)
        c.drawRect(0f, b, width.toFloat(), height.toFloat(), dim)
        c.drawRect(0f, t, l, b, dim)
        c.drawRect(r, t, width.toFloat(), b, dim)
        c.drawRect(l, t, r, b, stroke)
        val k = context.dpf(6)
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) c.drawCircle(x, y, k, knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y
                val l = box.left * scale; val t = box.top * scale; val r = box.right * scale; val b = box.bottom * scale
                val corners = listOf(l to t, r to t, l to b, r to b)
                val hit = corners.indexOfFirst { (cx, cy) -> abs(cx - x) < handle && abs(cy - y) < handle }
                mode = when {
                    hit >= 0 -> { corner = hit; Mode.CORNER }
                    x in l..r && y in t..b -> Mode.MOVE
                    else -> Mode.TAP
                }
                // Keep the page from scrolling while the box is being dragged.
                if (mode != Mode.TAP) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (x - lastX) / scale; val dy = (y - lastY) / scale
                lastX = x; lastY = y
                when (mode) {
                    Mode.MOVE -> {
                        val ox = dx.coerceIn(-box.left, bmp.width - box.right)
                        val oy = dy.coerceIn(-box.top, bmp.height - box.bottom)
                        box.offset(ox, oy)
                    }
                    Mode.CORNER -> {
                        val left = corner == 0 || corner == 2
                        val top = corner == 0 || corner == 1
                        if (left) box.left = (box.left + dx).coerceIn(0f, box.right - minSide)
                        else box.right = (box.right + dx).coerceIn(box.left + minSide, bmp.width.toFloat())
                        if (top) box.top = (box.top + dy).coerceIn(0f, box.bottom - minSide)
                        else box.bottom = (box.bottom + dy).coerceIn(box.top + minSide, bmp.height.toFloat())
                    }
                    Mode.TAP -> if (abs(x - downX) > slop || abs(y - downY) > slop) mode = Mode.NONE
                    Mode.NONE -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.TAP) {
                    val cx = x / scale; val cy = y / scale
                    val hw = box.width() / 2; val hh = box.height() / 2
                    val nx = cx.coerceIn(hw, bmp.width - hw); val ny = cy.coerceIn(hh, bmp.height - hh)
                    box.set(nx - hw, ny - hh, nx + hw, ny + hh)
                    invalidate()
                }
                mode = Mode.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> { mode = Mode.NONE; return true }
        }
        return false
    }
}

/** Full-screen box picker. [onDone] gets the box in [bmp] pixels. */
fun Context.showCropDialog(bmp: Bitmap, init: Rect?, title: String, onDone: (Rect) -> Unit) {
    val d = Dialog(this, R.style.Theme_Closet)
    val crop = CropView(this, bmp, init)
    val scroll = ScrollView(this).apply {
        setBackgroundColor(Color.BLACK)
        addView(crop)
    }
    val root = column().apply { setBackgroundColor(col(R.color.bg)) }
    val head = column(18, 12)
    head.addView(tv(title, 18f, bold = true))
    head.addView(tv("拖角调整大小，拖框移动；点图上别处可把框直接移过去。长图可以上下滑。", 13f, col(R.color.textSub)), mw(top = 4))
    root.addView(head)
    root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    val bar = row().apply { setPadding(dp(16), dp(10), dp(16), dp(12)) }
    bar.addView(btn("取消", Btn.OUTLINED) { d.dismiss() }, weight1())
    bar.addView(btn("用这个框") { onDone(crop.boxRect()); d.dismiss() }, weight1().apply { marginStart = dp(10) })
    root.addView(bar)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
    d.setContentView(root)
    d.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    d.show()
    scroll.post { scroll.scrollTo(0, (crop.boxCenterY() - scroll.height / 2).coerceAtLeast(0)) }
}
