package com.goldenaccountant.data

import androidx.room.withTransaction
import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.chart.ChartOfAccounts
import com.goldenaccountant.core.report.*
import java.math.BigDecimal

const val FROM_ALL = "0000-01-01"
const val TO_ALL = "9999-12-31"

data class AccountBalance(val account: AccountE, val balance: BigDecimal)
data class StatementResult(val rows: List<StatementRow>, val accountName: String)
data class VoucherRow(val id: Long, val date: String, val memo: String, val amount: BigDecimal)

class AccountingRepository(private val db: AppDatabase) {

    suspend fun addAccount(name: String, groupId: Long, phone: String? = null): Long {
        val n = name.trim()
        require(n.isNotEmpty()) { "اسم الحساب مطلوب" }
        require(db.accounts().byName(n) == null) { "الحساب موجود مسبقا" }
        return db.accounts().insert(AccountE(name = n, groupId = groupId, phone = phone))
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
            db.journal().post(entry, "voucher", null)
        }

    /** قيد يدوي: يُرفض إذا لم يتوازن. */
    suspend fun journal(date: String, memo: String, lines: List<Triple<String, BigDecimal, BigDecimal>>, opening: Boolean = false): Long =
        db.withTransaction {
            val entry = JournalEntry(date, memo, lines.map { (n, d, c) -> JournalLine(accountId(n), d, c) })
            db.journal().post(entry, if (opening) "opening" else "journal", null)
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

    suspend fun stockSummary() = db.stock().stockSummary()
    suspend fun items() = db.items().page(500, 0)

    suspend fun addItem(name: String, salePrice: BigDecimal, barcode: String?): Long {
        val n = name.trim()
        require(n.isNotEmpty()) { "اسم الصنف مطلوب" }
        require(db.items().byName(n) == null) { "الصنف موجود مسبقا" }
        return db.items().insert(ItemE(name = n, barcode = barcode?.ifBlank { null }, salePriceMicro = salePrice.toMicro()))
    }
}

class SettingsRepository(private val db: AppDatabase) {
    suspend fun get(key: String, default: String = ""): String = db.settings().get(key) ?: default
    suspend fun bool(key: String): Boolean = get(key) == "1"
    suspend fun put(key: String, value: String) = db.settings().put(SettingE(key, value))
    suspend fun putBool(key: String, v: Boolean) = put(key, if (v) "1" else "0")
}
