package com.felix.closet

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup

/** View helpers so the UI can be built in code. All colors come from res/values(-night)/colors.xml. */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density
fun Context.col(id: Int): Int = getColor(id)
fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.tv(s: CharSequence, size: Float = 15f, color: Int = col(R.color.text), bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.2f)
    }

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

/** match_parent width, wrap height, with vertical margins in dp. */
fun Context.mw(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

fun Context.ww(start: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(start) }

fun weight1(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

fun oval(color: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

enum class Btn { FILLED, TONAL, TEXT, OUTLINED }

fun Context.btn(label: String, kind: Btn = Btn.FILLED, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        isAllCaps = false
        insetTop = 0
        insetBottom = 0
        minHeight = dp(46)
        cornerRadius = dp(23)
        textSize = 15f
        when (kind) {
            Btn.FILLED -> {}
            Btn.TONAL -> {
                backgroundTintList = ColorStateList.valueOf(col(R.color.accentSoft))
                setTextColor(col(R.color.accentText))
            }
            Btn.TEXT -> {
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(col(R.color.accent))
                elevation = 0f
                stateListAnimator = null
            }
            Btn.OUTLINED -> {
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(col(R.color.outline))
                setTextColor(col(R.color.text))
            }
        }
        setOnClickListener { onClick() }
    }

fun Context.card(inner: View): MaterialCardView =
    MaterialCardView(this).apply {
        radius = dpf(20)
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(col(R.color.card))
        addView(inner)
    }

/** Selectable chip (Material 3 filter style). */
fun Context.filterChip(label: String): Chip =
    Chip(this).apply {
        setChipDrawable(
            ChipDrawable.createFromAttributes(
                this@filterChip, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
            )
        )
        text = label
        isCheckable = true
    }

/** Action chip. */
fun Context.assistChip(label: String, onClick: () -> Unit): Chip =
    Chip(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

fun Context.chipGroup(chips: List<Chip>, single: Boolean = false, oneLine: Boolean = false): ChipGroup =
    ChipGroup(this).apply {
        isSingleSelection = single
        isSingleLine = oneLine
        chips.forEach { addView(it) }
    }

fun Context.hscroll(v: View): HorizontalScrollView =
    HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(v)
    }

/** Small rounded label, e.g. a relation tag. */
fun Context.pill(s: String): TextView =
    tv(s, 12f, col(R.color.accentText), bold = true).apply {
        background = rounded(col(R.color.accentSoft), dpf(10))
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }
