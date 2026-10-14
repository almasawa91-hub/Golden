package com.goldenaccountant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import com.goldenaccountant.core.chart.ChartOfAccounts

@Database(
    entities = [
        AccountGroupE::class, AccountE::class, JournalEntryE::class, JournalLineE::class,
        WarehouseE::class, ItemE::class, StockMovementE::class, CurrencyE::class,
        SettingE::class, InvoiceE::class, InvoiceLineE::class,
        AccountCategoryE::class, AccountCategoryLinkE::class, InvoiceCancellationE::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun journal(): JournalDao
    abstract fun stock(): StockDao
    abstract fun items(): ItemDao
    abstract fun settings(): SettingDao
    abstract fun warehouses(): WarehouseDao
    abstract fun invoices(): InvoiceDao
    abstract fun invoiceCancellations(): InvoiceCancellationDao
    abstract fun accountCategories(): AccountCategoryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS account_categories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    groupId INTEGER,
                    color TEXT NOT NULL,
                    icon TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    enabled INTEGER NOT NULL,
                    isSystem INTEGER NOT NULL
                )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_account_categories_name ON account_categories(name)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS account_category_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    accountId INTEGER NOT NULL,
                    categoryId INTEGER NOT NULL,
                    FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(categoryId) REFERENCES account_categories(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_account_category_links_accountId_categoryId ON account_category_links(accountId, categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_account_category_links_categoryId ON account_category_links(categoryId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS invoice_cancellations (
                    invoiceId INTEGER NOT NULL PRIMARY KEY,
                    reversedEntryId INTEGER NOT NULL,
                    date TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(invoiceId) REFERENCES invoices(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(reversedEntryId) REFERENCES journal_entries(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoice_cancellations_reversedEntryId ON invoice_cancellations(reversedEntryId)")
            }
        }

        fun build(ctx: Context): AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "golden.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        /** تهيئة دليل الحسابات والمخزن والتصنيفات النظامية من دون استبدال بيانات المستخدم. */
        suspend fun seed(db: AppDatabase) = db.withTransaction {
            db.warehouses().insert(WarehouseE(1, "المخزن الرئيسي"))
            if (db.accounts().all().isEmpty()) {
                db.accounts().insertGroups(ChartOfAccounts.groups.map { AccountGroupE(it.id, it.name, it.parentId) })
                db.accounts().insertAccounts(ChartOfAccounts.systemAccounts.map { AccountE(it.id, it.name, it.groupId, isSystem = true) })
            }
            val defaults = listOf(
                "العملاء" to 123L,
                "عملاء آجل" to 123L,
                "عملاء جملة" to 123L,
                "عملاء تجزئة" to 123L,
                "الأمانات" to 221L,
                "الموردون" to 221L,
                "الصناديق والبنوك" to 121L,
                "المصروفات" to 322L,
                "الإيرادات" to 411L,
                "أخرى" to null,
            )
            defaults.forEachIndexed { index, (name, groupId) ->
                if (db.accountCategories().byName(name) == null) {
                    db.accountCategories().insert(AccountCategoryE(
                        name = name,
                        groupId = groupId,
                        sortOrder = index,
                        isSystem = true,
                    ))
                }
            }
        }
    }
}
