package com.goldenaccountant.core

import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.invoice.*
import com.goldenaccountant.core.report.*
import java.math.BigDecimal
import kotlin.test.*

private fun bd(s: String) = BigDecimal(s)
private fun assertBd(expected: String, actual: BigDecimal) = assertEquals(0, bd(expected).compareTo(actual), "expected $expected but was $actual")

class ReportsTest {
    private val accounts = listOf(
        AccountInfo(77, "فلان", 123), AccountInfo(88, "فا", 221),
        AccountInfo(DEFAULT_ACCOUNTS.inventory, "المخزون", 125), AccountInfo(DEFAULT_ACCOUNTS.salesCredit, "مبيعات آجل", 411),
        AccountInfo(DEFAULT_ACCOUNTS.cogs, "تكلفة المبيعات", 31), AccountInfo(-24, "اجور نقل", 322),
    )

    private fun scenario(): List<JournalEntry> {
        val svc = InvoiceService(InMemoryStockStore())
        fun r(kind: InvoiceKind, q: String, p: String, party: Long) = InvoiceRequest(kind, false, false, party, 1, "2026-10-03", "m", listOf(InvoiceLine(1, bd(q), bd(p))))
        return listOf(
            svc.process(r(InvoiceKind.PURCHASE, "10", "60", 88)).entry,
            svc.process(r(InvoiceKind.SALE, "5", "100", 77)).entry,
            PostingEngine.payment(-3, -24, bd("20"), "2026-10-04", "نقل"),
        )
    }

    @Test fun trialBalanceBalances() {
        val nets = Reports.netsOf(scenario().flatMap { it.lines })
        val tb = Reports.trialBalance(accounts + AccountInfo(-3, "الصندوق", 121), nets)
        assertTrue(tb.isBalanced)
    }

    @Test fun incomeStatementProfit() {
        val nets = Reports.netsOf(scenario().flatMap { it.lines })
        val inc = Reports.incomeStatement(accounts, nets)
        assertBd("500", inc.totalRevenue); assertBd("320", inc.totalExpense); assertBd("180", inc.netProfit)
    }

    @Test fun balanceSheetBalances() {
        val nets = Reports.netsOf(scenario().flatMap { it.lines })
        val bs = Reports.balanceSheet(accounts + AccountInfo(-3, "الصندوق", 121), nets)
        assertTrue(bs.isBalanced, "assets ${bs.totalAssets} vs ${bs.totalLiabilitiesEquity}")
        assertBd("180", bs.profit)
    }

    @Test fun statementRunningBalance() {
        val rows = Reports.statement(
            bd("100"),
            listOf(StatementLine("d1", "a", bd("50"), ZERO), StatementLine("d2", "b", ZERO, bd("30"))),
            debitNature = true,
        )
        assertEquals(3, rows.size)
        assertBd("150", rows[1].balance); assertBd("120", rows[2].balance)
    }

    @Test fun statementCreditNatureFlipsSign() {
        val rows = Reports.statement(bd("-100"), listOf(StatementLine("d", "x", ZERO, bd("40"))), debitNature = false)
        assertBd("100", rows[0].balance); assertBd("140", rows[1].balance)
    }
}
