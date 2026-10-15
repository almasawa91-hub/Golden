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
        AccountCategoryE::class, AccountCategoryLinkE::class, InvoiceCancellationE::class, AppUserE::class, AuditLogE::class, StockTransferE::class,
    ],
    version = 6,
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
    abstract fun users(): UserDao
    abstract fun auditLogs(): AuditLogDao
    abstract fun stockTransfers(): StockTransferDao

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS app_users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    username TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    passwordSalt TEXT NOT NULL,
                    passwordHash TEXT NOT NULL,
                    role TEXT NOT NULL,
                    active INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_app_users_username ON app_users(username)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS audit_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    action TEXT NOT NULL,
                    entityType TEXT NOT NULL,
                    entityId INTEGER,
                    details TEXT NOT NULL,
                    actorUserId INTEGER,
                    actorName TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_createdAt ON audit_logs(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_entityType_entityId ON audit_logs(entityType, entityId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS stock_transfers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    date TEXT NOT NULL,
                    itemId INTEGER NOT NULL,
                    fromWarehouseId INTEGER NOT NULL,
                    toWarehouseId INTEGER NOT NULL,
                    qtyMicro INTEGER NOT NULL,
                    unitCostMicro INTEGER NOT NULL,
                    memo TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(fromWarehouseId) REFERENCES warehouses(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(toWarehouseId) REFERENCES warehouses(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transfers_date ON stock_transfers(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transfers_itemId ON stock_transfers(itemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transfers_fromWarehouseId ON stock_transfers(fromWarehouseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transfers_toWarehouseId ON stock_transfers(toWarehouseId)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN category TEXT NOT NULL DEFAULT 'عام'")
                db.execSQL("ALTER TABLE items ADD COLUMN expiryDate TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(ctx: Context): AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "golden.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

        /** تهيئة دليل الحسابات والمخزن والتصنيفات النظامية من دون استبدال بيانات المستخدم. */
        suspend fun seed(db: AppDatabase) = db.withTransaction {
            db.warehouses().insert(WarehouseE(1, "المخزن الرئيسي"))
            if (db.accounts().all().isEmpty()) {
                db.accounts().insertGroups(ChartOfAccounts.groups.map { AccountGroupE(it.id, it.name, it.parentId) })
                db.accounts().insertAccounts(ChartOfAccounts.systemAccounts.map { AccountE(it.id, it.name, it.groupId, isSystem = true) })
            }
            val defaults = listOf(
                "العملاء" to 123L, "عملاء آجل" to 123L, "عملاء جملة" to 123L, "عملاء تجزئة" to 123L,
                "العملاء (الثقه)" to 126L, "الأمانات" to 221L, "الموردون" to 221L,
                "الصناديق" to 121L, "البنوك" to 122L, "الصناديق والبنوك" to 121L,
                "الأصول" to 12L, "الأصول الثابتة" to 11L, "الأصول المتداولة" to 12L,
                "الالتزامات" to 22L, "حقوق الملكية" to 21L, "المصروفات" to 322L, "الإيرادات" to 411L, "أخرى" to null,
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
