package com.goldenaccountant.core.accounting

import java.math.BigDecimal
import java.math.RoundingMode

val ZERO: BigDecimal = BigDecimal.ZERO

/** تقريب إلى 4 خانات عشرية. */
fun BigDecimal.r(): BigDecimal = setScale(4, RoundingMode.HALF_UP)

/** التخزين في قاعدة البيانات كعدد صحيح (القيمة × 10000) ليعمل SUM في SQL بدقة. */
fun BigDecimal.toMicro(): Long = r().movePointRight(4).longValueExact()
fun Long.fromMicro(): BigDecimal = BigDecimal.valueOf(this, 4)
