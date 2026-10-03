package com.goldenaccountant.core.accounting

import java.math.BigDecimal

class UnbalancedEntryException(message: String) : IllegalArgumentException(message)

data class JournalLine(
    val accountId: Long,
    val debit: BigDecimal = ZERO,
    val credit: BigDecimal = ZERO,
) {
    init {
        require(debit.signum() >= 0 && credit.signum() >= 0) { "المبالغ يجب ألا تكون سالبة" }
        require(!(debit.signum() > 0 && credit.signum() > 0)) { "السطر إما مدين أو دائن" }
    }
}

/** قيد محاسبي: لا يمكن إنشاؤه إلا إذا كان متوازنا (مجموع المدين = مجموع الدائن). */
data class JournalEntry(val date: String, val memo: String, val lines: List<JournalLine>) {
    val totalDebit: BigDecimal get() = lines.fold(ZERO) { a, l -> a + l.debit }
    val totalCredit: BigDecimal get() = lines.fold(ZERO) { a, l -> a + l.credit }

    init {
        if (lines.size < 2) throw UnbalancedEntryException("القيد يحتاج سطرين على الأقل")
        if (totalDebit.r().compareTo(totalCredit.r()) != 0)
            throw UnbalancedEntryException("القيد غير متوازن: مدين ${totalDebit.r()} دائن ${totalCredit.r()}")
    }
}

object Ledger {
    /** الرصيد يُعاد حسابه دائما من القيود. debitNature=true للأصول والمصروفات. */
    fun balance(lines: List<JournalLine>, accountId: Long, debitNature: Boolean): BigDecimal {
        val net = lines.filter { it.accountId == accountId }.fold(ZERO) { a, l -> a + l.debit - l.credit }
        return if (debitNature) net else net.negate()
    }
}
