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

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportBackup(uri) }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importBackup(uri) }

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
            setRoot(if (companyName.isEmpty()) CompanySetupScreen(firstRun = true) else HomeScreen())
        }
    }

    fun push(s: Screen) { stack += s; show() }
    fun setRoot(s: Screen) { stack.clear(); stack += s; show() }
    fun refresh() = show()
    fun back() { if (stack.size > 1) { stack.removeAt(stack.size - 1); show() } }
    private fun show() { container.removeAllViews(); container.addView(stack.last().build(this)) }

    fun chooseBackupDestination() {
        backupLauncher.launch("Golden-backup-${today()}.db")
    }

    fun chooseBackupToRestore() {
        restoreLauncher.launch(arrayOf("*/*"))
    }

    private fun exportBackup(destination: Uri) {
        lifecycleScope.launch {
            val snapshot = File(cacheDir, "golden-export-${System.currentTimeMillis()}.db")
            try {
                withContext(Dispatchers.IO) {
                    snapshot.delete()
                    val safePath = snapshot.absolutePath.replace("'", "''")
                    app.db.openHelper.writableDatabase.execSQL("VACUUM INTO '\u0024safePath'")
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
                val companyName = app.settings.get("company_name").trim()
                setRoot(if (companyName.isEmpty()) CompanySetupScreen(firstRun = true) else HomeScreen())
                toast("تم استعادة النسخة الاحتياطية بنجاح")
            } catch (e: Exception) {
                toast("فشلت الاستعادة: " + (e.message ?: "خطأ غير معروف"))
            } finally {
                incoming.delete()
            }
        }
    }
}
