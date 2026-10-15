package com.goldenaccountant.app

import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.core.chart.ChartOfAccounts
import com.goldenaccountant.data.FROM_ALL
import com.goldenaccountant.data.TO_ALL
import kotlinx.coroutines.launch

private val TYPES = listOf("عميل" to 123L, "مورد" to 221L, "صندوق" to 121L, "بنك" to 122L, "مصروف" to 322L, "إيراد" to 411L, "أخرى" to 124L)

class AccountsScreen : ListScreen("الحسابات", listOf("الحساب", "النوع", "الرصيد"), { a ->
    a.choose("نوع الحساب", TYPES.map { it.first }) { i ->
        a.ask("إضافة حساب", listOf("اسم الحساب", "الهاتف")) { v ->
            a.lifecycleScope.launch {
                try { a.app.accounting.addAccount(v[0], TYPES[i].second, v[1]); a.refresh() }
                catch (e: Exception) { a.toast(e.message ?: "تعذر الحفظ") }
            }
        }
    }
}) {
    private var categoryId: Long? = null
    private var categoryName: String = "كل الحسابات"

    override fun actions(a: MainActivity): List<Pair<String, () -> Unit>> = listOf(
        categoryName to {
            a.lifecycleScope.launch {
                try {
                    val categories = a.app.db.accountCategories().all().filter { it.enabled }
                    val labels = listOf("كل الحسابات") + categories.map { it.name }
                    a.choose("تصفية الحسابات حسب التصنيف", labels) { index ->
                        if (index == 0) {
                            categoryId = null
                            categoryName = "كل الحسابات"
                            a.refresh()
                        } else {
                            categoryId = categories[index - 1].id
                            categoryName = categories[index - 1].name
                            a.refresh()
                        }
                    }
                } catch (e: Exception) { a.toast(e.message ?: "تعذر تحميل التصنيفات") }
            }
        },
    )

    override suspend fun load(a: MainActivity): List<Row> {
        val balances = a.app.accounting.balances()
        val category = categoryId
        val selectedIds = category?.let { a.app.db.accountCategories().accountIdsForCategory(it).toSet() }
        return balances.filter { selectedIds == null || it.account.id in selectedIds }.map { b ->
            val type = a.app.db.openHelper.readableDatabase.query(
                "SELECT name FROM account_groups WHERE id = ? LIMIT 1", arrayOf(b.account.groupId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else "غير مصنف" }
            Row(listOf(b.account.name, type, fmt(b.balance))) { a.push(StatementScreen(b.account.id)) }
        }
    }
}


class StatementAccountPickerScreen : ListScreen("اختيار حساب للكشف", listOf("الحساب", "النوع", "الرصيد")) {
    override suspend fun load(a: MainActivity): List<Row> {
        return a.app.accounting.balances().map { b ->
            val type = a.app.db.openHelper.readableDatabase.query(
                "SELECT name FROM account_groups WHERE id = ? LIMIT 1", arrayOf(b.account.groupId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else "غير مصنف" }
            Row(listOf(b.account.name, type, fmt(b.balance))) { a.push(StatementScreen(b.account.id)) }
        }
    }
}

class StatementScreen(private val accountId: Long) : ListScreen("كشف حساب", listOf("التاريخ", "البيان", "مدين", "دائن", "الرصيد")) {
    private var fromDate = FROM_ALL
    private var toDate = TO_ALL
    override fun actions(a: MainActivity) = listOf(
        "الفترة" to { a.ask("تصفية كشف الحساب", listOf("من تاريخ YYYY-MM-DD", "إلى تاريخ YYYY-MM-DD")) { v ->
            val from = v.getOrNull(0)?.trim().orEmpty()
            val to = v.getOrNull(1)?.trim().orEmpty()
            if (!isValidIsoDate(from) || !isValidIsoDate(to) || from > to)
                a.toast("أدخل تاريخين صحيحين وبداية لا تتجاوز النهاية")
            else { fromDate = from; toDate = to; a.refresh() }
        } },
        "كل الفترة" to { fromDate = FROM_ALL; toDate = TO_ALL; a.refresh() },
    )
    override suspend fun load(a: MainActivity) = a.app.accounting.statement(accountId, fromDate, toDate).rows.map {
        Row(listOf(it.date, it.memo, fmt(it.debit), fmt(it.credit), fmt(it.balance)))
    }
}

class ChartScreen : ListScreen("الدليل المحاسبي", listOf("المجموعة", "الرقم", "الأب")) {
    override suspend fun load(a: MainActivity): List<Row> {
        val groups = a.app.db.openHelper.readableDatabase.query("SELECT id,name,parentId FROM account_groups ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(ChartRow(c.getLong(0), c.getString(1), c.getLong(2))) }
        }
        val byId = groups.associateBy { it.id }
        fun depth(id: Long, seen: Set<Long> = emptySet()): Int {
            if (id in seen) return 0
            val group = byId[id] ?: return 0
            return if (group.parentId == 0L) 0 else 1 + depth(group.parentId, seen + id)
        }
        return groups.sortedBy { it.id.toString().padEnd(6, 48.toChar()) }.map {
            Row(listOf("  ".repeat(depth(it.id)) + it.name, it.id.toString(), byId[it.parentId]?.name ?: "رئيسي"))
        }
    }
    private data class ChartRow(val id: Long, val name: String, val parentId: Long)
}
