package com.goldenaccountant.app

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WarehousesScreen : ListScreen("إدارة المستودعات", listOf("المستودع", "رقم المستودع")) {
    override fun actions(a: MainActivity): List<Pair<String, () -> Unit>> = listOf(
        "+" to {
            a.ask("إضافة مستودع", listOf("اسم المستودع")) { values ->
                val name = values.firstOrNull()?.trim().orEmpty()
                if (name.isBlank()) return@ask a.toast("اسم المستودع مطلوب")
                a.lifecycleScope.launch {
                    try {
                        require(a.app.db.warehouses().byName(name) == null) { "اسم المستودع موجود مسبقاً" }
                        val id = a.app.db.warehouses().insert(com.goldenaccountant.data.WarehouseE(name = name))
                        require(id > 0) { "تعذر إنشاء المستودع" }
                        a.app.db.logAudit("CREATE", "warehouse", id, "إضافة مستودع: $name")
                        a.refresh()
                    } catch (e: Exception) { a.toast(e.message ?: "تعذر إضافة المستودع") }
                }
            }
        },
        "⇄" to { a.push(StockTransferScreen()) },
    )

    override suspend fun load(a: MainActivity): List<Row> =
        a.app.db.warehouses().all().map { Row(listOf(it.name, it.id.toString())) }
}

class StockTransferScreen : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(a.header("تحويل بين المستودعات", { a.back() }))
        val item = a.field("اسم الصنف المسجل")
        val from = a.field("مستودع المصدر (الاسم)")
        val to = a.field("مستودع الوجهة (الاسم)")
        val quantity = a.field("الكمية المحوّلة", true)
        val date = a.field("التاريخ").apply { setText(today()) }
        val memo = a.field("ملاحظة التحويل")
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(16)) }
        form.addView(a.text("ينشئ التحويل سجل تحويل وحركتي مخزون متقابلتين داخل معاملة قاعدة بيانات واحدة، ويستخدم متوسط تكلفة المصدر.", 14f, MUTED))
        listOf(item, from, to, quantity, date, memo).forEach { form.addView(it) }
        form.addView(a.pill("حفظ التحويل") {
            val q = quantity.text.toString().num()
            if (item.text.isBlank() || from.text.isBlank() || to.text.isBlank() || q == null || q.signum() <= 0) {
                a.toast("أدخل الصنف والمستودعين والكمية بشكل صحيح")
            } else {
                a.lifecycleScope.launch {
                    try {
                        a.app.accounting.transferStock(item.text.toString(), from.text.toString(), to.text.toString(), q, date.text.toString(), memo.text.toString())
                        a.toast("تم التحويل بنجاح")
                        a.back()
                    } catch (e: Exception) { a.toast(e.message ?: "تعذر تنفيذ التحويل") }
                }
            }
        }.apply { setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(14)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(18) })
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
}
