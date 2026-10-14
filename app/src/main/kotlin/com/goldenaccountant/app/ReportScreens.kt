package com.goldenaccountant.app

import com.goldenaccountant.core.accounting.fromMicro
import com.goldenaccountant.data.FROM_ALL
import com.goldenaccountant.data.TO_ALL

class TrialBalanceScreen : ListScreen("ميزان المراجعة", listOf("الحساب", "مدين", "دائن")) {
    private var fromDate = FROM_ALL
    private var toDate = TO_ALL
    override fun actions(a: MainActivity) = listOf(
        "الفترة" to { a.ask("تصفية ميزان المراجعة", listOf("من تاريخ YYYY-MM-DD", "إلى تاريخ YYYY-MM-DD")) { v ->
            val from = v.getOrNull(0)?.trim().orEmpty()
            val to = v.getOrNull(1)?.trim().orEmpty()
            if (!from.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || !to.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || from > to)
                a.toast("أدخل تاريخين صحيحين وبداية لا تتجاوز النهاية")
            else { fromDate = from; toDate = to; a.refresh() }
        } },
        "كل الفترة" to { fromDate = FROM_ALL; toDate = TO_ALL; a.refresh() },
    )
    override suspend fun load(a: MainActivity): List<Row> {
        val tb = a.app.accounting.trialBalance(fromDate, toDate)
        return tb.rows.map { Row(listOf(it.name, fmt(it.debit), fmt(it.credit))) } +
            Row(listOf("الإجمالي" + if (tb.isBalanced) "" else " (غير متوازن)", fmt(tb.totalDebit), fmt(tb.totalCredit)))
    }
}

class IncomeScreen : ListScreen("قائمة الدخل", listOf("البند", "المبلغ")) {
    private var fromDate = FROM_ALL
    private var toDate = TO_ALL
    override fun actions(a: MainActivity) = listOf(
        "الفترة" to { a.ask("تصفية قائمة الدخل", listOf("من تاريخ YYYY-MM-DD", "إلى تاريخ YYYY-MM-DD")) { v ->
            val from = v.getOrNull(0)?.trim().orEmpty()
            val to = v.getOrNull(1)?.trim().orEmpty()
            if (!from.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || !to.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || from > to)
                a.toast("أدخل تاريخين صحيحين وبداية لا تتجاوز النهاية")
            else { fromDate = from; toDate = to; a.refresh() }
        } },
        "كل الفترة" to { fromDate = FROM_ALL; toDate = TO_ALL; a.refresh() },
    )
    override suspend fun load(a: MainActivity): List<Row> {
        val s = a.app.accounting.incomeStatement(fromDate, toDate)
        return listOf(Row(listOf("الإيرادات", ""))) + s.revenues.map { Row(listOf("  " + it.name, fmt(it.amount))) } +
            Row(listOf("إجمالي الإيرادات", fmt(s.totalRevenue))) + Row(listOf("المصروفات والتكاليف", "")) +
            s.expenses.map { Row(listOf("  " + it.name, fmt(it.amount))) } + Row(listOf("إجمالي المصروفات", fmt(s.totalExpense))) +
            Row(listOf("صافي الربح", fmt(s.netProfit)))
    }
}

class BalanceSheetScreen : ListScreen("المركز المالي", listOf("البند", "المبلغ")) {
    private var toDate = TO_ALL
    override fun actions(a: MainActivity) = listOf(
        "حتى تاريخ" to { a.ask("المركز المالي حتى تاريخ", listOf("التاريخ YYYY-MM-DD")) { v ->
            val to = v.firstOrNull()?.trim().orEmpty()
            if (!to.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) a.toast("أدخل التاريخ بصيغة YYYY-MM-DD")
            else { toDate = to; a.refresh() }
        } },
        "اليوم الحالي" to { toDate = today(); a.refresh() },
    )
    override suspend fun load(a: MainActivity): List<Row> {
        val b = a.app.accounting.balanceSheet(toDate)
        return listOf(Row(listOf("الأصول", ""))) + b.assets.map { Row(listOf("  " + it.name, fmt(it.amount))) } +
            Row(listOf("إجمالي الأصول", fmt(b.totalAssets))) + Row(listOf("الالتزامات وحقوق الملكية", "")) +
            b.liabilities.map { Row(listOf("  " + it.name, fmt(it.amount))) } + Row(listOf("أرباح الفترة", fmt(b.profit))) +
            Row(listOf("الإجمالي" + if (b.isBalanced) "" else " (غير متوازن)", fmt(b.totalLiabilitiesEquity)))
    }
}

class StockReportScreen : ListScreen("حركة الأصناف", listOf("الصنف", "الكمية", "متوسط التكلفة", "القيمة")) {
    override suspend fun load(a: MainActivity) = a.app.accounting.stockSummary().map {
        val q = it.qtyMicro.fromMicro(); val avg = (it.avgMicro ?: 0L).fromMicro()
        Row(listOf(it.name, fmt(q), fmt(avg), fmt(q * avg)))
    }
}

class ReportsHubScreen : ListScreen("تقارير أخرى", listOf("التقرير")) {
    override suspend fun load(a: MainActivity) = listOf(
        "ميزان المراجعة" to { a.push(TrialBalanceScreen()) },
        "قائمة الدخل" to { a.push(IncomeScreen()) },
        "المركز المالي" to { a.push(BalanceSheetScreen()) },
        "المخزون المتبقي" to { a.push(StockReportScreen()) },
        "حركة الصندوق" to { a.push(StatementAccountPickerScreen()) },
    ).map { (n, c) -> Row(listOf(n), c) }
}
