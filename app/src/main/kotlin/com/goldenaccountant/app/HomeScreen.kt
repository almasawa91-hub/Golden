package com.goldenaccountant.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.goldenaccountant.core.accounting.InvoiceKind

class HomeScreen : Screen {
    private var open = -1

    private val groups = listOf(
        "عمليات مخزنية" to listOf("صرف مخزني", "توريد مخزني", "تحويل مخزني", "تسوية مخزنية", "جرد مخزني", "إضافة مخزن"),
        "قيود وحسابات" to listOf("قيد يومي", "قيد إفتتاحي", "إضافة حساب", "حركة الصندوق", "دليل الحسابات", "إقفال سنوي"),
        "أصناف" to listOf("الأصناف", "أسعار البيع", "وحدات الصنف", "فاتورة عرض سعر", "طلب شراء"),
        "العملات" to listOf("إضافة عملة", "سعر العملات", "سقف الحسابات"),
        "التقارير" to listOf("حركة الأصناف", "ميزان المراجعة", "قائمة الدخل", "المركز المالي", "تقارير أخرى"),
    )

    private val routes: Map<String, (MainActivity) -> Unit> = mapOf(
        "قيد يومي" to { a -> a.push(JournalScreen(false)) },
        "قيد إفتتاحي" to { a -> a.push(JournalScreen(true)) },
        "إضافة حساب" to { a -> a.push(AccountsScreen()) },
        "حركة الصندوق" to { a -> a.push(StatementScreen(-3)) },
        "دليل الحسابات" to { a -> a.push(ChartScreen()) },
        "الأصناف" to { a -> a.push(ItemsScreen()) },
        "حركة الأصناف" to { a -> a.push(StockReportScreen()) },
        "ميزان المراجعة" to { a -> a.push(TrialBalanceScreen()) },
        "قائمة الدخل" to { a -> a.push(IncomeScreen()) },
        "المركز المالي" to { a -> a.push(BalanceSheetScreen()) },
        "تقارير أخرى" to { a -> a.push(ReportsHubScreen()) },
    )

    override fun build(a: MainActivity): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(a.header("المحاسب الذهبي", null, "⚙" to { a.push(SettingsScreen()) }))
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val tiles = GridLayout(a).apply { columnCount = 2; setPadding(a.dp(16), a.dp(22), a.dp(16), a.dp(22)) }
        listOf(
            "المبيعات +" to { a.push(InvoiceListScreen(InvoiceKind.SALE)) },
            "المشتريات +" to { a.push(InvoiceListScreen(InvoiceKind.PURCHASE)) },
            "الحسابات" to { a.push(AccountsScreen()) },
            "قبض/صرف" to { a.push(VouchersScreen()) },
        ).forEach { (label, click) ->
            tiles.addView(a.pill(label, click), GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(a.dp(8), a.dp(10), a.dp(8), a.dp(10))
            })
        }
        body.addView(tiles)
        groups.forEachIndexed { i, (title, items) ->
            body.addView(a.text(title + if (open == i) "  ⌃" else "  ⌄", 15f, Color.WHITE, true).apply {
                background = a.gradient(); setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11))
                setOnClickListener { open = if (open == i) -1 else i; a.refresh() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(3) })
            if (open == i) items.forEach { name ->
                body.addView(a.text(name, 14f).apply {
                    setPadding(a.dp(24), a.dp(12), a.dp(24), a.dp(12)); setOnClickListener { routes[name]?.invoke(a) ?: a.toast(NOT_YET) }
                })
            }
        }
        root.addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(a.footer(null, "المخزن الرئيسي"))
        return root
    }

    companion object { const val NOT_YET = "هذه الشاشة لم تُنفَّذ بعد في النسخة الأصلية" }
}
