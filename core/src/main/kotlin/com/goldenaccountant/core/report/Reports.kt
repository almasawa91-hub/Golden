package com.goldenaccountant.core.report

import com.goldenaccountant.core.accounting.*
import java.math.BigDecimal

data class AccountInfo(val id: Long, val name: String, val groupId: Long) {
    /** الجذر: 1 أصول، 2 التزامات وحقوق ملكية، 3 مصروفات، 4 إيرادات. */
    val root: Char get() = groupId.toString().first()
}
data class ReportRow(val name: String, val amount: BigDecimal)
data class TrialRow(val name: String, val debit: BigDecimal, val credit: BigDecimal)
data class TrialBalance(val rows: List<TrialRow>, val totalDebit: BigDecimal, val totalCredit: BigDecimal) {
    val isBalanced: Boolean get() = totalDebit.r().compareTo(totalCredit.r()) == 0
}
data class IncomeStatement(
    val revenues: List<ReportRow>, val expenses: List<ReportRow>,
    val totalRevenue: BigDecimal, val totalExpense: BigDecimal, val netProfit: BigDecimal,
)
data class BalanceSheet(
    val assets: List<ReportRow>, val liabilities: List<ReportRow>, val profit: BigDecimal,
    val totalAssets: BigDecimal, val totalLiabilitiesEquity: BigDecimal,
) {
    val isBalanced: Boolean get() = totalAssets.r().compareTo(totalLiabilitiesEquity.r()) == 0
}
data class StatementLine(val date: String, val memo: String, val debit: BigDecimal, val credit: BigDecimal)
data class StatementRow(val date: String, val memo: String, val debit: BigDecimal, val credit: BigDecimal, val balance: BigDecimal)

/** كل التقارير تُحسب من صافي حركة القيود (مدين - دائن) لكل حساب، لا من أرقام مخزنة. */
object Reports {
    private fun nz(v: BigDecimal) = v.r().signum() != 0

    fun netsOf(lines: List<JournalLine>): Map<Long, BigDecimal> =
        lines.groupBy { it.accountId }.mapValues { (_, ls) -> ls.fold(ZERO) { a, l -> a + l.debit - l.credit } }

    fun trialBalance(accounts: List<AccountInfo>, nets: Map<Long, BigDecimal>): TrialBalance {
        val rows = accounts.mapNotNull { a ->
            val n = nets[a.id] ?: ZERO
            if (!nz(n)) null
            else TrialRow(a.name, if (n.signum() > 0) n.r() else ZERO, if (n.signum() < 0) n.negate().r() else ZERO)
        }
        return TrialBalance(rows, rows.fold(ZERO) { x, r -> x + r.debit }, rows.fold(ZERO) { x, r -> x + r.credit })
    }

    fun incomeStatement(accounts: List<AccountInfo>, nets: Map<Long, BigDecimal>): IncomeStatement {
        val rev = accounts.filter { it.root == (4).digitToChar() }.mapNotNull { a ->
            val v = (nets[a.id] ?: ZERO).negate(); if (nz(v)) ReportRow(a.name, v.r()) else null
        }
        val exp = accounts.filter { it.root == (3).digitToChar() }.mapNotNull { a ->
            val v = nets[a.id] ?: ZERO; if (nz(v)) ReportRow(a.name, v.r()) else null
        }
        val tr = rev.fold(ZERO) { x, r -> x + r.amount }
        val te = exp.fold(ZERO) { x, r -> x + r.amount }
        return IncomeStatement(rev, exp, tr, te, (tr - te).r())
    }

    fun balanceSheet(accounts: List<AccountInfo>, nets: Map<Long, BigDecimal>): BalanceSheet {
        val assets = accounts.filter { it.root == (1).digitToChar() }.mapNotNull { a ->
            val v = nets[a.id] ?: ZERO; if (nz(v)) ReportRow(a.name, v.r()) else null
        }
        val liab = accounts.filter { it.root == (2).digitToChar() }.mapNotNull { a ->
            val v = (nets[a.id] ?: ZERO).negate(); if (nz(v)) ReportRow(a.name, v.r()) else null
        }
        val profit = incomeStatement(accounts, nets).netProfit
        val ta = assets.fold(ZERO) { x, r -> x + r.amount }
        val tl = liab.fold(ZERO) { x, r -> x + r.amount } + profit
        return BalanceSheet(assets, liab, profit, ta.r(), tl.r())
    }

    /** كشف حساب برصيد جارٍ؛ opening = صافي (مدين - دائن) قبل بداية الفترة. */
    fun statement(opening: BigDecimal, lines: List<StatementLine>, debitNature: Boolean): List<StatementRow> {
        var acc = if (debitNature) opening else opening.negate()
        val out = ArrayList<StatementRow>()
        if (nz(opening)) out += StatementRow("", "رصيد سابق", ZERO, ZERO, acc.r())
        for (l in lines) {
            acc += if (debitNature) l.debit - l.credit else l.credit - l.debit
            out += StatementRow(l.date, l.memo, l.debit, l.credit, acc.r())
        }
        return out
    }
}
