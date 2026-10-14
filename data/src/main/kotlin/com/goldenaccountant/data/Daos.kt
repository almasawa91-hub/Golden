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

    @Query("SELECT * FROM journal_entries WHERE refType = :refType AND refId = :refId ORDER BY id DESC LIMIT 1")
    abstract suspend fun entryByRef(refType: String, refId: Long): JournalEntryE?

    @Query("SELECT * FROM journal_lines WHERE entryId = :entryId ORDER BY id")
    abstract suspend fun linesForEntry(entryId: Long): List<JournalLineE>

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
    @Query("SELECT * FROM stock_movements WHERE refType = :refType AND refId = :refId ORDER BY id DESC")
    suspend fun byRef(refType: String, refId: Long): List<StockMovementE>
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
    @Query("SELECT * FROM invoices WHERE id = :id LIMIT 1") suspend fun byId(id: Long): InvoiceE?
    @Query("""SELECT l.itemId AS itemId, i.name AS itemName, l.qtyMicro AS qtyMicro, l.priceMicro AS priceMicro
              FROM invoice_lines l JOIN items i ON i.id = l.itemId
              WHERE l.invoiceId = :invoiceId ORDER BY l.id""")
    suspend fun linesForInvoice(invoiceId: Long): List<InvoiceLineDetailRow>
    @Query("SELECT COALESCE(SUM(totalMicro),0) FROM invoices WHERE kind = :k AND date = :date AND isReturn = 0") suspend fun totalForDay(k: String, date: String): Long
}

data class InvoiceLineDetailRow(val itemId: Long, val itemName: String, val qtyMicro: Long, val priceMicro: Long)

@Dao
interface InvoiceCancellationDao {
    @Insert
    suspend fun insert(cancellation: InvoiceCancellationE)

    @Query("SELECT * FROM invoice_cancellations WHERE invoiceId = :invoiceId LIMIT 1")
    suspend fun byInvoiceId(invoiceId: Long): InvoiceCancellationE?

    @Query("SELECT * FROM invoice_cancellations ORDER BY createdAt DESC")
    suspend fun all(): List<InvoiceCancellationE>
}

data class NetRow(val accountId: Long, val netMicro: Long)
data class StmtRow(val date: String, val memo: String, val debitMicro: Long, val creditMicro: Long)
data class StockRow(val name: String, val qtyMicro: Long, val avgMicro: Long?)


@Dao
interface AccountCategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: AccountCategoryE): Long

    @Update
    suspend fun update(category: AccountCategoryE)

    @Query("SELECT * FROM account_categories ORDER BY sortOrder, name")
    suspend fun all(): List<AccountCategoryE>

    @Query("SELECT * FROM account_categories WHERE enabled = 1 ORDER BY sortOrder, name")
    suspend fun enabled(): List<AccountCategoryE>

    @Query("SELECT * FROM account_categories WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): AccountCategoryE?

    @Query("SELECT * FROM account_categories WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AccountCategoryE?

    @Query("DELETE FROM account_categories WHERE id = :id AND isSystem = 0")
    suspend fun deleteCustom(id: Long): Int

    @Query("UPDATE account_categories SET enabled = :enabled WHERE id = :id AND isSystem = 0")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: AccountCategoryLinkE): Long

    @Query("DELETE FROM account_category_links WHERE accountId = :accountId AND categoryId = :categoryId")
    suspend fun unlink(accountId: Long, categoryId: Long)

    @Query("SELECT categoryId FROM account_category_links WHERE accountId = :accountId")
    suspend fun categoryIdsForAccount(accountId: Long): List<Long>

    @Query("SELECT accountId FROM account_category_links WHERE categoryId = :categoryId ORDER BY accountId")
    suspend fun accountIdsForCategory(categoryId: Long): List<Long>

    @Query("""SELECT c.* FROM account_categories c
              JOIN account_category_links l ON l.categoryId = c.id
              WHERE l.accountId = :accountId ORDER BY c.sortOrder, c.name""")
    suspend fun categoriesForAccount(accountId: Long): List<AccountCategoryE>

    @Query("""SELECT a.* FROM accounts a
              JOIN account_category_links l ON l.accountId = a.id
              WHERE l.categoryId = :categoryId ORDER BY a.name""")
    suspend fun accountsForCategory(categoryId: Long): List<AccountE>
}
