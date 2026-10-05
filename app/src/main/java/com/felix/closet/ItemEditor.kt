package com.felix.closet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** The editable part of an item, shared by import drafts and saved items. */
data class Fields(
    var name: String,
    var cat: String,
    var color: String,
    var warmth: Int,
    var occasions: MutableList<String>,
)

fun Item.fields() = Fields(name, cat, color, warmth, occasions.toMutableList())

fun Context.colorDot(name: String, sizeDp: Int = 14): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Colors.of(name).hex)
        setStroke(dp(1), col(R.color.outline))
        setSize(dp(sizeDp), dp(sizeDp))
    }

private fun Context.single(options: List<String>, selected: String, icon: ((String) -> GradientDrawable)? = null): ChipGroup {
    val chips = options.map { o ->
        filterChip(o).apply {
            isChecked = o == selected
            if (icon != null) {
                chipIcon = icon(o)
                isChipIconVisible = true
                chipIconTint = null
                isCheckedIconVisible = false
            }
        }
    }
    return chipGroup(chips, single = true).apply { isSelectionRequired = true }
}

private fun ChipGroup.checkedText(): String? =
    (0 until childCount).map { getChildAt(it) as Chip }.firstOrNull { it.isChecked }?.text?.toString()

/**
 * Edits [f] in a dialog. [extra] adds buttons above the form (e.g. 换照片 / 删除); they close the dialog first.
 * [onSave] receives the edited copy only when the user taps 保存.
 */
fun Context.showItemEditor(
    title: String,
    f: Fields,
    preview: Bitmap?,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    onSave: (Fields) -> Unit,
) {
    val c = column(22, 8)
    var dialog: AlertDialog? = null

    if (preview != null) {
        val iv = ImageView(this).apply {
            setImageBitmap(preview)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(col(R.color.bg), dpf(14))
        }
        c.addView(iv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)))
    }
    if (extra.isNotEmpty()) {
        val r = row()
        extra.forEachIndexed { i, (label, act) ->
            r.addView(btn(label, Btn.TONAL) { dialog?.dismiss(); act() }, weight1().apply { if (i > 0) marginStart = dp(8) })
        }
        c.addView(r, mw(top = 10))
    }

    val til = TextInputLayout(this).apply { hint = "名称" }
    val name = TextInputEditText(til.context).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        setText(f.name)
    }
    til.addView(name)
    c.addView(til, mw(top = 12))

    c.addView(tv("类别", 13f, col(R.color.textSub)), mw(top = 12))
    val cats = single(Cat.ALL, f.cat)
    c.addView(cats)

    c.addView(tv("颜色", 13f, col(R.color.textSub)), mw(top = 8))
    val colors = single(Colors.NAMES, f.color) { colorDot(it) }
    c.addView(colors)

    c.addView(tv("厚度", 13f, col(R.color.textSub)), mw(top = 8))
    val warm = single(Warmth.LABELS, Warmth.label(f.warmth))
    c.addView(warm)

    c.addView(tv("适合场合（不选 = 都行）", 13f, col(R.color.textSub)), mw(top = 8))
    val occ = chipGroup(Occasion.ALL.map { o -> filterChip(o).apply { isChecked = o in f.occasions } })
    c.addView(occ, mw(bottom = 8))

    dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(ScrollView(this).apply { addView(c) })
        .setNegativeButton("取消", null)
        .setPositiveButton("保存") { _, _ ->
            val out = Fields(
                name = name.text?.toString()?.trim().orEmpty().ifEmpty { f.name },
                cat = cats.checkedText() ?: f.cat,
                color = colors.checkedText() ?: f.color,
                warmth = Warmth.LABELS.indexOf(warm.checkedText()).let { if (it < 0) f.warmth else it + 1 },
                occasions = (0 until occ.childCount).map { occ.getChildAt(it) as Chip }.filter { it.isChecked }
                    .map { it.text.toString() }.toMutableList(),
            )
            onSave(out)
        }
        .show()
}
