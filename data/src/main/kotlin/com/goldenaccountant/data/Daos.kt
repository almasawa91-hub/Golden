package com.goldenaccountant.data

import androidx.room.*
import com.goldenaccountant.core.accounting.JournalEntry
import com.goldenaccountant.core.accounting.toMicro

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGroups(g: List<AccountGroupE>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAccounts(a: List<AccountE>)
    @Insert suspend fun insert(a: AccountE): Long
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun all(): List<AccountE>
    @Query("SELECT * FROM accounts WHERE name = :n LIMIT 1") suspend fun byName(n: String): AccountE?
    @Query("SELECT * FROM accounts WHERE groupId IN (:groups) ORDER BY name") suspend fun inGroups(groups: List<Long>): List<AccountE>
    @Query("SELECT * FROM accounts WHERE groupId = :g ORDER BY name") suspend fun byGroup(g: Long): List<AccountE>
}

@Dao
abstract class JournalDao {
    @Insert abstract suspend fun insertEntry(e: JournalEntryE): Long
    @Insert abstract suspend fun insertLines(l: List<JournalLineE>)

    /** ترحيل القيد داخل Transaction واحدة. القيد متوازن مسبقا لأن JournalEntry يرفض غير المتوازن. */
    @Transaction
    open suspend fun post(e: JournalEntry, refType: String? = null, refId: Long? = null): Long {
        val id = insertEntry(JournalEntryE(date = e.date, memo = e.memo, refType = refType, refId = refId))
        insertLines(e.lines.map { JournalLineE(entryId = id, accountId = it.accountId, debitMicro = it.debit.toMicro(), creditMicro = it.credit.toMicro()) })
        return id
    }

    /** صافي (مدين - دائن) بالوحدة الصغرى؛ الرصيد يُحسب دائما من القيود. */
    @Query("SELECT COALESCE(SUM(debitMicro) - SUM(creditMicro), 0) FROM journal_lines WHERE accountId = :acc")
    abstract suspend fun netMicro(acc: Long): Long

    @Query("SELECT l.accountId AS accountId, COALESCE(SUM(l.debitMicro),0) - COALESCE(SUM(l.creditMicro),0) AS netMicro FROM journal_lines l JOIN journal_entries e ON e.id = l.entryId WHERE e.date BETWEEN :from AND :to GROUP BY l.accountId")
    abstract suspend fun nets(from: String, to: String): List<NetRow>

    @Query("SELECT COALESCE(SUM(l.debitMicro),0) - COALESCE(SUM(l.creditMicro),0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entryId WHERE l.accountId = :acc AND e.date < :before")
    abstract suspend fun netBefore(acc: Long, before: String): Long

    @Query("SELECT e.date AS date, e.memo AS memo, l.debitMicro AS debitMicro, l.creditMicro AS creditMicro FROM journal_lines l JOIN journal_entries e ON e.id = l.entryId WHERE l.accountId = :acc AND e.date BETWEEN :from AND :to ORDER BY e.date, l.id")
    abstract suspend fun stmtRows(acc: Long, from: String, to: String): List<StmtRow>

    @Query("SELECT * FROM journal_entries WHERE refType = :t ORDER BY id DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun entriesByRef(t: String, limit: Int, offset: Int): List<JournalEntryE>

    @Query("SELECT COALESCE(SUM(debitMicro),0) FROM journal_lines WHERE entryId = :id")
    abstract suspend fun entryTotalMicro(id: Long): Long

    @Query("SELECT COALESCE(SUM(debitMicro),0) FROM journal_lines") abstract suspend fun totalDebitMicro(): Long
    @Query("SELECT COALESCE(SUM(creditMicro),0) FROM journal_lines") abstract suspend fun totalCreditMicro(): Long

    @Query("""SELECT l.* FROM journal_lines l JOIN journal_entries e ON e.id = l.entryId
              WHERE l.accountId = :acc AND e.date BETWEEN :from AND :to ORDER BY e.date, l.id LIMIT :limit OFFSET :offset""")
    abstract suspend fun statement(acc: Long, from: String, to: String, limit: Int, offset: Int): List<JournalLineE>
}

@Dao
interface StockDao {
    @Insert suspend fun insert(m: StockMovementE): Long
    @Query("SELECT COALESCE(SUM(qtyMicro),0) FROM stock_movements WHERE itemId=:item AND warehouseId=:wh") suspend fun qtyMicro(item: Long, wh: Long): Long
    @Query("SELECT i.name AS name, COALESCE(SUM(m.qtyMicro),0) AS qtyMicro, (SELECT avgCostMicro FROM stock_movements WHERE itemId = i.id ORDER BY id DESC LIMIT 1) AS avgMicro FROM items i LEFT JOIN stock_movements m ON m.itemId = i.id GROUP BY i.id ORDER BY i.name")
    suspend fun stockSummary(): List<StockRow>
    @Query("SELECT avgCostMicro FROM stock_movements WHERE itemId=:item ORDER BY id DESC LIMIT 1") suspend fun lastAvgCostMicro(item: Long): Long?
}

@Dao
interface ItemDao {
    @Insert suspend fun insert(i: ItemE): Long
    @Query("SELECT * FROM items WHERE name = :n LIMIT 1") suspend fun byName(n: String): ItemE?
    @Query("SELECT * FROM items WHERE barcode = :code LIMIT 1") suspend fun byBarcode(code: String): ItemE?
    @Query("SELECT * FROM items ORDER BY name LIMIT :limit OFFSET :offset") suspend fun page(limit: Int, offset: Int): List<ItemE>
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: SettingE)
    @Query("SELECT value FROM settings WHERE key = :k") suspend fun get(k: String): String?
}

@Dao
interface WarehouseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(w: WarehouseE): Long
    @Query("SELECT * FROM warehouses ORDER BY id") suspend fun all(): List<WarehouseE>
}

@Dao
interface InvoiceDao {
    @Insert suspend fun insert(i: InvoiceE): Long
    @Insert suspend fun insertLines(l: List<InvoiceLineE>)
    @Query("SELECT COUNT(*) FROM invoices WHERE kind = :k") suspend fun count(k: String): Int
    @Query("SELECT * FROM invoices WHERE kind = :k ORDER BY id DESC LIMIT :limit OFFSET :offset") suspend fun page(k: String, limit: Int, offset: Int): List<InvoiceE>
}

data class NetRow(val accountId: Long, val netMicro: Long)
data class StmtRow(val date: String, val memo: String, val debitMicro: Long, val creditMicro: Long)
data class StockRow(val name: String, val qtyMicro: Long, val avgMicro: Long?)
