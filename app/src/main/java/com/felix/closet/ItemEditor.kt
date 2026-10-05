package com.felix.closet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        setStroke(dp(1), col(R.color.line))
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
 * Edits [f] in a dialog. [extra] adds text actions under the photo (e.g. 换照片 / 删除); they close the dialog first.
 * [onSave] receives the edited copy only when the user taps 保存.
 */
fun Context.showItemEditor(
    title: String,
    f: Fields,
    preview: Bitmap?,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    onSave: (Fields) -> Unit,
) {
    val c = column(24, 4)
    var dialog: AlertDialog? = null

    if (preview != null) {
        val frame = FrameLayout(this).apply { background = backdrop(); clipToOutline = true }
        frame.addView(ImageView(this).apply {
            setImageBitmap(preview)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        c.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
    }
    if (extra.isNotEmpty()) {
        val r = row()
        extra.forEachIndexed { i, (label, act) ->
            val color = if (label == "删除") col(R.color.warn) else col(R.color.text)
            r.addView(link(label, 14f, color) { dialog?.dismiss(); act() }, ww(start = if (i > 0) 20 else 0))
        }
        c.addView(r, mw(top = 4))
    }

    val (til, name) = field("名称")
    name.inputType = InputType.TYPE_CLASS_TEXT
    name.setText(f.name)
    c.addView(til, mw(top = 10))

    c.addView(eyebrow("类别"), mw(top = 16, bottom = 2))
    val cats = single(Cat.ALL, f.cat)
    c.addView(cats)

    c.addView(eyebrow("颜色"), mw(top = 12, bottom = 2))
    val colors = single(Colors.NAMES, f.color) { colorDot(it) }
    c.addView(colors)

    c.addView(eyebrow("厚度"), mw(top = 12, bottom = 2))
    val warm = single(Warmth.LABELS, Warmth.label(f.warmth))
    c.addView(warm)

    c.addView(eyebrow("适合场合 · 不选 = 都行"), mw(top = 12, bottom = 2))
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
    dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(col(R.color.text))
    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(col(R.color.textSub))
}
