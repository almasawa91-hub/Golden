package com.goldenaccountant.app

import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ItemsScreen : ListScreen("الأصناف", listOf("الصنف", "التصنيف", "سعر البيع", "الباركود", "الصلاحية"), { a ->
    a.ask("صنف جديد", listOf(
        "اسم الصنف", "سعر البيع", "الباركود (اختياري)", "التصنيف", "تاريخ الصلاحية YYYY-MM-DD (اختياري)", "ملاحظات", "حد إعادة الطلب (0 لتعطيل التنبيه)",
    ), setOf(1, 6)) { v ->
        a.lifecycleScope.launch {
            try {
                a.app.accounting.addItem(
                    v.getOrElse(0) { "" },
                    v.getOrElse(1) { "" }.num() ?: BigDecimal.ZERO,
                    v.getOrElse(2) { "" },
                    v.getOrElse(3) { "عام" }.ifBlank { "عام" },
                    v.getOrElse(4) { "" },
                    v.getOrElse(5) { "" },
                    v.getOrElse(6) { "" }.num() ?: BigDecimal.ZERO,
                )
                a.refresh()
            } catch (e: Exception) { a.toast(e.message ?: "تعذر الحفظ") }
        }
    }
}) {
    override fun actions(a: MainActivity) = listOf(
        "±" to { a.push(StockAdjustmentScreen()) },
        "المستودعات" to { a.push(WarehousesScreen()) },
        "⇄" to { a.push(StockTransferScreen()) },
        "الصلاحية" to { a.push(ExpiryReportScreen()) },
        "نقص المخزون" to { a.push(LowStockReportScreen()) },
    )
    override suspend fun load(a: MainActivity) = a.app.accounting.items().map {
        Row(listOf(it.name, it.category, fmtMicro(it.salePriceMicro), it.barcode ?: "", it.expiryDate ?: "—"))
    }
}

class StockAdjustmentScreen : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(a.header("تسوية المخزون", { a.back() }))
        val item = a.field("اسم الصنف المسجل")
        val warehouse = a.field("اسم المستودع").apply { setText("المخزن الرئيسي") }
        val quantity = a.field("الكمية", true)
        val cost = a.field("تكلفة الوحدة", true)
        val date = a.field("التاريخ").apply { setText(today()) }
        val receive = Switch(a).apply { text = "توريد للمخزون (إلغاء التفعيل = صرف)"; isChecked = true }
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }
        form.addView(a.text("تُسجّل التسوية كحركة مخزنية مستقلة. الصرف لا يسمح بتجاوز الكمية المتاحة.", 14f, MUTED))
        listOf(item, warehouse, quantity, cost, date, receive).forEach { form.addView(it) }
        form.addView(a.pill("حفظ التسوية") {
            val q = quantity.text.toString().num()
            val c = cost.text.toString().num() ?: BigDecimal.ZERO
            if (item.text.isBlank() || q == null || q.signum() <= 0) {
                a.toast("أدخل اسم الصنف والكمية بشكل صحيح")
            } else if (receive.isChecked && c.signum() < 0) {
                a.toast("التكلفة لا يمكن أن تكون سالبة")
            } else {
                a.lifecycleScope.launch {
                    try {
                        a.app.accounting.adjustStock(item.text.toString(), q, c, receive.isChecked, date.text.toString(), warehouseName = warehouse.text.toString())
                        a.toast("تم حفظ تسوية المخزون")
                        a.back()
                    } catch (e: Exception) {
                        a.toast(e.message ?: "تعذر حفظ التسوية")
                    }
                }
            }
        }.apply { setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(14)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(18) })
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
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
        body.addView(a.pill("بيانات المنشأة") { a.push(CompanySetupScreen(firstRun = false)) }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = a.dp(12) })
        body.addView(a.text("المستخدم والصلاحيات", 16f, NAVY, true).apply { setPadding(0, a.dp(12), 0, a.dp(8)) })
        body.addView(a.text("المستخدم الحالي: ${a.currentUser?.displayName ?: "—"} • ${a.currentUser?.role?.let { RolePolicy.label(it) } ?: ""}", 13f, MUTED))
        if (a.can("admin")) {
            body.addView(a.pill("إدارة المستخدمين") { a.push(UsersScreen()) }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8) })
            body.addView(a.pill("سجل التدقيق") { a.push(AuditScreen()) }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8); bottomMargin = a.dp(8) })
        }
        body.addView(a.pill("تسجيل الخروج") { a.logout() }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(8); bottomMargin = a.dp(8) })
        if (a.can("backup")) {
        body.addView(a.text("النسخ الاحتياطي", 16f, NAVY, true).apply { setPadding(0, a.dp(12), 0, a.dp(8)) })
        body.addView(a.text("صدّر نسخة كاملة من قاعدة البيانات أو استعد نسخة محفوظة. يفحص التطبيق سلامة الملف قبل الاستعادة.", 13f, MUTED))
        body.addView(a.pill("تصدير نسخة احتياطية") { a.chooseBackupDestination() }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10) })
        body.addView(a.pill("استعادة نسخة احتياطية") { a.chooseBackupToRestore() }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10); bottomMargin = a.dp(14) })
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


