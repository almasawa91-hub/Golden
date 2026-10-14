package com.goldenaccountant.app

import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.core.accounting.InvoiceKind
import com.goldenaccountant.core.accounting.fromMicro
import com.goldenaccountant.data.InvoiceE
import kotlinx.coroutines.launch
import java.util.Locale

class InvoiceListScreen(private val kind: InvoiceKind) : Screen {
    override fun build(a: MainActivity): View {
        val sale = kind == InvoiceKind.SALE
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val screenTitle = if (sale) "قائمة المبيعات" else "قائمة المشتريات"
        root.addView(a.header(screenTitle, { a.back() }, "PDF" to {
            a.lifecycleScope.launch {
                try {
                    val all = a.app.db.invoices().page(kind.name, 500, 0)
                    val cancelled = a.app.db.invoiceCancellations().all().map { it.invoiceId }.toSet()
                    a.requestReportPdf(screenTitle, listOf("رقم", "التاريخ", "الاسم", "المبلغ", "الحالة"), all.map { invoice ->
                        listOf(invoice.number.toString(), invoice.date, invoice.partyName, String.format(Locale.US, "%,.2f", invoice.totalMicro.fromMicro()), if (invoice.id in cancelled) "ملغاة" else if (invoice.isReturn) "مرتجع" else "نشطة")
                    })
                } catch (e: Exception) { a.toast(e.message ?: "تعذر تصدير التقرير") }
            }
        }))

        val search = a.field("بحث بالرقم أو الطرف أو التاريخ أو البيان").apply {
            isSingleLine = true
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = a.dp(10).toFloat()
                setColor(SURFACE)
                setStroke(a.dp(1), BORDER)
            }
        }
        val searchWrap = LinearLayout(a).apply {
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            addView(search, LinearLayout.LayoutParams(-1, a.dp(48)))
        }
        root.addView(searchWrap)
        root.addView(a.rowOf("رقم", "التاريخ", "الاسم", "المبلغ", header = true))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(a.footer({ a.push(InvoiceScreen(kind)) }, if (sale) "فاتورة بيع جديدة" else "فاتورة شراء جديدة"))

        a.lifecycleScope.launch {
            try {
                val invoices = a.app.db.invoices().page(kind.name, 500, 0)
                val cancelledIds = a.app.db.invoiceCancellations().all().map { it.invoiceId }.toSet()
                fun render(query: String) {
                    val filtered = invoices.filter {
                        query.isBlank() ||
                            it.number.toString().contains(query, true) ||
                            it.partyName.contains(query, true) ||
                            it.date.contains(query, true) ||
                            it.memo.contains(query, true) ||
                            (if (it.isReturn) "مرتجع" else "").contains(query, true) ||
                            (if (it.id in cancelledIds) "ملغاة" else "نشطة").contains(query, true)
                    }
                    list.removeAllViews()
                    if (filtered.isEmpty()) {
                        list.addView(a.text(
                            if (query.isBlank()) "لا توجد فواتير مسجلة" else "لا توجد فواتير مطابقة للبحث",
                            15f, MUTED
                        ).apply { gravity = Gravity.CENTER; setPadding(a.dp(12), a.dp(70), a.dp(12), a.dp(12)) })
                    } else {
                        filtered.forEach { i ->
                            list.addView(a.rowOf(
                                i.number.toString(),
                                i.date,
                                i.partyName + (if (i.isReturn) " (مرتجع)" else "") + (if (i.id in cancelledIds) " (ملغاة)" else ""),
                                String.format(Locale.US, "%,.2f", i.totalMicro.fromMicro())
                            ).apply {
                                setOnClickListener { a.push(InvoiceDetailScreen(i.id, kind)) }
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
                list.addView(a.text("تعذر تحميل الفواتير: " + (e.message ?: "خطأ غير معروف"), 14f, DANGER)
                    .apply { setPadding(a.dp(14), a.dp(30), a.dp(14), 0) })
            }
        }
        return root
    }
}
