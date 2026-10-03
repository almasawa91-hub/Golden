package com.goldenaccountant.core

import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.chart.ChartOfAccounts
import com.goldenaccountant.core.inventory.*
import java.math.BigDecimal
import kotlin.test.*

private fun bd(s: String) = BigDecimal(s)
private fun assertBd(expected: String, actual: BigDecimal) = assertEquals(0, bd(expected).compareTo(actual), "expected $expected but was $actual")

class AccountingTest {
    private val ids = SystemAccounts(cash = -3, salesCash = -5, salesCredit = -1, salesReturnCash = -11, salesReturnCredit = -9, tax = -18, inventory = -14, cogs = 9001, fees = -24)
    private val lines = listOf(InvoiceLine(1, bd("5"), bd("100")))

    @Test fun exclusiveTax() {
        val t = InvoiceMath.totals(lines, taxRatePercent = bd("5"))
        assertBd("500", t.net); assertBd("25", t.tax); assertBd("525", t.total)
    }

    @Test fun inclusiveTax() {
        val t = InvoiceMath.totals(listOf(InvoiceLine(1, bd("1"), bd("105"))), taxRatePercent = bd("5"), taxInclusive = true)
        assertBd("100", t.net); assertBd("5", t.tax); assertBd("105", t.total)
    }

    @Test fun percentDiscount() {
        val t = InvoiceMath.totals(lines, discount = bd("10"), discountIsPercent = true)
        assertBd("450", t.total)
    }

    @Test fun cashSaleIsBalancedAndDebitsCash() {
        val t = InvoiceMath.totals(lines, taxRatePercent = bd("5"))
        val e = PostingEngine.invoice(InvoiceKind.SALE, false, true, 77, t, ids, "2026-10-03", "بيع", costOfGoods = bd("300"))
        assertBd("825", e.totalDebit); assertBd("825", e.totalCredit)
        assertBd("525", e.lines.first { it.accountId == -3L }.debit)
    }

    @Test fun creditSaleDebitsCustomer() {
        val t = InvoiceMath.totals(lines)
        val e = PostingEngine.invoice(InvoiceKind.SALE, false, false, 77, t, ids, "d", "m")
        assertBd("500", e.lines.first { it.accountId == 77L }.debit)
    }

    @Test fun purchaseCreditsSupplierAndDebitsInventory() {
        val t = InvoiceMath.totals(lines, taxRatePercent = bd("5"))
        val e = PostingEngine.invoice(InvoiceKind.PURCHASE, false, false, 88, t, ids, "d", "m")
        assertBd("525", e.lines.first { it.accountId == 88L }.credit)
        assertBd("500", e.lines.first { it.accountId == -14L }.debit)
    }

    @Test fun salesReturnReversesSides() {
        val t = InvoiceMath.totals(lines)
        val e = PostingEngine.invoice(InvoiceKind.SALE, true, false, 77, t, ids, "d", "m")
        assertBd("500", e.lines.first { it.accountId == 77L }.credit)
        assertBd("500", e.lines.first { it.accountId == -9L }.debit)
    }

    @Test fun partialPaymentOnCreditSale() {
        val t = InvoiceMath.totals(lines)
        val e = PostingEngine.invoice(InvoiceKind.SALE, false, false, 77, t, ids, "d", "m", paid = bd("200"))
        val bal = Ledger.balance(e.lines, 77, debitNature = true)
        assertBd("300", bal)
    }

    @Test fun receiptAndPaymentBalance() {
        val r = PostingEngine.receipt(-3, 77, bd("200"), "d", "m")
        assertBd("200", r.totalDebit)
        val p = PostingEngine.payment(-3, 88, bd("50"), "d", "m")
        assertBd("50", p.totalCredit)
    }

    @Test fun unbalancedEntryIsRejected() {
        assertFailsWith<UnbalancedEntryException> {
            JournalEntry("d", "m", listOf(JournalLine(1, debit = bd("10")), JournalLine(2, credit = bd("9"))))
        }
    }

    @Test fun lineCannotBeBothSides() {
        assertFailsWith<IllegalArgumentException> { JournalLine(1, bd("1"), bd("1")) }
    }

    @Test fun microRoundTrip() {
        assertEquals(bd("12.3456"), bd("12.3456").toMicro().fromMicro())
    }

    @Test fun movingAverage() {
        var s = MovingAverage.receive(StockState(), bd("10"), bd("60"))
        s = MovingAverage.receive(s, bd("10"), bd("100"))
        assertBd("80", s.avgCost); assertBd("20", s.qty)
        val (after, cost) = MovingAverage.issue(s, bd("5"))
        assertBd("400", cost); assertBd("15", after.qty); assertBd("80", after.avgCost)
    }

    @Test fun insufficientStockRejectedUnlessNegativeAllowed() {
        val s = StockState(bd("2"), bd("10"))
        assertFailsWith<InsufficientStockException> { MovingAverage.issue(s, bd("3")) }
        assertBd("-1", MovingAverage.issue(s, bd("3"), allowNegative = true).first.qty)
    }

    @Test fun chartOfAccountsMatchesReference() {
        assertEquals(34, ChartOfAccounts.groups.size)
        assertEquals(23, ChartOfAccounts.systemAccounts.size)
        assertTrue(ChartOfAccounts.isDebitNature(123)); assertFalse(ChartOfAccounts.isDebitNature(221))
    }
}
