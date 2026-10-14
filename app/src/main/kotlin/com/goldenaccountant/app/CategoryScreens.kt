package com.goldenaccountant.app

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.data.AccountCategoryE
import com.goldenaccountant.data.AccountCategoryLinkE
import kotlinx.coroutines.launch

class AccountCategoriesScreen : ListScreen(
    "تصنيفات الحسابات",
    listOf("التصنيف", "الحالة", "النوع"),
    { a ->
        a.ask("إضافة تصنيف", listOf("اسم التصنيف")) { values ->
            val name = values.firstOrNull()?.trim().orEmpty()
            if (name.isBlank()) {
                a.toast("اسم التصنيف مطلوب")
            } else {
                a.lifecycleScope.launch {
                    try {
                        val dao = a.app.db.accountCategories()
                        require(dao.byName(name) == null) { "اسم التصنيف موجود مسبقاً" }
                        dao.insert(AccountCategoryE(name = name, sortOrder = dao.all().size, isSystem = false))
                        a.refresh()
                    } catch (e: Exception) {
                        a.toast(e.message ?: "تعذر إضافة التصنيف")
                    }
                }
            }
        }
    }
) {
    override suspend fun load(a: MainActivity): List<Row> =
        a.app.db.accountCategories().all().map { category ->
            Row(
                listOf(category.name, if (category.enabled) "مفعّل" else "معطّل", if (category.isSystem) "أساسي" else "مخصص")
            ) { a.push(CategoryAccountsScreen(category.id)) }
        }
}

class CategoryAccountsScreen(private val categoryId: Long) : ListScreen(
    "حسابات التصنيف",
    listOf("الحساب", "رقم الحساب", "إجراء")
) {
    override fun actions(a: MainActivity): List<Pair<String, () -> Unit>> = listOf(
        "+" to { addAccountToCategory(a) },
        "⋯" to { manageCategory(a) },
    )

    override suspend fun load(a: MainActivity): List<Row> {
        val dao = a.app.db.accountCategories()
        dao.byId(categoryId) ?: return emptyList()
        return dao.accountsForCategory(categoryId).map { account ->
            Row(listOf(account.name, account.id.toString(), "إزالة")) {
                AlertDialog.Builder(a)
                    .setTitle("إزالة الحساب من التصنيف")
                    .setMessage("سيبقى الحساب وقيوده المحاسبية دون تغيير. إزالة الربط فقط؟")
                    .setPositiveButton("إزالة") { _, _ ->
                        a.lifecycleScope.launch {
                            dao.unlink(account.id, categoryId)
                            a.refresh()
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
    }

    private fun addAccountToCategory(a: MainActivity) {
        a.lifecycleScope.launch {
            try {
                val dao = a.app.db.accountCategories()
                val category = dao.byId(categoryId) ?: throw IllegalArgumentException("التصنيف غير موجود")
                val linked = dao.accountIdsForCategory(categoryId).toSet()
                val candidates = a.app.db.accounts().all().filter { !it.isSystem && it.id !in linked }
                if (candidates.isEmpty()) {
                    a.toast("لا توجد حسابات متاحة للإضافة")
                } else {
                    AlertDialog.Builder(a)
                        .setTitle("إضافة حساب إلى " + category.name)
                        .setItems(candidates.map { it.name }.toTypedArray()) { _, index ->
                            a.lifecycleScope.launch {
                                try {
                                    dao.link(AccountCategoryLinkE(accountId = candidates[index].id, categoryId = categoryId))
                                    a.refresh()
                                } catch (e: Exception) {
                                    a.toast(e.message ?: "تعذر ربط الحساب")
                                }
                            }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            } catch (e: Exception) {
                a.toast(e.message ?: "تعذر تحميل الحسابات")
            }
        }
    }

    private fun manageCategory(a: MainActivity) {
        a.lifecycleScope.launch {
            val dao = a.app.db.accountCategories()
            val category = dao.byId(categoryId) ?: return@launch a.toast("التصنيف غير موجود")
            val options = if (category.isSystem) {
                listOf(if (category.enabled) "تعطيل التصنيف" else "تفعيل التصنيف")
            } else {
                listOf("تعديل اسم التصنيف", if (category.enabled) "تعطيل التصنيف" else "تفعيل التصنيف", "حذف التصنيف")
            }
            AlertDialog.Builder(a)
                .setTitle(category.name)
                .setItems(options.toTypedArray()) { _, index ->
                    when (options[index]) {
                        "تعديل اسم التصنيف" -> a.ask("تعديل اسم التصنيف", listOf("الاسم الجديد")) { values ->
                            val name = values.firstOrNull()?.trim().orEmpty()
                            if (name.isBlank()) return@ask a.toast("اسم التصنيف مطلوب")
                            a.lifecycleScope.launch {
                                try {
                                    require(dao.byName(name) == null || name == category.name) { "الاسم مستخدم بالفعل" }
                                    dao.update(category.copy(name = name))
                                    a.refresh()
                                } catch (e: Exception) {
                                    a.toast(e.message ?: "تعذر تعديل التصنيف")
                                }
                            }
                        }
                        "تعطيل التصنيف", "تفعيل التصنيف" -> a.lifecycleScope.launch {
                            dao.setEnabled(categoryId, !category.enabled)
                            a.refresh()
                        }
                        "حذف التصنيف" -> AlertDialog.Builder(a)
                            .setTitle("حذف التصنيف")
                            .setMessage("سيُحذف التصنيف وروابطه فقط، ولن تُحذف الحسابات أو القيود.")
                            .setPositiveButton("حذف") { _, _ ->
                                a.lifecycleScope.launch {
                                    val deleted = dao.deleteCustom(categoryId)
                                    if (deleted > 0) a.back() else a.toast("لا يمكن حذف هذا التصنيف")
                                }
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}
