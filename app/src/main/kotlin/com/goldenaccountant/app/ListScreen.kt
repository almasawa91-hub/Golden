package com.goldenaccountant.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class Row(val cells: List<String>, val onClick: (() -> Unit)? = null)

/** قالب القوائم: شريط أزرق، رأس أعمدة، بيانات تُحمَّل من قاعدة البيانات، وزر + اختياري. */
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
        root.addView(a.rowOf(*cols.toTypedArray(), header = true))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        list.addView(a.text("جاري التحميل...", 15f, LINK).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(90), 0, 0) })
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(a.footer(onAdd?.let { f -> { f(a) } }))
        a.lifecycleScope.launch {
            try {
                val rows = load(a)
                list.removeAllViews()
                if (rows.isEmpty()) list.addView(a.text("لا توجد نتائج...", 15f, LINK).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(90), 0, 0) })
                rows.forEach { r ->
                    list.addView(a.rowOf(*r.cells.toTypedArray()).apply { r.onClick?.let { c -> setOnClickListener { c() } } })
                }
            } catch (e: Exception) {
                list.removeAllViews()
                list.addView(a.text("تعذر تحميل البيانات: " + (e.message ?: ""), 14f, android.graphics.Color.RED).apply { setPadding(a.dp(14), a.dp(30), a.dp(14), 0) })
            }
        }
        return root
    }
}
