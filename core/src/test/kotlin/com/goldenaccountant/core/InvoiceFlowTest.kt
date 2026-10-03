package com.goldenaccountant.core

import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.inventory.InsufficientStockException
import com.goldenaccountant.core.invoice.*
import java.math.BigDecimal
import kotlin.test.*

private fun bd(s: String) = BigDecimal(s)
private fun assertBd(expected: String, actual: BigDecimal) = assertEquals(0, bd(expected).compareTo(actual), "expected $expected but was $actual")

/** اختبار تكاملي: شراء ثم بيع ثم مرتجع ثم التحقق من المخزون والأرصدة والتوازن. */
class InvoiceFlowTest {
    private val store = InMemoryStockStore()
    private val service = InvoiceService(store)
    private val journal = ArrayList<JournalEntry>()
    private val supplier = 88L
    private val customer = 77L

    private fun req(kind: InvoiceKind, ret: Boolean, qty: String, price: String, party: Long, cash: Boolean = false) = InvoiceRequest(
        kind, ret, cash, party, warehouseId = 1, date = "2026-10-03", memo = "t",
        lines = listOf(InvoiceLine(1, bd(qty), bd(price))),
    )

    private fun run(r: InvoiceRequest): InvoiceResult = service.process(r).also { journal += it.entry }
    private fun lines() = journal.flatMap { it.lines }

    @Test fun purchaseSaleReturnFlow() {
        run(req(InvoiceKind.PURCHASE, false, "10", "60", supplier))
        assertBd("10", store.get(1, 1).qty); assertBd("60", store.get(1, 1).avgCost)

        val sale = run(req(InvoiceKind.SALE, false, "5", "100", customer))
        assertBd("300", sale.entry.lines.first { it.accountId == DEFAULT_ACCOUNTS.cogs }.debit)
        assertBd("5", store.get(1, 1).qty)

        run(req(InvoiceKind.SALE, true, "2", "100", customer))
        assertBd("7", store.get(1, 1).qty)

        assertBd("300", Ledger.balance(lines(), customer, debitNature = true))      // 500 - 200
        assertBd("600", Ledger.balance(lines(), supplier, debitNature = false))
        assertBd("420", Ledger.balance(lines(), DEFAULT_ACCOUNTS.inventory, debitNature = true)) // 7 x 60
        assertBd("180", Ledger.balance(lines(), DEFAULT_ACCOUNTS.cogs, debitNature = true))    // 300 - 120
        val d = lines().fold(ZERO) { a, l -> a + l.debit }; val c = lines().fold(ZERO) { a, l -> a + l.credit }
        assertEquals(0, d.compareTo(c))
    }

    @Test fun oversellRejectedAndStockUntouched() {
        run(req(InvoiceKind.PURCHASE, false, "10", "60", supplier))
        assertFailsWith<InsufficientStockException> { service.process(req(InvoiceKind.SALE, false, "20", "100", customer)) }
        assertBd("10", store.get(1, 1).qty)
    }

    @Test fun movingAverageAcrossPurchases() {
        run(req(InvoiceKind.PURCHASE, false, "10", "60", supplier))
        run(req(InvoiceKind.PURCHASE, false, "10", "100", supplier))
        assertBd("80", store.get(1, 1).avgCost)
    }

    @Test fun foreignCurrencyConvertsToLocal() {
        val r = req(InvoiceKind.SALE, false, "1", "10", customer).copy(exchangeRate = bd("250"))
        run(req(InvoiceKind.PURCHASE, false, "5", "1", supplier))
        val res = run(r)
        assertBd("2500", res.totals.total)
    }

    @Test fun serviceItemsDoNotTouchStock() {
        val r = req(InvoiceKind.SALE, false, "3", "50", customer).copy(serviceItemIds = setOf(1L))
        val res = run(r)
        assertTrue(res.moves.isEmpty()); assertBd("150", res.totals.total)
    }

    @Test fun cashSaleWithTaxBalances() {
        run(req(InvoiceKind.PURCHASE, false, "10", "60", supplier))
        val s = InvoiceService(store, settings = InvoiceSettings(taxRatePercent = bd("5")))
        val e = s.process(req(InvoiceKind.SALE, false, "5", "100", customer, cash = true)).entry
        assertBd("525", e.lines.first { it.accountId == DEFAULT_ACCOUNTS.cash }.debit)
        assertBd("25", e.lines.first { it.accountId == DEFAULT_ACCOUNTS.tax }.credit)
    }
}
