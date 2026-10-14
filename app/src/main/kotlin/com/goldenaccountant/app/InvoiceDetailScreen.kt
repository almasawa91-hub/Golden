package com.goldenaccountant.app

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.core.accounting.InvoiceKind
import com.goldenaccountant.core.accounting.fromMicro
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Locale

class InvoiceDetailScreen(private val invoiceId: Long, val kind: InvoiceKind) : Screen {
    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val sale = kind == InvoiceKind.SALE
        root.addView(a.header("تفاصيل الفاتورة", { a.back() }, "PDF" to {
            a.lifecycleScope.launch {
                try {
                    val invoice = a.app.db.invoices().byId(invoiceId) ?: throw IllegalArgumentException("الفاتورة غير موجودة")
                    val lines = a.app.db.invoices().linesForInvoice(invoiceId)
                    val cancelled = a.app.db.invoiceCancellations().byInvoiceId(invoiceId) != null
                    val rows = mutableListOf(
                        listOf("رقم الفاتورة", invoice.number.toString()),
                        listOf("النوع", if (sale) "بيع" else "شراء"),
                        listOf("التاريخ", invoice.date),
                        listOf("الطرف", invoice.partyName),
                        listOf("الحالة", if (cancelled) "ملغاة بعكس القيد" else if (invoice.isReturn) "مرتجع" else "نشطة"),
                        listOf("البيان", invoice.memo),
                    )
                    lines.forEach { line ->
                        val total = line.qtyMicro.fromMicro().multiply(line.priceMicro.fromMicro())
                        rows.add(listOf(line.itemName, "الكمية " + fmtMicro(line.qtyMicro), "السعر " + fmtMicro(line.priceMicro), "الإجمالي " + fmt(total)))
                    }
                    rows.add(listOf("الصافي", fmtMicro(invoice.netMicro)))
                    rows.add(listOf("الضريبة", fmtMicro(invoice.taxMicro)))
                    rows.add(listOf("الرسوم", fmtMicro(invoice.feesMicro)))
                    rows.add(listOf("الإجمالي", fmtMicro(invoice.totalMicro)))
                    rows.add(listOf("المدفوع", fmtMicro(invoice.paidMicro)))
                    a.requestReportPdf("فاتورة رقم ${invoice.number}", emptyList(), rows)
                } catch (e: Exception) {
                    a.toast(e.message ?: "تعذر تجهيز الفاتورة")
                }
            }
        }))
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(18))
        }
        body.addView(a.text("جاري تحميل الفاتورة...", 15f, MUTED))
        root.addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        a.lifecycleScope.launch {
            try {
                val invoice = a.app.db.invoices().byId(invoiceId) ?: throw IllegalArgumentException("الفاتورة غير موجودة")
                val lines = a.app.db.invoices().linesForInvoice(invoiceId)
                val cancellation = a.app.db.invoiceCancellations().byInvoiceId(invoiceId)
                body.removeAllViews()
                body.addView(a.text("فاتورة ${if (sale) "بيع" else "شراء"} رقم ${invoice.number}", 20f, NAVY, true))
                body.addView(a.rowOf("التاريخ", invoice.date))
                body.addView(a.rowOf(if (sale) "العميل" else "المورد", invoice.partyName))
                body.addView(a.rowOf("نوع العملية", if (invoice.isReturn) "مرتجع" else if (invoice.isCash) "نقدي" else "آجل"))
                body.addView(a.rowOf("الحالة", if (cancellation != null) "ملغاة — تم عكس القيد" else if (invoice.isReturn) "مرتجع" else "نشطة"))
                body.addView(a.rowOf("البيان", invoice.memo))
                body.addView(a.rowOf("الصنف", "الكمية", "السعر", "الإجمالي", header = true))
                lines.forEach { line ->
                    val total = line.qtyMicro.fromMicro().multiply(line.priceMicro.fromMicro())
                    body.addView(a.rowOf(line.itemName, fmtMicro(line.qtyMicro), fmtMicro(line.priceMicro), fmt(total)))
                }
                body.addView(a.rowOf("الصافي", fmtMicro(invoice.netMicro)))
                body.addView(a.rowOf("الضريبة", fmtMicro(invoice.taxMicro)))
                body.addView(a.rowOf("الرسوم", fmtMicro(invoice.feesMicro)))
                body.addView(a.rowOf("الإجمالي", fmtMicro(invoice.totalMicro)))
                body.addView(a.rowOf("المدفوع", fmtMicro(invoice.paidMicro)))
                if (cancellation != null) {
                    body.addView(a.text("سبب الإلغاء: ${cancellation.reason} — ${cancellation.date}", 13f, DANGER))
                } else {
                    body.addView(a.pill("إلغاء الفاتورة بعكس القيد") {
                        AlertDialog.Builder(a)
                            .setTitle("إلغاء الفاتورة")
                            .setMessage("لن تُحذف الفاتورة أو قيدها الأصلي. سيُنشأ قيد عكسي وحركات مخزون عكسية. إذا صُرفت البضاعة لاحقاً فقد يُرفض الإلغاء لحماية المخزون.")
                            .setPositiveButton("متابعة") { _, _ ->
                                a.ask("سبب إلغاء الفاتورة", listOf("السبب")) { values ->
                                    a.lifecycleScope.launch {
                                        try {
                                            a.app.invoices.cancelInvoice(invoiceId, today(), values.firstOrNull().orEmpty())
                                            a.toast("تم إلغاء الفاتورة وإنشاء القيد العكسي")
                                            a.refresh()
                                        } catch (e: Exception) {
                                            a.toast("تعذر الإلغاء: " + (e.message ?: "خطأ غير معروف"))
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("تراجع", null)
                            .show()
                    }.apply { setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(16) })
                }
            } catch (e: Exception) {
                body.removeAllViews()
                body.addView(a.text("تعذر تحميل الفاتورة: " + (e.message ?: "خطأ غير معروف"), 14f, DANGER))
            }
        }
        return root
    }
}
