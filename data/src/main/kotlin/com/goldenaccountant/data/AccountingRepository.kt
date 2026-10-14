package com.goldenaccountant.data

import androidx.room.withTransaction
import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.chart.ChartOfAccounts
import com.goldenaccountant.core.inventory.MovingAverage
import com.goldenaccountant.core.inventory.StockState
import com.goldenaccountant.core.report.*
import java.math.BigDecimal

const val FROM_ALL = "0000-01-01"
const val TO_ALL = "9999-12-31"

data class AccountBalance(val account: AccountE, val balance: BigDecimal)
data class StatementResult(val rows: List<StatementRow>, val accountName: String)
data class VoucherRow(val id: Long, val date: String, val memo: String, val amount: BigDecimal)
data class DashboardSummary(val salesToday: BigDecimal, val purchasesToday: BigDecimal, val receivables: BigDecimal, val stockValue: BigDecimal)

class AccountingRepository(private val db: AppDatabase) {

    suspend fun addAccount(name: String, groupId: Long, phone: String? = null): Long {
        val n = name.trim()
        require(n.isNotEmpty()) { "اسم الحساب مطلوب" }
        require(db.accounts().byName(n) == null) { "الحساب موجود مسبقا" }
        val id = db.accounts().insert(AccountE(name = n, groupId = groupId, phone = phone))
        db.logAudit("CREATE", "account", id, "إضافة حساب: $n")
        return id
    }

    private suspend fun accountId(name: String): Long =
        db.accounts().byName(name.trim())?.id ?: throw IllegalArgumentException("الحساب غير موجود: ${name.trim()}")

    /** سند قبض (مدين الصندوق) أو صرف (دائن الصندوق) بقيد متوازن. */
    suspend fun voucher(receipt: Boolean, accountName: String, amount: BigDecimal, date: String, memo: String, rate: BigDecimal = BigDecimal.ONE): Long =
        db.withTransaction {
            require(amount.signum() > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
            val acc = accountId(accountName)
            val m = (amount * rate).r()
            val text = memo.ifBlank { (if (receipt) "سند قبض - " else "سند صرف - ") + accountName.trim() }
            val entry = if (receipt) PostingEngine.receipt(DEFAULT_ACCOUNTS.cash, acc, m, date, text) else PostingEngine.payment(DEFAULT_ACCOUNTS.cash, acc, m, date, text)
            val id = db.journal().post(entry, "voucher", null)
            db.logAudit("POST", "voucher", id, text)
            id
        }

    /** قيد يدوي: يُرفض إذا لم يتوازن. */
    suspend fun journal(date: String, memo: String, lines: List<Triple<String, BigDecimal, BigDecimal>>, opening: Boolean = false): Long =
        db.withTransaction {
            val entry = JournalEntry(date, memo, lines.map { (n, d, c) -> JournalLine(accountId(n), d, c) })
            val id = db.journal().post(entry, if (opening) "opening" else "journal", null)
            db.logAudit("POST", if (opening) "opening" else "journal", id, memo)
            id
        }

    private suspend fun infos() = db.accounts().all().map { AccountInfo(it.id, it.name, it.groupId) }
    private suspend fun nets(from: String, to: String): Map<Long, BigDecimal> =
        db.journal().nets(from, to).associate { it.accountId to it.netMicro.fromMicro() }

    suspend fun trialBalance(from: String = FROM_ALL, to: String = TO_ALL) = Reports.trialBalance(infos(), nets(from, to))
    suspend fun incomeStatement(from: String = FROM_ALL, to: String = TO_ALL) = Reports.incomeStatement(infos(), nets(from, to))
    suspend fun balanceSheet(to: String = TO_ALL) = Reports.balanceSheet(infos(), nets(FROM_ALL, to))

    suspend fun statement(accountId: Long, from: String = FROM_ALL, to: String = TO_ALL): StatementResult {
        val acc = db.accounts().all().first { it.id == accountId }
        val opening = if (from == FROM_ALL) BigDecimal.ZERO else db.journal().netBefore(accountId, from).fromMicro()
        val lines = db.journal().stmtRows(accountId, from, to).map { StatementLine(it.date, it.memo, it.debitMicro.fromMicro(), it.creditMicro.fromMicro()) }
        return StatementResult(Reports.statement(opening, lines, ChartOfAccounts.isDebitNature(acc.groupId)), acc.name)
    }

    /** أرصدة الحسابات المحسوبة من القيود (دون الحسابات النظامية). */
    suspend fun balances(groups: List<Long>? = null): List<AccountBalance> {
        val n = nets(FROM_ALL, TO_ALL)
        return db.accounts().all()
            .filter { !it.isSystem && (groups == null || it.groupId in groups) }
            .map { a ->
                val net = n[a.id] ?: BigDecimal.ZERO
                AccountBalance(a, if (ChartOfAccounts.isDebitNature(a.groupId)) net else net.negate())
            }
    }

    suspend fun vouchers(): List<VoucherRow> =
        db.journal().entriesByRef("voucher", 200, 0).map { VoucherRow(it.id, it.date, it.memo, db.journal().entryTotalMicro(it.id).fromMicro()) }

    suspend fun dashboardSummary(date: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())): DashboardSummary {
        val sales = db.invoices().totalForDay(InvoiceKind.SALE.name, date).fromMicro()
        val purchases = db.invoices().totalForDay(InvoiceKind.PURCHASE.name, date).fromMicro()
        val receivables = balances(listOf(123L)).fold(BigDecimal.ZERO) { sum, b ->
            sum + if (b.balance.signum() > 0) b.balance else BigDecimal.ZERO
        }
        val stockValue = db.stock().stockSummary().fold(BigDecimal.ZERO) { sum, row ->
            val quantity = row.qtyMicro.fromMicro()
            val averageCost = (row.avgMicro ?: 0L).fromMicro()
            sum + quantity.multiply(averageCost)
        }
        return DashboardSummary(sales, purchases, receivables, stockValue)
    }

