package com.goldenaccountant.app

import android.app.Application
import com.goldenaccountant.data.AppDatabase
import com.goldenaccountant.data.InvoiceRepository

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val invoices: InvoiceRepository by lazy { InvoiceRepository(db) }
    val accounting: com.goldenaccountant.data.AccountingRepository by lazy { com.goldenaccountant.data.AccountingRepository(db) }
    val settings: com.goldenaccountant.data.SettingsRepository by lazy { com.goldenaccountant.data.SettingsRepository(db) }
}
