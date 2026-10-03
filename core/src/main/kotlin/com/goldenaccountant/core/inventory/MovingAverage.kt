package com.goldenaccountant.core.inventory

import com.goldenaccountant.core.accounting.ZERO
import com.goldenaccountant.core.accounting.r
import java.math.BigDecimal
import java.math.RoundingMode

class InsufficientStockException(message: String) : IllegalStateException(message)

data class StockState(val qty: BigDecimal = ZERO, val avgCost: BigDecimal = ZERO) {
    val value: BigDecimal get() = (qty * avgCost).r()
}

/** المتوسط المتحرك المرجّح للتكلفة. */
object MovingAverage {
    fun receive(s: StockState, qty: BigDecimal, unitCost: BigDecimal): StockState {
        require(qty.signum() > 0) { "الكمية يجب أن تكون موجبة" }
        require(unitCost.signum() >= 0) { "التكلفة لا يمكن أن تكون سالبة" }
        val newQty = s.qty + qty
        val avg = if (s.qty.signum() <= 0) unitCost
        else (s.qty * s.avgCost + qty * unitCost).divide(newQty, 4, RoundingMode.HALF_UP)
        return StockState(newQty, avg.r())
    }

    /** يعيد الحالة الجديدة وتكلفة البضاعة المصروفة. */
    fun issue(s: StockState, qty: BigDecimal, allowNegative: Boolean = false): Pair<StockState, BigDecimal> {
        require(qty.signum() > 0) { "الكمية يجب أن تكون موجبة" }
        if (!allowNegative && qty > s.qty) throw InsufficientStockException("الكمية غير كافية: المتوفر ${s.qty} المطلوب $qty")
        return StockState(s.qty - qty, s.avgCost) to (qty * s.avgCost).r()
    }
}
