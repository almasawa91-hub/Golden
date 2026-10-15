package com.goldenaccountant.app

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

fun isValidIsoDate(value: String): Boolean = try {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    val parsed = formatter.parse(value)
    parsed != null && formatter.format(parsed) == value
} catch (_: Exception) { false }

interface Screen { fun build(a: MainActivity): View }

class MainActivity : AppCompatActivity() {
    private val stack = ArrayList<Screen>()
    private lateinit var container: FrameLayout
    val app: App get() = application as App
    val users: com.goldenaccountant.data.UserRepository get() = com.goldenaccountant.data.UserRepository(app.db)
    var currentUser: com.goldenaccountant.data.AppUserE? = null
        private set
    fun setCurrentUser(user: com.goldenaccountant.data.AppUserE?) { currentUser = user }
    fun can(permission: String): Boolean = RolePolicy.can(currentUser?.role, permission)

    private fun requiredPermission(screen: Screen): String? = when (screen) {
        is InvoiceListScreen -> if (screen.kind == com.goldenaccountant.core.accounting.InvoiceKind.SALE) "sales" else "purchases"
        is InvoiceScreen -> if (screen.kind == com.goldenaccountant.core.accounting.InvoiceKind.SALE) "sales" else "purchases"
        is InvoiceDetailScreen -> if (screen.kind == com.goldenaccountant.core.accounting.InvoiceKind.SALE) "sales" else "purchases"
        is AccountsScreen, is StatementScreen, is StatementAccountPickerScreen, is ChartScreen, is AccountCategoriesScreen, is CategoryAccountsScreen -> "accounts"
        is VouchersScreen, is VoucherScreen -> "vouchers"
        is JournalScreen -> "journal"
        is ItemsScreen, is StockAdjustmentScreen, is StockReportScreen, is WarehousesScreen, is StockTransferScreen, is StockTransferHistoryScreen, is WarehouseStockScreen, is ExpiryReportScreen, is LowStockReportScreen -> "inventory"
        is ReportsHubScreen, is TrialBalanceScreen, is IncomeScreen, is BalanceSheetScreen -> "reports"
        is SettingsScreen -> "settings"
        is UsersScreen, is AuditScreen -> "admin"
        else -> null
    }

