package com.goldenaccountant.data

import androidx.room.*

/** كل المبالغ مخزنة كعدد صحيح = القيمة × 10000 (انظر Money.kt). */

@Entity(tableName = "account_groups", indices = [Index("parentId")])
data class AccountGroupE(@PrimaryKey val id: Long, val name: String, val parentId: Long)

@Entity(
    tableName = "accounts",
    foreignKeys = [ForeignKey(AccountGroupE::class, ["id"], ["groupId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("groupId"), Index(value = ["name"], unique = true)],
)
data class AccountE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupId: Long,
    val isSystem: Boolean = false,
    val phone: String? = null,
    val creditLimitMicro: Long = 0,
)

@Entity(tableName = "journal_entries", indices = [Index("date"), Index("refType", "refId")])
data class JournalEntryE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val memo: String,
    val refType: String? = null,
    val refId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "journal_lines",
    foreignKeys = [
        ForeignKey(JournalEntryE::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AccountE::class, ["id"], ["accountId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("entryId"), Index("accountId")],
)
data class JournalLineE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val accountId: Long,
    val debitMicro: Long,
    val creditMicro: Long,
    val currencyId: Long = 0,
)

@Entity(tableName = "warehouses")
data class WarehouseE(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "items", indices = [Index(value = ["name"], unique = true), Index("barcode")])
data class ItemE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val barcode: String? = null,
    val isService: Boolean = false,
    val unit: String = "حبة",
    val salePriceMicro: Long = 0,
    val minStockMicro: Long = 0,
)

/** حركة مخزنية: الكمية موجبة للتوريد وسالبة للصرف. avgCostMicro = متوسط التكلفة بعد الحركة. */
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(ItemE::class, ["id"], ["itemId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(WarehouseE::class, ["id"], ["warehouseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("itemId", "warehouseId"), Index("date")],
)
data class StockMovementE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val warehouseId: Long,
    val date: String,
    val kind: String,
    val qtyMicro: Long,
    val unitCostMicro: Long,
    val avgCostMicro: Long,
    val refType: String? = null,
    val refId: Long? = null,
)

@Entity(tableName = "currencies")
data class CurrencyE(@PrimaryKey val id: Long, val name: String, val code: String, val rateMicro: Long)

@Entity(tableName = "settings")
data class SettingE(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "invoices", indices = [Index("kind", "date"), Index("partyAccountId")])
data class InvoiceE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val number: Int,
    val isReturn: Boolean,
    val isCash: Boolean,
    val partyAccountId: Long,
    val partyName: String,
    val warehouseId: Long,
    val date: String,
    val memo: String,
    val discountMicro: Long,
    val netMicro: Long,
    val taxMicro: Long,
    val feesMicro: Long,
    val totalMicro: Long,
    val paidMicro: Long,
)

@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(InvoiceE::class, ["id"], ["invoiceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ItemE::class, ["id"], ["itemId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("invoiceId"), Index("itemId")],
)
data class InvoiceLineE(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val itemId: Long,
    val qtyMicro: Long,
    val priceMicro: Long,
)
