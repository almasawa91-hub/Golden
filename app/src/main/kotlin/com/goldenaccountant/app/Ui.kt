package com.goldenaccountant.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

val B1 = Color.parseColor("#0B4F8C")
val B2 = Color.parseColor("#1479B8")
val LINK = Color.parseColor("#5B78C6")
val LINE = Color.parseColor("#CFD8E0")

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.gradient(): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(B1, B2))

fun Context.text(s: String, size: Float = 15f, color: Int = Color.parseColor("#1D2730"), bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** شريط العنوان الأزرق المتدرج: رجوع في اليسار وعنوان وسطي وأزرار إجراءات. */
fun Context.header(title: String, onBack: (() -> Unit)?, vararg actions: Pair<String, () -> Unit>): LinearLayout =
    LinearLayout(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = gradient()
        minimumHeight = dp(52)
        fun icon(label: String, click: () -> Unit) = text(label, 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { click() }
        }
        if (onBack != null) addView(icon("←", onBack)) else addView(View(context), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(text(title, 16f, Color.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.forEach { (label, click) -> addView(icon(label, click)) }
    }

/** الشريط السفلي مع زر + الدائري الأبيض. */
fun Context.footer(onAdd: (() -> Unit)?, label: String = ""): LinearLayout =
    LinearLayout(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER
        background = gradient()
        minimumHeight = dp(52)
        if (label.isNotEmpty()) addView(text(label, 13f, Color.WHITE))
        if (onAdd != null) addView(text("+", 28f, B1, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(12) }
            setOnClickListener { onAdd() }
        })
    }

fun Context.pill(label: String, onClick: () -> Unit): TextView =
    text(label, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(B2) }
        setOnClickListener { onClick() }
    }

fun Context.rowOf(vararg cols: String, header: Boolean = false): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10), dp(if (header) 8 else 12), dp(10), dp(if (header) 8 else 12))
        if (header) setBackgroundColor(Color.parseColor("#E8EEF3"))
        cols.forEach { c ->
            addView(text(c, if (header) 13f else 14f, if (header) B1 else Color.parseColor("#1D2730")).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

fun Context.field(hint: String, number: Boolean = false): EditText =
    EditText(this).apply {
        this.hint = hint
        textSize = 15f
        if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** يقبل الأرقام العربية والفاصلة العشرية العربية. */
fun String.num(): java.math.BigDecimal? =
    map { if (it in (0x0660.toChar())..(0x0669.toChar())) (0x30 + (it - 0x0660.toChar())).toChar() else it }
        .joinToString("").replace("٫", ".").trim().toBigDecimalOrNull()

fun fmt(v: java.math.BigDecimal): String = String.format(java.util.Locale.US, "%,.2f", v)
fun fmtMicro(m: Long): String = fmt(java.math.BigDecimal.valueOf(m, 4))

fun MainActivity.ask(title: String, hints: List<String>, numeric: Set<Int> = emptySet(), onOk: (List<String>) -> Unit) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
    val fields = hints.mapIndexed { i, h -> field(h, i in numeric).also { box.addView(it) } }
    androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("موافق") { _, _ -> onOk(fields.map { it.text.toString() }) }
        .setNegativeButton("إلغاء", null).show()
}

fun MainActivity.choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
    androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
        .setItems(options.toTypedArray()) { _, i -> onPick(i) }.show()
}

fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
