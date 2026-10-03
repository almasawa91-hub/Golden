package com.goldenaccountant.app

import androidx.lifecycle.lifecycleScope
import com.goldenaccountant.core.chart.ChartOfAccounts
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
    override suspend fun load(a: MainActivity) = a.app.accounting.balances().map { b ->
        val type = ChartOfAccounts.groups.firstOrNull { it.id == b.account.groupId }?.name ?: ""
        Row(listOf(b.account.name, type, fmt(b.balance))) { a.push(StatementScreen(b.account.id)) }
    }
}

class StatementScreen(private val accountId: Long) : ListScreen("كشف حساب", listOf("التاريخ", "البيان", "مدين", "دائن", "الرصيد")) {
    override suspend fun load(a: MainActivity) = a.app.accounting.statement(accountId).rows.map {
        Row(listOf(it.date, it.memo, fmt(it.debit), fmt(it.credit), fmt(it.balance)))
    }
}

class ChartScreen : ListScreen("الدليل المحاسبي", listOf("المجموعة", "الرقم", "الأب")) {
    override suspend fun load(a: MainActivity): List<Row> {
        val byId = ChartOfAccounts.groups.associateBy { it.id }
        fun depth(id: Long): Int = byId[id]?.let { g -> if (g.parentId == 0L) 0 else 1 + depth(g.parentId) } ?: 0
        return ChartOfAccounts.groups.sortedBy { it.id.toString().padEnd(4, 48.toChar()) }.map {
            Row(listOf("  ".repeat(depth(it.id)) + it.name, it.id.toString(), byId[it.parentId]?.name ?: "رئيسي"))
        }
    }
}
