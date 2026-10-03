package com.goldenaccountant.app

import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.core.accounting.InvoiceKind
import com.goldenaccountant.core.inventory.InsufficientStockException
import com.goldenaccountant.data.DraftLine
import com.goldenaccountant.data.InvoiceDraft
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InvoiceScreen(private val kind: InvoiceKind) : Screen {
    private val lines = ArrayList<DraftLine>()

    override fun build(a: MainActivity): View {
        val sale = kind == InvoiceKind.SALE
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val isReturn = CheckBox(a).apply { text = "مرتجع" }
        val credit = Switch(a).apply { text = "آجل"; isChecked = true }
        val party = a.field(if (sale) "العميل" else "المورد")
        val date = a.field("التاريخ").apply { setText(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
        val note = a.field("ملاحظات")
        val item = a.field("اكتب اسم الصنف")
        val qty = a.field("الكمية", true)
        val price = a.field("السعر", true)
        val table = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val total = a.text("الإجمالي: 0.00", 16f, B1, true)

        fun refresh() {
            table.removeAllViews()
            table.addView(a.rowOf("الصنف", "الكمية", "السعر", "الإجمالي", header = true))
            var sum = BigDecimal.ZERO
            lines.forEach { l ->
                val t = l.qty * l.price; sum += t
                table.addView(a.rowOf(l.itemName, l.qty.stripTrailingZeros().toPlainString(), l.price.stripTrailingZeros().toPlainString(), t.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()))
            }
            total.text = "الإجمالي (قبل الضريبة): " + sum.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        }

        fun save() {
            if (lines.isEmpty()) return a.toast("أضف صنفاً واحداً على الأقل")
            val draft = InvoiceDraft(kind, isReturn.isChecked, !credit.isChecked, party.text.toString(), 1, date.text.toString(), note.text.toString(), lines.toList())
            a.lifecycleScope.launch {
                try {
                    a.app.invoices.post(draft)
                    a.toast("تم حفظ العملية")
                    a.back(); a.back(); a.push(InvoiceListScreen(kind))
                } catch (e: InsufficientStockException) {
                    a.toast(e.message ?: "الكمية غير كافية")
                } catch (e: Exception) {
                    a.toast("تعذر الحفظ: " + (e.message ?: "خطأ غير معروف"))
                }
            }
        }

        root.addView(a.header(if (sale) "بيع" else "شراء", { a.back() }, "💾" to { save() }))
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6)) }
        form.addView(LinearLayout(a).apply { addView(isReturn, LinearLayout.LayoutParams(0, -2, 1f)); addView(credit, LinearLayout.LayoutParams(0, -2, 1f)) })
        listOf(party, date, note, item).forEach { form.addView(it) }
        form.addView(LinearLayout(a).apply {
            addView(qty, LinearLayout.LayoutParams(0, -2, 1f)); addView(price, LinearLayout.LayoutParams(0, -2, 1f))
            addView(a.text("⊕", 28f, B2).apply {
                setOnClickListener {
                    val q = qty.text.toString().num(); val p = price.text.toString().num()
                    if (item.text.isBlank() || q == null || q.signum() <= 0 || p == null) return@setOnClickListener a.toast("أدخل الصنف والكمية والسعر")
                    lines += DraftLine(item.text.toString().trim(), q, p)
                    item.text.clear(); qty.text.clear(); price.text.clear(); refresh()
                }
            })
        })
        form.addView(table); form.addView(total)
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh()
        return root
    }
}
