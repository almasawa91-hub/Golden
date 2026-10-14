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
        is ItemsScreen, is StockAdjustmentScreen, is StockReportScreen -> "inventory"
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

    private data class PdfPayload(val title: String, val columns: List<String>, val rows: List<List<String>>)
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

    fun requestReportPdf(title: String, columns: List<String>, rows: List<List<String>>) {
        pendingPdf = PdfPayload(title, columns.toList(), rows.map { it.toList() })
        val safeTitle = title.replace(" ", "-").replace("/", "-").replace("\\", "-")
        pdfLauncher.launch("Golden-report-$safeTitle-${today()}.pdf")
    }

    private fun exportReportPdf(destination: Uri, payload: PdfPayload) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        ReportPdf.write(output, payload.title, payload.columns, payload.rows)
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
