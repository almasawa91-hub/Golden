package com.goldenaccountant.app

import android.app.Application
import com.goldenaccountant.data.AppDatabase
import com.goldenaccountant.data.InvoiceRepository
import com.goldenaccountant.data.AccountingRepository
import com.goldenaccountant.data.SettingsRepository
import java.io.File

class App : Application() {
    @Volatile private var database: AppDatabase? = null

    val db: AppDatabase
        get() = database ?: synchronized(this) {
            database ?: AppDatabase.build(this).also { database = it }
        }

    val invoices: InvoiceRepository get() = InvoiceRepository(db)
    val accounting: AccountingRepository get() = AccountingRepository(db)
    val settings: SettingsRepository get() = SettingsRepository(db)

    /** Replaces the database only after the caller has validated the backup file. */
    @Synchronized
    fun replaceDatabase(backupFile: File) {
        require(backupFile.isFile && backupFile.length() > 0L) { "ملف النسخة الاحتياطية فارغ" }
        val target = getDatabasePath("golden.db")
        val safetyCopy = File(cacheDir, "golden-before-restore.db")
        database?.close()
        database = null
        try {
            if (target.exists()) target.copyTo(safetyCopy, overwrite = true)
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            target.parentFile?.mkdirs()
            backupFile.copyTo(target, overwrite = true)
            database = AppDatabase.build(this)
            // Opening the database here ensures Room can read the restored schema.
            database!!.openHelper.writableDatabase
            safetyCopy.delete()
        } catch (e: Exception) {
            target.delete()
            if (safetyCopy.exists()) safetyCopy.copyTo(target, overwrite = true)
            database = AppDatabase.build(this)
            throw e
        } finally {
            safetyCopy.delete()
        }
    }
}
