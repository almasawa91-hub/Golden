package com.goldenaccountant.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class Row(val cells: List<String>, val onClick: (() -> Unit)? = null)

/** قالب القوائم: بحث فوري محلي، رأس أعمدة، بيانات حقيقية من قاعدة البيانات، وزر إضافة اختياري. */
abstract class ListScreen(
    private val title: String,
    private val cols: List<String>,
    private val onAdd: ((MainActivity) -> Unit)? = null,
) : Screen {
    abstract suspend fun load(a: MainActivity): List<Row>
    open fun actions(a: MainActivity): List<Pair<String, () -> Unit>> = emptyList()

    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(a.header(title, { a.back() }, *actions(a).toTypedArray()))

        val search = a.field("بحث في $title").apply {
            singleLine = true
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = a.dp(10).toFloat()
                setColor(SURFACE)
                setStroke(a.dp(1), BORDER)
            }
        }
        val searchWrap = LinearLayout(a).apply {
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            setBackgroundColor(BG)
            addView(search, LinearLayout.LayoutParams(-1, a.dp(48)))
        }
        root.addView(searchWrap)
        root.addView(a.rowOf(*cols.toTypedArray(), header = true))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        list.addView(a.text("جاري التحميل...", 15f, LINK).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dp(90), 0, 0)
        })
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(a.footer(onAdd?.let { f -> { f(a) } }))

        a.lifecycleScope.launch {
            try {
                val rows = load(a)
                fun render(query: String) {
                    val filtered = if (query.isBlank()) rows else rows.filter { row ->
                        row.cells.any { it.contains(query, ignoreCase = true) }
                    }
                    list.removeAllViews()
                    if (filtered.isEmpty()) {
                        val message = if (query.isBlank()) "لا توجد بيانات مسجلة" else "لا توجد نتائج مطابقة للبحث"
                        list.addView(a.text(message, 15f, MUTED).apply {
                            gravity = Gravity.CENTER
                            setPadding(a.dp(12), a.dp(72), a.dp(12), a.dp(12))
                        })
                    } else {
                        filtered.forEach { row ->
                            list.addView(a.rowOf(*row.cells.toTypedArray()).apply {
                                if (row.onClick != null) {
                                    isClickable = true
                                    setBackgroundResource(android.R.drawable.list_selector_background)
                                    setOnClickListener { row.onClick.invoke() }
                                }
                            })
                        }
                    }
                }
                render("")
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        render(s?.toString().orEmpty().trim())
                    }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
            } catch (e: Exception) {
                list.removeAllViews()
                list.addView(a.text("تعذر تحميل البيانات: " + (e.message ?: "خطأ غير معروف"), 14f, android.graphics.Color.RED)
                    .apply { setPadding(a.dp(14), a.dp(30), a.dp(14), 0) })
            }
        }
        return root
    }
}
