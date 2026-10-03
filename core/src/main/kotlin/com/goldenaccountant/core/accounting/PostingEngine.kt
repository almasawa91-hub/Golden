package com.goldenaccountant.core.accounting

import java.math.BigDecimal

/** معرّفات الحسابات النظامية المستخدمة في الترحيل. */
data class SystemAccounts(
    val cash: Long,
    val salesCash: Long,
    val salesCredit: Long,
    val salesReturnCash: Long,
    val salesReturnCredit: Long,
    val tax: Long,
    val inventory: Long,
    val cogs: Long,
    val fees: Long,
)

enum class InvoiceKind { SALE, PURCHASE }

object PostingEngine {
    private fun dr(a: Long, v: BigDecimal) = JournalLine(a, debit = v.r())
    private fun cr(a: Long, v: BigDecimal) = JournalLine(a, credit = v.r())

    /**
     * ترحيل فاتورة بيع/شراء (أو مرتجعها) إلى قيد مزدوج.
     * البيع: مدين الصندوق/العميل، دائن المبيعات والضريبة، (+ تكلفة المبيعات مقابل المخزون).
     * الشراء: مدين المخزون والضريبة، دائن الصندوق/المورد.
     * المرتجع يعكس الجانبين. [paid] دفعة جزئية على فاتورة آجلة.
     */
    fun invoice(
        kind: InvoiceKind,
        isReturn: Boolean,
        isCash: Boolean,
        partyAccountId: Long,
        t: Totals,
        ids: SystemAccounts,
        date: String,
        memo: String,
        paid: BigDecimal = ZERO,
        costOfGoods: BigDecimal = ZERO,
    ): JournalEntry {
        val out = ArrayList<JournalLine>()
        val party = if (isCash) ids.cash else partyAccountId
        fun add(account: Long, v: BigDecimal, debit: Boolean) {
            if (v.signum() != 0) out += if (debit) dr(account, v) else cr(account, v)
        }
        if (kind == InvoiceKind.SALE) {
            val revenue = if (isReturn) (if (isCash) ids.salesReturnCash else ids.salesReturnCredit)
            else (if (isCash) ids.salesCash else ids.salesCredit)
            add(party, t.total, !isReturn)
            add(revenue, t.net, isReturn)
            add(ids.tax, t.tax, isReturn)
            add(ids.fees, t.fees, isReturn)
            add(ids.cogs, costOfGoods, !isReturn)
            add(ids.inventory, costOfGoods, isReturn)
            if (!isCash && paid.signum() > 0) { add(ids.cash, paid, !isReturn); add(partyAccountId, paid, isReturn) }
        } else {
            add(ids.inventory, t.net, !isReturn)
            add(ids.tax, t.tax, !isReturn)
            add(ids.fees, t.fees, !isReturn)
            add(party, t.total, isReturn)
            if (!isCash && paid.signum() > 0) { add(ids.cash, paid, isReturn); add(partyAccountId, paid, !isReturn) }
        }
        return JournalEntry(date, memo, out)
    }

    /** سند قبض: مدين الصندوق، دائن الحساب. */
    fun receipt(cash: Long, account: Long, amount: BigDecimal, date: String, memo: String) =
        JournalEntry(date, memo, listOf(dr(cash, amount), cr(account, amount)))

    /** سند صرف: مدين الحساب، دائن الصندوق. */
    fun payment(cash: Long, account: Long, amount: BigDecimal, date: String, memo: String) =
        JournalEntry(date, memo, listOf(dr(account, amount), cr(cash, amount)))
}

/** الحسابات النظامية الافتراضية (نفس معرّفات القاعدة الأصلية + المخزون وتكلفة المبيعات). */
val DEFAULT_ACCOUNTS = SystemAccounts(
    cash = -3, salesCash = -5, salesCredit = -1, salesReturnCash = -11, salesReturnCredit = -9,
    tax = -18, inventory = -40, cogs = -41, fees = -24,
)
