package com.goldenaccountant.app

import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.data.AccountingRepository
import kotlinx.coroutines.launch
import java.math.BigDecimal

class VouchersScreen : ListScreen("قبض/صرف", listOf("رقم", "التاريخ", "المبلغ", "البيان"), { a ->
    a.choose("نوع السند", listOf("سند قبض", "سند صرف")) { i -> a.push(VoucherScreen(i == 0)) }
}) {
    override suspend fun load(a: MainActivity) = a.app.accounting.vouchers().map {
        Row(listOf(it.id.toString(), it.date, fmt(it.amount), it.memo))
    }
}

class VoucherScreen(private val receipt: Boolean) : Screen {
    override fun build(a: MainActivity): View {
        val account = a.field("اسم الحساب")
        val amount = a.field("المبلغ", true)
        val memo = a.field("البيان")
        val date = a.field("التاريخ").apply { setText(today()) }
        fun save() {
            val m = amount.text.toString().num()
            if (m == null || m.signum() <= 0) return a.toast("أدخل مبلغاً صحيحاً")
            a.lifecycleScope.launch {
                try {
                    a.app.accounting.voucher(receipt, account.text.toString(), m, date.text.toString(), memo.text.toString())
                    a.toast("تم حفظ العملية"); a.back(); a.back(); a.push(VouchersScreen())
                } catch (e: Exception) { a.toast(e.message ?: "تعذر الحفظ") }
            }
        }
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(a.header(if (receipt) "سند قبض" else "سند صرف", { a.back() }, "💾" to { save() }))
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10)) }
        listOf(date, account, amount, memo).forEach { form.addView(it) }
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
}

class JournalScreen(private val opening: Boolean) : Screen {
    private class L(val account: String, val debit: BigDecimal, val credit: BigDecimal)
    private val lines = ArrayList<L>()

    override fun build(a: MainActivity): View {
        val memo = a.field("البيان").apply { if (opening) setText("قيد إفتتاحي") }
        val date = a.field("التاريخ").apply { setText(today()) }
        val acc = a.field("اسم الحساب")
        val dr = a.field("مدين", true)
        val cr = a.field("دائن", true)
        val table = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val totals = a.text("", 15f, B1, true)
        fun refresh() {
            table.removeAllViews()
            table.addView(a.rowOf("الحساب", "مدين", "دائن", header = true))
            lines.forEach { table.addView(a.rowOf(it.account, fmt(it.debit), fmt(it.credit))) }
            val d = lines.fold(BigDecimal.ZERO) { x, l -> x + l.debit }; val c = lines.fold(BigDecimal.ZERO) { x, l -> x + l.credit }
            totals.text = "المجموع: مدين " + fmt(d) + "  دائن " + fmt(c) + if (lines.isNotEmpty() && d.compareTo(c) != 0) "  (غير متوازن)" else ""
        }
        fun save() {
            if (lines.size < 2) return a.toast("القيد يحتاج سطرين على الأقل")
            a.lifecycleScope.launch {
                try {
                    a.app.accounting.journal(date.text.toString(), memo.text.toString(), lines.map { Triple(it.account, it.debit, it.credit) }, opening)
                    a.toast("تم حفظ العملية"); a.back()
                } catch (e: Exception) { a.toast(e.message ?: "تعذر الحفظ") }
            }
        }
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(a.header(if (opening) "قيد إفتتاحي" else "قيد يومي", { a.back() }, "💾" to { save() }))
        val form = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6)) }
        listOf(date, memo, acc).forEach { form.addView(it) }
        form.addView(LinearLayout(a).apply {
            addView(dr, LinearLayout.LayoutParams(0, -2, 1f)); addView(cr, LinearLayout.LayoutParams(0, -2, 1f))
            addView(a.text("⊕", 28f, B2).apply {
                setOnClickListener {
                    val d = dr.text.toString().num() ?: BigDecimal.ZERO; val c = cr.text.toString().num() ?: BigDecimal.ZERO
                    if (acc.text.isBlank() || (d.signum() > 0) == (c.signum() > 0)) return@setOnClickListener a.toast("أدخل الحساب ومبلغاً مدينا أو دائنا فقط")
                    lines += L(acc.text.toString().trim(), d, c); acc.text.clear(); dr.text.clear(); cr.text.clear(); refresh()
                }
            })
        })
        form.addView(table); form.addView(totals)
        root.addView(ScrollView(a).apply { addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh()
        return root
    }
}
