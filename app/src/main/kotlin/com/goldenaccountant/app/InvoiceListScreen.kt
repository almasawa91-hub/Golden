package com.goldenaccountant.app

import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.core.accounting.InvoiceKind
import com.goldenaccountant.core.accounting.fromMicro
import kotlinx.coroutines.launch
import java.util.Locale

class InvoiceListScreen(private val kind: InvoiceKind) : Screen {
    override fun build(a: MainActivity): View {
        val sale = kind == InvoiceKind.SALE
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(a.header(if (sale) "قائمة المبيعات" else "قائمة المشتريات", { a.back() }))
        root.addView(a.rowOf("رقم", "التاريخ", "الاسم", "المبلغ", header = true))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val empty = a.text("لا توجد نتائج...", 15f, LINK).apply { gravity = android.view.Gravity.CENTER; setPadding(0, a.dp(90), 0, 0) }
        list.addView(empty)
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(a.footer({ a.push(InvoiceScreen(kind)) }))
        a.lifecycleScope.launch {
            val rows = a.app.db.invoices().page(kind.name, 200, 0)
            if (rows.isNotEmpty()) list.removeAllViews()
            rows.forEach { i ->
                list.addView(a.rowOf(i.number.toString(), i.date, i.partyName + if (i.isReturn) " (مرتجع)" else "",
                    String.format(Locale.US, "%,.2f", i.totalMicro.fromMicro())))
            }
        }
        return root
    }
}
