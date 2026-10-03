package com.goldenaccountant.app

import com.goldenaccountant.core.accounting.fromMicro

class TrialBalanceScreen : ListScreen("ميزان المراجعة", listOf("الحساب", "مدين", "دائن")) {
    override suspend fun load(a: MainActivity): List<Row> {
        val tb = a.app.accounting.trialBalance()
        return tb.rows.map { Row(listOf(it.name, fmt(it.debit), fmt(it.credit))) } +
            Row(listOf("الإجمالي" + if (tb.isBalanced) "" else " (غير متوازن)", fmt(tb.totalDebit), fmt(tb.totalCredit)))
    }
}

class IncomeScreen : ListScreen("قائمة الدخل", listOf("البند", "المبلغ")) {
    override suspend fun load(a: MainActivity): List<Row> {
        val s = a.app.accounting.incomeStatement()
        return listOf(Row(listOf("الإيرادات", ""))) + s.revenues.map { Row(listOf("  " + it.name, fmt(it.amount))) } +
            Row(listOf("إجمالي الإيرادات", fmt(s.totalRevenue))) + Row(listOf("المصروفات والتكاليف", "")) +
            s.expenses.map { Row(listOf("  " + it.name, fmt(it.amount))) } + Row(listOf("إجمالي المصروفات", fmt(s.totalExpense))) +
            Row(listOf("صافي الربح", fmt(s.netProfit)))
    }
}

class BalanceSheetScreen : ListScreen("المركز المالي", listOf("البند", "المبلغ")) {
    override suspend fun load(a: MainActivity): List<Row> {
        val b = a.app.accounting.balanceSheet()
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
        "حركة الصندوق" to { a.push(StatementScreen(-3)) },
    ).map { (n, c) -> Row(listOf(n), c) }
}
