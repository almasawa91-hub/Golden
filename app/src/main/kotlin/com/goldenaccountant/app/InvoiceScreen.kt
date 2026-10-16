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

class InvoiceScreen(val kind: InvoiceKind) : Screen {
    private val lines = ArrayList<DraftLine>()

    override fun build(a: MainActivity): View {
        val sale = kind == InvoiceKind.SALE
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val isReturn = CheckBox(a).apply { text = "مرتجع" }
        val credit = Switch(a).apply { text = "آجل"; isChecked = true }

        // AutoCompleteTextView displays real database matches from the first character.
        val party = AutoCompleteTextView(a).apply {
            hint = if (sale) "ابدأ بكتابة اسم العميل..." else "ابدأ بكتابة اسم المورد..."
            textSize = 15f
            threshold = 1
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), BORDER)
            }
        }
        val date = a.field("التاريخ").apply { setText(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
        val note = a.field("ملاحظات")
        val item = AutoCompleteTextView(a).apply {
            hint = "ابدأ بكتابة اسم الصنف..."
            textSize = 15f
            threshold = 1
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = a.dp(10).toFloat(); setStroke(a.dp(1), BORDER)
            }
        }
        val qty = a.field("الكمية", true)
        val price = a.field("السعر", true)
        val table = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val total = a.text("الإجمالي: 0.00", 16f, B1, true)
        var itemPrices = emptyMap<String, String>()

        fun refresh() {
            table.removeAllViews()
            table.addView(a.rowOf("الصنف", "الكمية", "السعر", "الإجمالي", header = true))
            var sum = BigDecimal.ZERO
            lines.forEach { l ->
                val t = l.qty * l.price
                sum += t
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
        val form = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(16))
        }
        form.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(isReturn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(credit, LinearLayout.LayoutParams(0, -2, 1f))
        })
        form.addView(a.text(if (sale) "العميل" else "المورد", 13f, MUTED, true))
        form.addView(party, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(4); bottomMargin = a.dp(10) })
        form.addView(date)
        form.addView(note)
        form.addView(a.text("الصنف", 13f, MUTED, true).apply { setPadding(0, a.dp(8), 0, a.dp(4)) })
        form.addView(item)
        form.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(qty, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = a.dp(6) })
            addView(price, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = a.dp(6) })
        })
        form.addView(a.pill("＋  إضافة الصنف") {
            val selectedName = item.text.toString().trim()
            val q = qty.text.toString().num()
            val p = price.text.toString().num()
            if (selectedName.isBlank() || q == null || q.signum() <= 0 || p == null || p.signum() < 0) {
                a.toast("اختر الصنف وأدخل كمية صحيحة وسعراً غير سالب")
                return@pill
            }
            lines += DraftLine(selectedName, q, p)
            item.text.clear(); qty.text.clear(); price.text.clear()
            item.clearFocus()
            refresh()
        }.apply {
            setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(12))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8); bottomMargin = a.dp(12) })

        form.addView(table)
        form.addView(total.apply { setPadding(0, a.dp(12), 0, a.dp(8)) })
        root.addView(ScrollView(a).apply { isFillViewport = true; addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))

        a.lifecycleScope.launch {
            try {
                val accounts = a.app.db.accounts().all()
                    .filter { it.groupId == if (sale) 123L else 221L }
                    .map { it.name }
                    .distinct()
                    .sorted()
                party.setAdapter(ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, accounts))
                party.setOnFocusChangeListener { _, focused -> if (focused && party.text.isNotEmpty()) party.showDropDown() }

                val items = a.app.accounting.items()
                val names = items.map { it.name }.distinct().sorted()
                itemPrices = items.associate { it.name to fmtMicro(it.salePriceMicro) }
                item.setAdapter(ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, names))
                item.setOnItemClickListener { _, _, position, _ ->
                    val selected = item.adapter.getItem(position)?.toString().orEmpty()
                    if (selected.isNotBlank()) itemPrices[selected]?.let { price.setText(it) }
                }
                item.setOnFocusChangeListener { _, focused -> if (focused && item.text.isNotEmpty()) item.showDropDown() }
            } catch (e: Exception) {
                a.toast("تعذر تحميل اقتراحات العملاء والأصناف")
            }
        }
        refresh()
        return root
    }
}
