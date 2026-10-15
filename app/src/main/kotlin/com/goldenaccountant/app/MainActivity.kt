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
import com.goldenaccountant.data.LegacyDatabaseImporter
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
            val incoming = File(cacheDir, "legacy-import-${System.currentTimeMillis()}.db")
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
                        require(listOf("account_tree", "customers", "transactions", "transactions_h", "bills",
                            "bill_transactions", "items", "currency", "currency_price", "units", "item_type").all { it in tables }) {
                            "الملف لا يبدو قاعدة محاسب سوفت مكتملة؛ تأكد من اختيار ملف SQLite الأصلي."
                        }
                        val expected = listOf(
                            "account_tree" to "مجموعات الحسابات",
                            "customers" to "الحسابات والعملاء",
                            "transactions" to "الحركات الحالية",
                            "transactions_h" to "الحركات التاريخية",
                            "bills" to "الفواتير",
                            "bill_transactions" to "تفاصيل الفواتير",
                            "items" to "الأصناف",
                            "currency" to "العملات",
                            "currency_price" to "أسعار العملات",
                            "units" to "وحدات القياس",
                            "item_type" to "تصنيفات الأصناف",
                        )
                        val counts = expected.map { (table, label) ->
                            val n = src.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c ->
                                if (c.moveToFirst()) c.getLong(0) else 0L
                            }
                            "$label: $n"
                        }
                        val target = app.db.openHelper.readableDatabase
                        fun collisions(sourceTable: String, targetTable: String): Long {
                            val existingNames = mutableSetOf<String>()
                            target.query("SELECT name FROM `$targetTable`").use { c ->
                                while (c.moveToNext()) {
                                    c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { existingNames += it.lowercase(Locale.ROOT) }
                                }
                            }
                            var found = 0L
                            src.rawQuery("SELECT name FROM `$sourceTable` WHERE name IS NOT NULL AND TRIM(name) <> ''", null).use { c ->
                                while (c.moveToNext()) {
                                    val n = c.getString(0)?.trim()?.lowercase(Locale.ROOT).orEmpty()
                                    if (n.isNotEmpty() && n in existingNames) found++
                                }
                            }
                            return found
                        }
                        val fkByTable = mutableMapOf<String, Int>()
                        src.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                            while (c.moveToNext()) {
                                val table = c.getString(0) ?: "غير معروف"
                                fkByTable[table] = (fkByTable[table] ?: 0) + 1
                            }
                        }
                        buildString {
                            appendLine("معاينة قاعدة محاسب سوفت")
                            appendLine("حجم الملف: ${incoming.length()} بايت")
                            appendLine("سلامة SQLite: سليمة")
                            appendLine()
                            counts.forEach { appendLine(it) }
                            appendLine()
                            appendLine("مطابقات أسماء (تُربط بالسجلات الحالية بدل تكرارها):")
                            appendLine("الحسابات: ${collisions("customers", "accounts")}")
                            appendLine("الأصناف: ${collisions("items", "items")}")
                            appendLine()
                            appendLine("مخالفات العلاقات في المصدر: ${fkByTable.values.sum()}")
                            fkByTable.entries.sortedByDescending { it.value }.take(6).forEach {
                                appendLine("• ${it.key}: ${it.value}")
                            }
                            appendLine()
                            appendLine("سيُدمج دليل الحسابات، وتُربط الحسابات بتصنيفاتها، وتُحدّث الأصناف والعملات، وتُسجّل لقطة المخزون الحالية.")
                            appendLine("ستُنقل فواتير البيع والشراء والمرتجعات، وسندات القبض والصرف، والحركات المباشرة القابلة للمطابقة إلى الجداول التشغيلية والقيود المتوازنة.")
                            appendLine("أي حركة غير مرتبطة بفواتير أو سندات قابلة للمطابقة، وأي فرق بين إجمالي الفاتورة وتفاصيلها، سيبقى محفوظاً للمراجعة ولن يُرحّل بتخمين.")
                            appendLine("لن تُحذف بيانات Golden الحالية، والاستيراد قابل لإعادة المحاولة دون تكرار الفواتير أو سندات المخزون المرحّلة.")
                        }
                    } finally {
                        src.close()
                    }
                }
                var importStarted = false
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("مراجعة الدمج — محاسب سوفت")
                    .setMessage(report)
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("بدء الدمج الآمن") { _, _ ->
                        importStarted = true
                        runLegacyImport(incoming)
                    }
                    .create()
                dialog.setOnDismissListener { if (!importStarted) incoming.delete() }
                dialog.show()
            } catch (e: Exception) {
                incoming.delete()
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("تعذر فحص الملف")
                    .setMessage(e.message ?: "خطأ غير معروف")
                    .setPositiveButton("إغلاق", null)
                    .show()
            }
        }
    }

    private fun runLegacyImport(incoming: File) {
        if (!can("backup")) {
            incoming.delete()
            toast("لا تملك صلاحية استيراد البيانات")
            return
        }
        lifecycleScope.launch {
            val progress = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("جارٍ دمج البيانات")
                .setMessage("يتم حفظ البيانات القديمة ومطابقة الحسابات والأصناف. لا تغلق التطبيق.")
                .setCancelable(false)
                .create()
            progress.show()
            try {
                val result = withContext(Dispatchers.IO) {
                    LegacyDatabaseImporter.import(incoming, app.db.openHelper.writableDatabase)
                }
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("اكتمل الدمج الآمن")
                    .setMessage(buildString {
                        appendLine("مجموعات حسابات جديدة: ${result.groupsAdded}")
                        appendLine("حسابات جديدة: ${result.accountsAdded}")
                        appendLine("حسابات رُبطت بسجلات موجودة: ${result.accountsMatched}")
                        appendLine("أصناف جديدة: ${result.itemsAdded}")
                        appendLine("أصناف مكررة جرى ربطها: ${result.itemsMatched}")
                        appendLine("عملات جديدة: ${result.currenciesAdded}")
                        appendLine("أرصدة مخزون حالية أُضيفت: ${result.stockBalancesAdded}")
                        appendLine("فواتير تشغيلية تم ترحيلها: ${result.invoicesAdded}")
                        appendLine("مجموعات السندات والحركات المتوازنة التي تم ترحيلها: ${result.voucherGroupsPosted}")
                        appendLine("حركات الحسابات المباشرة التي تم ترحيلها: ${result.manualTransactionsPosted}")
                        appendLine("مجموعات حركات تحتاج مراجعة: ${result.voucherGroupsForReview}")
                        appendLine("حركات منفردة تحتاج مراجعة: ${result.manualTransactionsForReview}")
                        appendLine("سجلات المصدر المحفوظة: ${result.archivedRowsAdded}")
                        appendLine("سجلات كانت محفوظة سابقاً: ${result.archivedRowsAlreadyPresent}")
                        appendLine()
                        appendLine("تنبيه: أي سند غير متوازن أو يفتقد ربط حساب بقي في الأرشيف للمراجعة. بعض الفواتير القديمة قد تحمل ملاحظة إذا كانت تفاصيلها لا تطابق إجمالي رأس الفاتورة.")
                    })
                    .setPositiveButton("حسنًا") { _, _ -> refresh() }
                    .show()
            } catch (e: Exception) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("فشل الدمج — لم تُعتمد العملية")
                    .setMessage(e.message ?: "خطأ غير معروف")
                    .setPositiveButton("إغلاق", null)
                    .show()
            } finally {
                progress.dismiss()
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
