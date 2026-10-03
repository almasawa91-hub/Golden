package com.goldenaccountant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.goldenaccountant.core.chart.ChartOfAccounts

@Database(
    entities = [AccountGroupE::class, AccountE::class, JournalEntryE::class, JournalLineE::class, WarehouseE::class,
        ItemE::class, StockMovementE::class, CurrencyE::class, SettingE::class, InvoiceE::class, InvoiceLineE::class],
    version = 1,
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

    companion object {
        fun build(ctx: Context): AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "golden.db").build()

        /** زرع شجرة الحسابات الأصلية عند أول تشغيل. */
        suspend fun seed(db: AppDatabase) {
            db.warehouses().insert(WarehouseE(1, "المخزن الرئيسي"))
            if (db.accounts().all().isNotEmpty()) return
            db.accounts().insertGroups(ChartOfAccounts.groups.map { AccountGroupE(it.id, it.name, it.parentId) })
            db.accounts().insertAccounts(ChartOfAccounts.systemAccounts.map { AccountE(it.id, it.name, it.groupId, isSystem = true) })
        }
    }
}