    fun logout() {
        lifecycleScope.launch {
            app.settings.put("session_username", "")
            currentUser = null
            setRoot(LoginScreen())
        }
    }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportBackup(uri) }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importBackup(uri) }

    private val legacyImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) previewLegacyImport(uri) }

    private data class PdfPayload(
        val title: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val pageWidth: Int = 595,
        val pageHeight: Int = 842,
    )
    private var pendingPdf: PdfPayload? = null
    private val pdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val payload = pendingPdf
        pendingPdf = null
        if (uri != null && payload != null) exportReportPdf(uri, payload)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (stack.size > 1) back() else finish() }
        })
        lifecycleScope.launch {
            AppDatabase.seed(app.db)
            val companyName = app.settings.get("company_name").trim()
            setRoot(when {
                companyName.isEmpty() -> CompanySetupScreen(firstRun = true)
                users.count() == 0 -> UserSetupScreen(firstRun = true)
                else -> LoginScreen()
            })
        }
    }

    fun push(s: Screen) {
        if (s !is LoginScreen && s !is UserSetupScreen && s !is CompanySetupScreen && currentUser == null) {
            toast("سجّل الدخول أولاً")
            setRoot(LoginScreen())
            return
        }
        val permission = requiredPermission(s)
        if (permission != null && !can(permission)) {
            toast("ليس لديك صلاحية لتنفيذ هذه العملية")
            return
        }
        stack += s
        show()
    }
    fun setRoot(s: Screen) { stack.clear(); stack += s; show() }
    fun refresh() = show()
    fun back() { if (stack.size > 1) { stack.removeAt(stack.size - 1); show() } }
    private fun show() { container.removeAllViews(); container.addView(stack.last().build(this)) }

    fun requestReportPdf(
        title: String,
        columns: List<String>,
        rows: List<List<String>>,
        pageWidth: Int = 595,
        pageHeight: Int = 842,
    ) {
        pendingPdf = PdfPayload(title, columns.toList(), rows.map { it.toList() }, pageWidth, pageHeight)
        val safeTitle = title.replace(" ", "-").replace("/", "-").replace("\\", "-")
        pdfLauncher.launch("Golden-report-$safeTitle-${today()}.pdf")
    }

    private fun exportReportPdf(destination: Uri, payload: PdfPayload) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        ReportPdf.write(output, payload.title, payload.columns, payload.rows, payload.pageWidth, payload.pageHeight)
                    } ?: throw IllegalStateException("تعذر فتح ملف PDF للحفظ")
                }
                toast("تم تصدير التقرير بصيغة PDF")
            } catch (e: Exception) {
                toast("تعذر تصدير PDF: " + (e.message ?: "خطأ غير معروف"))
            }
        }
    }

    fun chooseBackupDestination() {
        if (!can("backup")) return toast("لا تملك صلاحية النسخ الاحتياطي")
        backupLauncher.launch("Golden-backup-${today()}.db")
    }

    fun chooseBackupToRestore() {
        if (!can("backup")) return toast("لا تملك صلاحية استعادة النسخ الاحتياطية")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("استعادة نسخة احتياطية")
            .setMessage("ستُستبدل البيانات الحالية ببيانات النسخة المختارة. يُنصح بتصدير نسخة حالية أولاً. هل تريد المتابعة؟")
            .setPositiveButton("اختيار النسخة") { _, _ -> restoreLauncher.launch(arrayOf("*/*")) }
            .setNegativeButton("إلغاء", null)
            .show()
    }


    /** معاينة آمنة لقاعدة محاسب سوفت؛ لا تعدّل قاعدة المصدر أو قاعدة Golden. */
    fun chooseLegacyImportPreview() {
        if (!can("backup")) return toast("لا تملك صلاحية استيراد البيانات")
        legacyImportLauncher.launch(arrayOf("*/*"))
    }

    private fun previewLegacyImport(source: Uri) {
        lifecycleScope.launch {
            val incoming = File(cacheDir, "legacy-preview-${System.currentTimeMillis()}.db")
            try {
                val report = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)?.use { input ->
                        incoming.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("تعذر قراءة الملف المحدد")
                    require(incoming.isFile && incoming.length() > 0L) { "الملف المحدد فارغ" }
                    val src = SQLiteDatabase.openDatabase(incoming.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    try {
                        val integrity = src.rawQuery("PRAGMA integrity_check", null).use { c ->
                            if (c.moveToFirst()) c.getString(0) else "لا توجد نتيجة"
                        }
                        require(integrity == "ok") { "فشل فحص سلامة SQLite: $integrity" }
                        val tables = mutableSetOf<String>()
                        src.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
                            while (c.moveToNext()) tables += c.getString(0)
                        }
                        require("customers" in tables && "transactions" in tables && "items" in tables && "bills" in tables) {
                            "الملف لا يبدو قاعدة محاسب سوفت المتوقعة (الجداول الأساسية غير مكتملة)"
                        }
                        val expected = listOf(
                            "account_tree" to "شجرة الحسابات",
                            "customers" to "العملاء والحسابات",
                            "transactions" to "الحركات الحالية",
                            "transactions_h" to "الحركات التاريخية",
                            "bills" to "الفواتير",
                            "bill_transactions" to "تفاصيل الفواتير",
                            "items" to "الأصناف",
                            "currency" to "العملات",
                            "units" to "وحدات القياس",
                            "item_type" to "تصنيفات الأصناف",
                        )
                        val counts = expected.mapNotNull { (table, label) ->
                            if (table !in tables) null else {
                                val n = src.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c ->
                                    if (c.moveToFirst()) c.getLong(0) else 0L
                                }
                                "$label: $n"
                            }
                        }
                        val fkByTable = mutableMapOf<String, Int>()
                        src.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                            while (c.moveToNext()) {
                                val table = c.getString(0) ?: "غير معروف"
                                fkByTable[table] = (fkByTable[table] ?: 0) + 1
                            }
                        }
                        val target = app.db.openHelper.readableDatabase
                        fun collisionCount(sourceTable: String, targetTable: String): Long {
                            if (sourceTable !in tables) return 0L
                            return target.rawQuery(
                                "SELECT COUNT(*) FROM `$sourceTable` s JOIN `$targetTable` t ON TRIM(s.name) = TRIM(t.name) COLLATE NOCASE WHERE s.name IS NOT NULL AND TRIM(s.name) <> ''",
                                null,
                            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                        }
                        val customerCollisions = collisionCount("customers", "accounts")
                        val itemCollisions = collisionCount("items", "items")
                        buildString {
                            appendLine("نتيجة فحص قاعدة محاسب سوفت")
                            appendLine("حجم الملف: ${incoming.length()} بايت")
                            appendLine("سلامة SQLite: سليمة")
                            appendLine()
                            counts.forEach { appendLine(it) }
                            appendLine()
                            appendLine("مطابقات أسماء تحتاج مراجعة قبل الدمج:")
                            appendLine("العملاء/الحسابات الموجودة مسبقاً: $customerCollisions")
                            appendLine("الأصناف الموجودة مسبقاً: $itemCollisions")
                            appendLine()
                            appendLine("مخالفات العلاقات المرجعية: ${fkByTable.values.sum()}")
                            fkByTable.entries.sortedByDescending { it.value }.take(8).forEach {
                                appendLine("• ${it.key}: ${it.value}")
                            }
                            appendLine()
                            appendLine("هذه معاينة فقط: لم تُستورد أو تُعدّل أي بيانات. يلزم تحويل الحركات والفواتير والتحقق من الأرصدة والعملات قبل اعتماد الدمج.")
                        }
                    } finally {
                        src.close()
                    }
                }
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("معاينة الاستيراد — محاسب سوفت")
                    .setMessage(report)
                    .setPositiveButton("حسنًا", null)
                    .show()
            } catch (e: Exception) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("تعذر فحص الملف")
                    .setMessage(e.message ?: "خطأ غير معروف")
                    .setPositiveButton("إغلاق", null)
                    .show()
            } finally {
                incoming.delete()
            }
        }
    }

    private fun exportBackup(destination: Uri) {
        lifecycleScope.launch {
            val snapshot = File(cacheDir, "golden-export-${System.currentTimeMillis()}.db")
            try {
                withContext(Dispatchers.IO) {
                    snapshot.delete()
                    val safePath = snapshot.absolutePath.replace("'", "''")
                    app.db.openHelper.writableDatabase.execSQL("VACUUM INTO '$safePath'")
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        snapshot.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("تعذر فتح وجهة حفظ النسخة")
                }
                toast("تم تصدير النسخة الاحتياطية بنجاح")
            } catch (e: Exception) {
                toast("فشل إنشاء النسخة: " + (e.message ?: "خطأ غير معروف"))
            } finally {
                snapshot.delete()
            }
        }
    }

    private fun importBackup(source: Uri) {
        lifecycleScope.launch {
            val incoming = File(cacheDir, "golden-import-${System.currentTimeMillis()}.db")
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)?.use { input ->
                        incoming.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("تعذر قراءة ملف النسخة")
                    require(incoming.isFile && incoming.length() > 0L) { "الملف المحدد فارغ" }
                    val checkDb = SQLiteDatabase.openDatabase(incoming.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    try {
                        checkDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                            require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "ملف قاعدة البيانات تالف" }
                        }
                        checkDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='accounts'", null).use { cursor ->
                            require(cursor.moveToFirst()) { "هذا الملف ليس نسخة من المحاسب الذهبي" }
                        }
                    } finally {
                        checkDb.close()
                    }
                    app.replaceDatabase(incoming)
                }
                currentUser = null
                app.settings.put("session_username", "")
                val companyName = app.settings.get("company_name").trim()
                setRoot(when {
                    companyName.isEmpty() -> CompanySetupScreen(firstRun = true)
                    users.count() == 0 -> UserSetupScreen(firstRun = true)
                    else -> LoginScreen()
                })
                toast("تمت استعادة النسخة الاحتياطية. سجّل الدخول مجدداً.")
            } catch (e: Exception) {
                toast("فشلت الاستعادة: " + (e.message ?: "خطأ غير معروف"))
            } finally {
                incoming.delete()
            }
        }
    }
}
