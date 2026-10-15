package com.goldenaccountant.core.accounting

import java.math.BigDecimal

data class InvoiceLine(val itemId: Long, val qty: BigDecimal, val unitPrice: BigDecimal) {
    init {
        require(itemId > 0) { "معرّف الصنف غير صحيح" }
        require(qty.signum() > 0) { "كمية الصنف يجب أن تكون أكبر من صفر" }
        require(unitPrice.signum() >= 0) { "سعر الوحدة لا يمكن أن يكون سالباً" }
    }
}

/** net = قيمة السلع بدون ضريبة بعد الخصم، total = ما يدفعه الطرف الآخر. */
data class Totals(
    val gross: BigDecimal,
    val discount: BigDecimal,
    val net: BigDecimal,
    val tax: BigDecimal,
    val fees: BigDecimal,
    val total: BigDecimal,
)

object InvoiceMath {
    private val HUNDRED = BigDecimal(100)

    fun totals(
        lines: List<InvoiceLine>,
        discount: BigDecimal = ZERO,
        discountIsPercent: Boolean = false,
        taxRatePercent: BigDecimal = ZERO,
        taxInclusive: Boolean = false,
        fees: BigDecimal = ZERO,
    ): Totals {
        require(lines.isNotEmpty()) { "الفاتورة يجب أن تحتوي على صنف واحد على الأقل" }
        require(discount.signum() >= 0) { "الخصم لا يمكن أن يكون سالباً" }
        require(!discountIsPercent || discount <= HUNDRED) { "نسبة الخصم يجب أن تكون بين 0 و100" }
        require(taxRatePercent.signum() >= 0 && taxRatePercent <= BigDecimal("100")) { "نسبة الضريبة يجب أن تكون بين 0 و100" }
        require(fees.signum() >= 0) { "الرسوم لا يمكن أن تكون سالبة" }
        val gross = lines.fold(ZERO) { a, l -> a + l.qty * l.unitPrice }.r()
        val disc = (if (discountIsPercent) gross * discount / HUNDRED else discount).r()
        require(disc <= gross) { "الخصم أكبر من الإجمالي" }
        val base = gross - disc
        val tax: BigDecimal
        val net: BigDecimal
        val total: BigDecimal
        if (taxInclusive) {
            tax = (base * taxRatePercent).divide(HUNDRED + taxRatePercent, 4, java.math.RoundingMode.HALF_UP)
            net = base - tax
            total = base + fees
        } else {
            tax = (base * taxRatePercent / HUNDRED).r()
            net = base
            total = base + tax + fees
        }
        return Totals(gross, disc, net.r(), tax.r(), fees.r(), total.r())
    }
}