class CompanySetupScreen(private val firstRun: Boolean) : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(a.header(if (firstRun) "إعداد المنشأة لأول مرة" else "بيانات المنشأة", if (firstRun) null else ({ a.back() })))
        val name = a.field("اسم المنشأة (مطلوب)")
        val phone = a.field("رقم الهاتف")
        val address = a.field("العنوان")
        val currency = a.field("العملة الأساسية").apply { setText("YER") }
        val taxNumber = a.field("الرقم الضريبي")
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(18)) }
        form.addView(a.text("تُحفظ بيانات المنشأة على هذا الجهاز وتُستخدم في إعداد التقارير والفواتير.", 14f, MUTED))
        listOf(name, phone, address, currency, taxNumber).forEach { form.addView(it) }
        val save = a.pill(if (firstRun) "حفظ وبدء الاستخدام" else "حفظ البيانات") {
            val company = name.text.toString().trim()
            if (company.isEmpty()) {
                a.toast("اسم المنشأة مطلوب")
            } else if (currency.text.toString().trim().isEmpty()) {
                a.toast("أدخل رمز العملة")
            } else {
                a.lifecycleScope.launch {
                    try {
                        a.app.settings.put("company_name", company)
                        a.app.settings.put("company_phone", phone.text.toString().trim())
                        a.app.settings.put("company_address", address.text.toString().trim())
                        a.app.settings.put("company_currency", currency.text.toString().trim().uppercase())
                        a.app.settings.put("company_tax_number", taxNumber.text.toString().trim())
                        a.toast("تم حفظ بيانات المنشأة")
                        if (firstRun) a.push(UserSetupScreen(firstRun = true)) else a.back()
                    } catch (e: Exception) {
                        a.toast(e.message ?: "تعذر حفظ البيانات")
                    }
                }
            }
        }
        form.addView(save, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(16) })
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        a.lifecycleScope.launch {
            name.setText(a.app.settings.get("company_name"))
            phone.setText(a.app.settings.get("company_phone"))
            address.setText(a.app.settings.get("company_address"))
            currency.setText(a.app.settings.get("company_currency", "YER"))
            taxNumber.setText(a.app.settings.get("company_tax_number"))
        }
        return root
    }
}


class ExpiryReportScreen : ListScreen(
    "الأصناف المنتهية وقريبة الصلاحية",
    listOf("الصنف", "التصنيف", "تاريخ الصلاحية", "الحالة")
) {
    override suspend fun load(a: MainActivity): List<Row> {
        val today = today()
        val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 90) }.time
        val endDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(end)
        return a.app.accounting.items()
            .filter { item ->
                val expiry = item.expiryDate ?: return@filter false
                isValidIsoDate(expiry) && expiry <= endDate
            }
            .sortedBy { it.expiryDate.orEmpty() }
            .map { item ->
                val expiry = item.expiryDate.orEmpty()
                Row(listOf(item.name, item.category, expiry, if (expiry < today) "منتهي" else "ينتهي خلال 90 يوماً"))
            }
    }
}


class LowStockReportScreen : ListScreen(
    "الأصناف تحت حد إعادة الطلب",
    listOf("الصنف", "المتاح", "حد إعادة الطلب", "النقص")
) {
    override suspend fun load(a: MainActivity): List<Row> {
        val items = a.app.accounting.items()
        val quantities = a.app.db.stock().stockSummary().associate { it.name to it.qtyMicro }
        return items.filter { item ->
            val available = quantities[item.name] ?: 0L
            item.minStockMicro > 0L && available < item.minStockMicro
        }.map { item ->
            val available = quantities[item.name] ?: 0L
            Row(listOf(item.name, fmtMicro(available), fmtMicro(item.minStockMicro), fmtMicro(item.minStockMicro - available)))
        }
    }
}
