package com.goldenaccountant.data

import androidx.room.withTransaction
import com.goldenaccountant.core.accounting.*
import com.goldenaccountant.core.inventory.StockState
import com.goldenaccountant.core.invoice.*
import java.math.BigDecimal

data class DraftLine(val itemName: String, val qty: BigDecimal, val price: BigDecimal)

data class InvoiceDraft(
    val kind: InvoiceKind,
    val isReturn: Boolean,
    val isCash: Boolean,
    val partyName: String,
    val warehouseId: Long = 1,
    val date: String,
    val memo: String = "",
    val lines: List<DraftLine>,
    val discount: BigDecimal = ZERO,
    val discountIsPercent: Boolean = false,
    val fees: BigDecimal = ZERO,
    val paid: BigDecimal = ZERO,
)

class InvoiceRepository(private val db: AppDatabase) {

    private suspend fun settings(): InvoiceSettings {
        val s = db.settings()
        val vatOn = s.get("vat_on") == "1"
        return InvoiceSettings(
            allowNegativeStock = s.get("allow_negative") == "1",
            taxRatePercent = if (vatOn) BigDecimal(s.get("vat_rate") ?: "0") else ZERO,
            taxInclusive = s.get("vat_inclusive") == "1",
        )
    }

    /** ينشئ الطرف والأصناف عند الحاجة ثم يرحّل الفاتورة والقيد وحركات المخزون في Transaction واحدة. */
    suspend fun post(d: InvoiceDraft): Long = db.withTransaction {
        val sale = d.kind == InvoiceKind.SALE
        val partyName = d.partyName.trim().ifEmpty { if (sale) "عميل نقدي" else "مورد نقدي" }
        val party = db.accounts().byName(partyName)
            ?: AccountE(name = partyName, groupId = if (sale) 123 else 221).let { it.copy(id = db.accounts().insert(it)) }

        val itemIds = LinkedHashMap<String, Long>()
        for (l in d.lines) {
            val name = l.itemName.trim()
            require(name.isNotEmpty()) { "اسم الصنف مطلوب" }
            itemIds.getOrPut(name) {
                db.items().byName(name)?.id ?: db.items().insert(ItemE(name = name, salePriceMicro = if (sale) l.price.toMicro() else 0))
            }
        }
        val store = InMemoryStockStore()
        for (id in itemIds.values) {
            val qty = db.stock().qtyMicro(id, d.warehouseId).fromMicro()
            val avg = (db.stock().lastAvgCostMicro(id) ?: 0L).fromMicro()
            store.put(id, d.warehouseId, StockState(qty, avg))
        }
        val req = InvoiceRequest(
            d.kind, d.isReturn, d.isCash, party.id, d.warehouseId, d.date, d.memo.ifEmpty { "${if (sale) "بيع" else "شراء"} - $partyName" },
            lines = d.lines.map { InvoiceLine(itemIds.getValue(it.itemName.trim()), it.qty, it.price) },
            discount = d.discount, discountIsPercent = d.discountIsPercent, fees = d.fees, paid = d.paid,
        )
        val res = InvoiceService(store, DEFAULT_ACCOUNTS, settings()).process(req)

        val kind = d.kind.name
        val invoiceId = db.invoices().insert(
            InvoiceE(
                kind = kind, number = db.invoices().count(kind) + 1, isReturn = d.isReturn, isCash = d.isCash,
                partyAccountId = party.id, partyName = partyName, warehouseId = d.warehouseId, date = d.date, memo = req.memo,
                discountMicro = res.totals.discount.toMicro(), netMicro = res.totals.net.toMicro(), taxMicro = res.totals.tax.toMicro(),
                feesMicro = res.totals.fees.toMicro(), totalMicro = res.totals.total.toMicro(), paidMicro = d.paid.toMicro(),
            ),
        )
        db.invoices().insertLines(d.lines.map { DraftLineE(invoiceId, itemIds.getValue(it.itemName.trim()), it) })
        db.journal().post(res.entry, "invoice", invoiceId)
        for (m in res.moves) {
            db.stock().insert(
                StockMovementE(
                    itemId = m.itemId, warehouseId = m.warehouseId, date = d.date,
                    kind = if (m.qty.signum() > 0) "IN" else "OUT",
                    qtyMicro = m.qty.toMicro(), unitCostMicro = m.unitCost.toMicro(), avgCostMicro = m.avgAfter.toMicro(),
                    refType = "invoice", refId = invoiceId,
                ),
            )
        }
        invoiceId
    }

    private fun DraftLineE(invoiceId: Long, itemId: Long, l: DraftLine) =
        InvoiceLineE(invoiceId = invoiceId, itemId = itemId, qtyMicro = l.qty.toMicro(), priceMicro = l.price.toMicro())
}
