package com.goldenaccountant.core.invoice

import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.inventory.*
import java.math.BigDecimal

data class InvoiceSettings(
    val allowNegativeStock: Boolean = false,
    val taxRatePercent: BigDecimal = ZERO,
    val taxInclusive: Boolean = false,
)

data class InvoiceRequest(
    val kind: InvoiceKind,
    val isReturn: Boolean,
    val isCash: Boolean,
    val partyAccountId: Long,
    val warehouseId: Long,
    val date: String,
    val memo: String,
    val lines: List<InvoiceLine>,
    val discount: BigDecimal = ZERO,
    val discountIsPercent: Boolean = false,
    val fees: BigDecimal = ZERO,
    val paid: BigDecimal = ZERO,
    val exchangeRate: BigDecimal = BigDecimal.ONE,
    val serviceItemIds: Set<Long> = emptySet(),
)

/** qty موجبة للتوريد وسالبة للصرف. */
data class StockMove(val itemId: Long, val warehouseId: Long, val qty: BigDecimal, val unitCost: BigDecimal, val avgAfter: BigDecimal)

data class InvoiceResult(val totals: Totals, val entry: JournalEntry, val moves: List<StockMove>)

interface StockStore {
    fun get(itemId: Long, warehouseId: Long): StockState
    fun put(itemId: Long, warehouseId: Long, state: StockState)
}

class InMemoryStockStore : StockStore {
    private val m = HashMap<Pair<Long, Long>, StockState>()
    override fun get(itemId: Long, warehouseId: Long): StockState = m[itemId to warehouseId] ?: StockState()
    override fun put(itemId: Long, warehouseId: Long, state: StockState) { m[itemId to warehouseId] = state }
}

/**
 * معالجة فاتورة كاملة: حساب الإجماليات، تحديث المخزون بالمتوسط المتحرك، وتوليد القيد المتوازن.
 * لا يُكتب شيء في المخزن إلا بعد نجاح كل الأسطر (كل شيء أو لا شيء).
 */
class InvoiceService(
    private val stock: StockStore,
    private val ids: SystemAccounts = DEFAULT_ACCOUNTS,
    private val settings: InvoiceSettings = InvoiceSettings(),
) {
    fun process(req: InvoiceRequest): InvoiceResult {
        require(req.lines.isNotEmpty()) { "الفاتورة بلا أصناف" }
        require(req.lines.all { it.qty.signum() > 0 && it.unitPrice.signum() >= 0 }) { "كمية أو سعر غير صحيح" }
        val rate = req.exchangeRate
        val local = req.lines.map { it.copy(unitPrice = (it.unitPrice * rate).r()) }
        val totals = InvoiceMath.totals(
            local,
            discount = if (req.discountIsPercent) req.discount else (req.discount * rate).r(),
            discountIsPercent = req.discountIsPercent,
            taxRatePercent = settings.taxRatePercent,
            taxInclusive = settings.taxInclusive,
            fees = (req.fees * rate).r(),
        )
        val work = HashMap<Long, StockState>()
        val moves = ArrayList<StockMove>()
        var cogs = ZERO
        val sale = req.kind == InvoiceKind.SALE
        for (l in local) {
            if (l.itemId in req.serviceItemIds) continue
            val cur = work[l.itemId] ?: stock.get(l.itemId, req.warehouseId)
            val next: StockState = when {
                sale && !req.isReturn -> {
                    val (s, cost) = MovingAverage.issue(cur, l.qty, settings.allowNegativeStock)
                    cogs += cost
                    moves += StockMove(l.itemId, req.warehouseId, l.qty.negate(), cur.avgCost, s.avgCost)
                    s
                }
                sale -> {
                    val s = MovingAverage.receive(cur, l.qty, cur.avgCost)
                    cogs += (l.qty * cur.avgCost).r()
                    moves += StockMove(l.itemId, req.warehouseId, l.qty, cur.avgCost, s.avgCost)
                    s
                }
                !req.isReturn -> {
                    val s = MovingAverage.receive(cur, l.qty, l.unitPrice)
                    moves += StockMove(l.itemId, req.warehouseId, l.qty, l.unitPrice, s.avgCost)
                    s
                }
                else -> {
                    val (s, _) = MovingAverage.issue(cur, l.qty, settings.allowNegativeStock)
                    moves += StockMove(l.itemId, req.warehouseId, l.qty.negate(), cur.avgCost, s.avgCost)
                    s
                }
            }
            work[l.itemId] = next
        }
        val entry = PostingEngine.invoice(
            req.kind, req.isReturn, req.isCash, req.partyAccountId, totals, ids, req.date, req.memo,
            paid = (req.paid * rate).r(), costOfGoods = if (sale) cogs else ZERO,
        )
        work.forEach { (item, st) -> stock.put(item, req.warehouseId, st) }
        return InvoiceResult(totals, entry, moves)
    }
}