    suspend fun stockSummary() = db.stock().stockSummary()
    suspend fun items() = db.items().page(500, 0)

    suspend fun adjustStock(itemName: String, quantity: BigDecimal, unitCost: BigDecimal, receive: Boolean, date: String, warehouseId: Long = 1): Long =
        db.withTransaction {
            require(quantity.signum() > 0) { "الكمية يجب أن تكون أكبر من صفر" }
            require(unitCost.signum() >= 0) { "التكلفة لا يمكن أن تكون سالبة" }
            val item = db.items().byName(itemName.trim()) ?: throw IllegalArgumentException("الصنف غير موجود")
            val currentQty = db.stock().qtyMicro(item.id, warehouseId).fromMicro()
            val currentAvg = (db.stock().lastAvgCostMicro(item.id) ?: 0L).fromMicro()
            val current = StockState(currentQty, currentAvg)
            val next = if (receive) MovingAverage.receive(current, quantity, unitCost)
                else MovingAverage.issue(current, quantity, allowNegative = false).first
            val id = db.stock().insert(StockMovementE(
                itemId = item.id, warehouseId = warehouseId, date = date,
                kind = if (receive) "ADJUST_IN" else "ADJUST_OUT",
                qtyMicro = (if (receive) quantity else quantity.negate()).toMicro(),
                unitCostMicro = (if (receive) unitCost else currentAvg).toMicro(),
                avgCostMicro = next.avgCost.toMicro(), refType = "adjustment", refId = null,
            ))
            db.logAudit("ADJUST", "stock", id, "${item.name}: ${if (receive) "توريد" else "صرف"} ${quantity.toPlainString()}")
            id
        }

    suspend fun addItem(name: String, salePrice: BigDecimal, barcode: String?): Long {
        val n = name.trim()
        require(n.isNotEmpty()) { "اسم الصنف مطلوب" }
        require(db.items().byName(n) == null) { "الصنف موجود مسبقا" }
        val id = db.items().insert(ItemE(name = n, barcode = barcode?.ifBlank { null }, salePriceMicro = salePrice.toMicro()))
        db.logAudit("CREATE", "item", id, "إضافة صنف: $n")
        return id
    }
}

class SettingsRepository(private val db: AppDatabase) {
    suspend fun get(key: String, default: String = ""): String = db.settings().get(key) ?: default
    suspend fun bool(key: String): Boolean = get(key) == "1"
    suspend fun put(key: String, value: String) = db.settings().put(SettingE(key, value))
    suspend fun putBool(key: String, v: Boolean) = put(key, if (v) "1" else "0")
}
