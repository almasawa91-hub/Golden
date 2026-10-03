package com.goldenaccountant.app

import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.math.BigDecimal

class ItemsScreen : ListScreen("الأصناف", listOf("الصنف", "سعر البيع", "الباركود"), { a ->
    a.ask("صنف جديد", listOf("اسم الصنف", "سعر البيع", "الباركود"), setOf(1)) { v ->
        a.lifecycleScope.launch {
            try { a.app.accounting.addItem(v[0], v[1].num() ?: BigDecimal.ZERO, v[2]); a.refresh() }
            catch (e: Exception) { a.toast(e.message ?: "تعذر الحفظ") }
        }
    }
}) {
    override suspend fun load(a: MainActivity) = a.app.accounting.items().map { Row(listOf(it.name, fmtMicro(it.salePriceMicro), it.barcode ?: "")) }
}

class SettingsScreen : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(a.header("الإعدادات", { a.back() }))
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8)) }
        fun toggle(label: String, key: String) {
            val sw = Switch(a).apply { text = label; textSize = 15f; setPadding(0, a.dp(12), 0, a.dp(12)) }
            a.lifecycleScope.launch {
                sw.isChecked = a.app.settings.bool(key)
                sw.setOnCheckedChangeListener { _, on -> a.lifecycleScope.launch { a.app.settings.putBool(key, on) } }
            }
            body.addView(sw)
        }
        toggle("تفعيل ضريبة القيمة المضافة", "vat_on")
        toggle("الضريبة متضمنة في السعر", "vat_inclusive")
        toggle("البيع بالكمية السالبة", "allow_negative")
        body.addView(a.pill("نسبة الضريبة") {
            a.ask("نسبة الضريبة %", listOf("النسبة"), setOf(0)) { v ->
                val r = v[0].num()
                if (r == null || r.signum() < 0) a.toast("نسبة غير صحيحة") else a.lifecycleScope.launch { a.app.settings.put("vat_rate", r.toPlainString()); a.toast("تم الحفظ") }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(16) })
        root.addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
}
