package com.goldenaccountant.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import com.goldenaccountant.core.accounting.DEFAULT_ACCOUNTS
import com.goldenaccountant.core.accounting.InvoiceKind
import com.goldenaccountant.core.accounting.PostingEngine
import com.goldenaccountant.core.accounting.Totals

/**
 * Conservative importer for the legacy محاسب سوفت SQLite database.
 *
 * Master data is mapped into Golden's live tables. Legacy financial rows are preserved
 * byte-for-byte at the field-value level in legacy_import_payloads until their accounting
 * semantics can be reconciled; they are deliberately NOT posted as guessed journal entries.
 */
object LegacyDatabaseImporter {
    data class Result(
        val groupsAdded: Int,
        val accountsAdded: Int,
        val accountsMatched: Int,
        val itemsAdded: Int,
        val itemsMatched: Int,
        val currenciesAdded: Int,
        val stockBalancesAdded: Int,
        val invoicesAdded: Int,
        val voucherGroupsPosted: Int,
        val voucherGroupsForReview: Int,
        val manualTransactionsPosted: Int,
        val manualTransactionsForReview: Int,
        val archivedRowsAdded: Long,
        val archivedRowsAlreadyPresent: Long,
    )

    private val archiveTables = listOf(
        "account_tree", "customers", "transactions", "transactions_h", "bills",
        "bill_transactions", "items", "currency", "currency_price", "units", "item_type", "items_cost_calc", "items_closing_balance", "item_price", "item_price_history", "items_h", "bill_transactions_h", "bills_h",
    )

    fun import(sourceFile: File, target: SupportSQLiteDatabase): Result {
        require(sourceFile.isFile && sourceFile.length() > 0L) { "ملف المصدر غير موجود أو فارغ" }
        val source = SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val integrity = source.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else "no result"
            }
            require(integrity == "ok") { "قاعدة المصدر غير سليمة: $integrity" }
            val tables = mutableSetOf<String>()
            source.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            require(archiveTables.all { it in tables }) { "تنقص قاعدة المصدر جداول أساسية لازمة للاستيراد" }
            // Preserve every non-system source table, including secondary/history tables not yet
            // mapped into Golden's operational model.
            val sourceTables = tables.toList().sorted()

            target.execSQL("""CREATE TABLE IF NOT EXISTS legacy_import_map (
                sourceTable TEXT NOT NULL, sourceId TEXT NOT NULL, targetTable TEXT NOT NULL,
                targetId INTEGER, disposition TEXT NOT NULL,
                PRIMARY KEY(sourceTable, sourceId)
            )""")
            target.execSQL("""CREATE TABLE IF NOT EXISTS legacy_import_payloads (
                sourceTable TEXT NOT NULL, sourceId TEXT NOT NULL, payload TEXT NOT NULL,
                importedAt INTEGER NOT NULL, PRIMARY KEY(sourceTable, sourceId)
            )""")

            var groupsAdded = 0
            var accountsAdded = 0
            var accountsMatched = 0
            var itemsAdded = 0
            var itemsMatched = 0
            var currenciesAdded = 0
            var stockBalancesAdded = 0
            var invoicesAdded = 0
            var voucherGroupsPosted = 0
            var voucherGroupsForReview = 0
            var manualTransactionsPosted = 0
            var manualTransactionsForReview = 0
            var archivedAdded = 0L
            var archivedExisting = 0L

            target.beginTransaction()
            try {
                // Retain the legacy rows first, so source records remain recoverable even when
                // they cannot safely be posted to Golden's accounting engine.
                sourceTables.forEach { table ->
                    val before = scalar(target, "SELECT COUNT(*) FROM legacy_import_payloads WHERE sourceTable = ?", arrayOf(table))
                    val rows = archiveSourceTable(source, target, table)
                    val after = scalar(target, "SELECT COUNT(*) FROM legacy_import_payloads WHERE sourceTable = ?", arrayOf(table))
                    archivedAdded += (after - before).coerceAtLeast(0L)
                    archivedExisting += (rows - (after - before)).coerceAtLeast(0L)
                }

                // Add legacy account groups only when their IDs do not collide with Golden's chart.
                source.rawQuery("SELECT id, name, parent_id FROM account_tree ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)?.trim().orEmpty().ifEmpty { "مجموعة قديمة $id" }
                        val parent = if (c.isNull(2)) 0L else c.getLong(2)
                        val before = scalar(target, "SELECT COUNT(*) FROM account_groups WHERE id = ?", arrayOf(id))
                        target.execSQL("INSERT OR IGNORE INTO account_groups(id,name,parentId) VALUES(?,?,?)", arrayOf(id, name, parent))
                        if (before == 0L && scalar(target, "SELECT COUNT(*) FROM account_groups WHERE id = ?", arrayOf(id)) > 0L) groupsAdded++
                    }
                }

                val groupIds = mutableSetOf<Long>()
                target.query("SELECT id FROM account_groups").use { c -> while (c.moveToNext()) groupIds += c.getLong(0) }

                // Build a real category for each chart group. Keep cashboxes and banks separate,
                // and add ancestor links so "الأصول" also shows all its subgroups.
                val groupNames = mutableMapOf<Long, String>()
                val parentByGroup = mutableMapOf<Long, Long>()
                target.query("SELECT id,name,parentId FROM account_groups").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val sourceName = c.getString(1)?.trim().orEmpty().ifEmpty { "مجموعة $id" }
                        groupNames[id] = when (id) {
                            1L -> "الأصول"
                            2L -> "الالتزامات وحقوق الملكية"
                            3L -> "المصروفات"
                            4L -> "الإيرادات"
                            11L, 111L, 13L -> "الأصول الثابتة"
                            12L -> "الأصول المتداولة"
                            121L -> "الصناديق"
                            122L -> "البنوك"
                            123L -> "العملاء"
                            126L -> "العملاء (الثقه)"
                            21L -> "حقوق الملكية"
                            22L, 23L -> "الالتزامات"
                            31L, 32L -> "المصروفات"
                            41L, 42L -> "الإيرادات"
                            221L -> "الموردون"
                            else -> sourceName
                        }
                        parentByGroup[id] = if (c.isNull(2)) 0L else c.getLong(2)
                    }
                }
                val categoryByGroup = mutableMapOf<Long, Long>()
                groupNames.forEach { (groupId, groupName) ->
                    var categoryId = scalarNullable(target,
                        "SELECT id FROM account_categories WHERE name = ? COLLATE NOCASE LIMIT 1",
                        arrayOf(groupName))
                    if (categoryId != null) {
                        target.execSQL("UPDATE account_categories SET groupId=? WHERE id=? AND groupId IS NULL",
                            arrayOf(groupId, categoryId))
                    } else {
                        categoryId = scalarNullable(target,
                            "SELECT id FROM account_categories WHERE groupId=? AND name=? COLLATE NOCASE LIMIT 1",
                            arrayOf(groupId, groupName))
                        if (categoryId == null) {
                            target.execSQL(
                                "INSERT OR IGNORE INTO account_categories(name,groupId,color,icon,sortOrder,enabled,isSystem) VALUES(?,?, '#D4AF37','folder',?,1,1)",
                                arrayOf(groupName, groupId, groupId.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                            categoryId = scalarNullable(target,
                                "SELECT id FROM account_categories WHERE name = ? COLLATE NOCASE LIMIT 1",
                                arrayOf(groupName))
                        }
                    }
                    if (categoryId != null) categoryByGroup[groupId] = categoryId
                }
                // Ensure separate, colored categories before linking imported accounts.
                val categorySpecs = listOf(
                    Triple("العملاء", 123L, "#2563EB"), Triple("العملاء (الثقه)", 126L, "#0D9488"),
                    Triple("عملاء آجل", 123L, "#1D4ED8"), Triple("عملاء جملة", 123L, "#3B82F6"),
                    Triple("عملاء تجزئة", 123L, "#60A5FA"), Triple("الموردون", 221L, "#7C3AED"),
                    Triple("الصناديق", 121L, "#059669"), Triple("البنوك", 122L, "#0891B2"),
                    Triple("الأصول", 1L, "#D97706"), Triple("الأصول الثابتة", 11L, "#B45309"),
                    Triple("الأصول المتداولة", 12L, "#F59E0B"), Triple("الالتزامات", 22L, "#DC2626"),
                    Triple("حقوق الملكية", 21L, "#9333EA"), Triple("المصروفات", 322L, "#EA580C"),
                    Triple("الإيرادات", 411L, "#16A34A"), Triple("أخرى", 124L, "#64748B"),
                )
                categorySpecs.forEachIndexed { index, spec -> ensureCategory(target, spec.first, spec.second, spec.third, index) }
                target.execSQL("UPDATE account_categories SET enabled=0 WHERE name='الصناديق والبنوك'")

                source.rawQuery("SELECT id,name,gsm,acc_p_id,cus_type_id FROM customers ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val sourceId = c.getLong(0)
                        val name = c.getString(1)?.trim().orEmpty()
                        if (name.isEmpty()) {
                            putMap(target, "customers", sourceId.toString(), "accounts", null, "SKIPPED_EMPTY_NAME")
                            continue
                        }
                        val sourceGroup = if (c.isNull(3)) 0L else c.getLong(3)
                        val customerType = if (c.isNull(4)) 0L else c.getLong(4)
                        val preferredGroup = when (customerType) {
                            1L -> 221L; 4L -> 121L; 5L -> 322L; 6L -> 411L; 7L -> 124L; else -> 123L
                        }
                        val groupId = when {
                            sourceGroup in groupIds -> sourceGroup
                            preferredGroup in groupIds -> preferredGroup
                            123L in groupIds -> 123L
                            else -> groupIds.firstOrNull() ?: 1L
                        }
                        val phone = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() }
                        val existing = scalarNullable(target, "SELECT id FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        val targetId = if (existing != null) {
                            val isSystem = scalar(target, "SELECT isSystem FROM accounts WHERE id = ?", arrayOf(existing)) != 0L
                            if (!isSystem) target.execSQL(
                                "UPDATE accounts SET groupId=?, phone=COALESCE(NULLIF(?,''),phone) WHERE id=?",
                                arrayOf(groupId, phone, existing),
                            )
                            accountsMatched++
                            existing
                        } else {
                            target.execSQL("INSERT INTO accounts(name,groupId,isSystem,phone,creditLimitMicro) VALUES(?,?,0,?,0)", arrayOf(name, groupId, phone))
                            accountsAdded++
                            scalarNullable(target, "SELECT id FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        }
                        if (targetId != null) {
                            linkLegacyCategory(target, targetId, groupId, customerType)
                            var ancestorId = groupId
                            val seenAncestors = mutableSetOf<Long>()
                            while (ancestorId != 0L && seenAncestors.add(ancestorId)) {
                                categoryByGroup[ancestorId]?.let { categoryId ->
                                    target.execSQL("INSERT OR IGNORE INTO account_category_links(accountId,categoryId) VALUES(?,?)", arrayOf(targetId, categoryId))
                                }
                                ancestorId = parentByGroup[ancestorId] ?: 0L
                            }
                            putMap(target, "customers", sourceId.toString(), "accounts", targetId, if (existing != null) "MATCHED_NAME" else "INSERTED")
                        } else putMap(target, "customers", sourceId.toString(), "accounts", null, "SKIPPED_UNRESOLVED")
                    }
                }

                // Units and item types are stored in the raw archive. Golden has a text unit and
                // category, so use their names when constructing item master records.
                val unitNames = mutableMapOf<Long, String>()
                source.rawQuery("SELECT id,name FROM units", null).use { c ->
                    while (c.moveToNext()) unitNames[c.getLong(0)] = c.getString(1)?.trim().orEmpty()
                }
                val typeNames = mutableMapOf<Long, String>()
                source.rawQuery("SELECT id,name FROM item_type", null).use { c ->
                    while (c.moveToNext()) typeNames[c.getLong(0)] = c.getString(1)?.trim().orEmpty()
                }
                val baseCurrencyId = source.rawQuery("SELECT id FROM currency WHERE id=0 LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
                val latestBasePrices = mutableMapOf<Long, BigDecimal>()
                source.rawQuery("""SELECT p.item_id, p.SLS_U_PRICE FROM item_price p
                    JOIN (SELECT item_id, MAX(date_) AS max_date FROM item_price WHERE curr_id = 0 AND SLS_U_PRICE > 0 GROUP BY item_id) x
                    ON x.item_id=p.item_id AND x.max_date=p.date_ WHERE p.curr_id = 0 AND p.SLS_U_PRICE > 0""", null).use { c ->
                    while (c.moveToNext()) {
                        val price = c.getString(1)?.toBigDecimalOrNull()
                        if (price != null) latestBasePrices[c.getLong(0)] = price
                    }
                }
                // Fallback for items whose current master price is zero and have no item_price row:
                // use the most recent positive base-currency selling price recorded on an invoice line.
                source.rawQuery("""SELECT bt.item_id, bt.sls_u_price
                    FROM bill_transactions bt
                    JOIN bills b ON b.id=bt.bill_id
                    JOIN (
                        SELECT bt2.item_id, MAX(b2.date_ || printf('%020d', b2.id)) AS latest_key
                        FROM bill_transactions bt2 JOIN bills b2 ON b2.id=bt2.bill_id
                        WHERE bt2.curr_id=0 AND bt2.sls_u_price > 0
                        GROUP BY bt2.item_id
                    ) x ON x.item_id=bt.item_id AND (b.date_ || printf('%020d', b.id))=x.latest_key
                    WHERE bt.curr_id=0 AND bt.sls_u_price > 0""", null).use { c ->
                    while (c.moveToNext()) {
                        val itemId = c.getLong(0)
                        val price = c.getString(1)?.toBigDecimalOrNull()
                        if (price != null) latestBasePrices.putIfAbsent(itemId, price)
                    }
                }

                // Final fallback: historical invoice prices for legacy items not priced in the
                // current period. Current prices always win because this uses putIfAbsent.
                source.rawQuery("""SELECT bt.item_id, bt.sls_u_price
                    FROM bill_transactions_h bt
                    JOIN bills_h b ON b.id=bt.bill_id
                    JOIN (
                        SELECT bt2.item_id, MAX(b2.date_ || printf('%020d', b2.id)) AS latest_key
                        FROM bill_transactions_h bt2 JOIN bills_h b2 ON b2.id=bt2.bill_id
                        WHERE bt2.curr_id=0 AND bt2.sls_u_price > 0
                        GROUP BY bt2.item_id
                    ) x ON x.item_id=bt.item_id AND (b.date_ || printf('%020d', b.id))=x.latest_key
                    WHERE bt.curr_id=0 AND bt.sls_u_price > 0""", null).use { c ->
                    while (c.moveToNext()) {
                        val itemId = c.getLong(0)
                        val price = c.getString(1)?.toBigDecimalOrNull()
                        if (price != null) latestBasePrices.putIfAbsent(itemId, price)
                    }
                }
                val latestStock = mutableMapOf<Long, Pair<BigDecimal, BigDecimal>>()
                source.rawQuery("""SELECT s.item_id, s.n_q, s.n_u FROM items_cost_calc s
                    JOIN (SELECT item_id, MAX(date_ || printf('%020d', id2)) AS latest_key
                          FROM items_cost_calc GROUP BY item_id) x
                    ON x.item_id=s.item_id AND (s.date_ || printf('%020d', s.id2))=x.latest_key""", null).use { c ->
                    while (c.moveToNext()) {
                        val qty = c.getString(1)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val cost = c.getString(2)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        latestStock[c.getLong(0)] = qty to cost
                    }
                }
                val latestRate = mutableMapOf<Long, BigDecimal>()
                source.rawQuery("""SELECT cp.curr_id, cp.price FROM currency_price cp
                    JOIN (SELECT curr_id, MAX(f_date) AS max_date FROM currency_price GROUP BY curr_id) x
                    ON x.curr_id=cp.curr_id AND x.max_date=cp.f_date""", null).use { c ->
                    while (c.moveToNext()) {
                        val rate = c.getString(1)?.toBigDecimalOrNull()
                        if (rate != null && rate.signum() > 0) latestRate[c.getLong(0)] = rate
                    }
                }

                source.rawQuery("SELECT id,name,curr_type,code_name FROM currency ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)?.trim().orEmpty().ifEmpty { "عملة $id" }
                        val code = c.getString(3)?.trim().orEmpty().ifEmpty { "LEGACY_$id" }
                        val existingById = scalarNullable(target, "SELECT id FROM currencies WHERE id = ? LIMIT 1", arrayOf(id))
                        val existingByName = scalarNullable(target, "SELECT id FROM currencies WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        val existing = existingById ?: existingByName
                        val rate = if (id == baseCurrencyId) BigDecimal.ONE else latestRate[id]
                        val rateMicro = rate?.movePointRight(4)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L
                        if (existing != null) {
                            target.execSQL(
                                "UPDATE currencies SET name=?,code=?,rateMicro=CASE WHEN ?>0 THEN ? ELSE rateMicro END WHERE id=?",
                                arrayOf(name, code, rateMicro, rateMicro, existing),
                            )
                            putMap(target, "currency", id.toString(), "currencies", existing, "MATCHED_EXISTING")
                        } else {
                            target.execSQL("INSERT INTO currencies(id,name,code,rateMicro) VALUES(?,?,?,?)", arrayOf(id, name, code, rateMicro))
                            putMap(target, "currency", id.toString(), "currencies", id, "INSERTED")
                            currenciesAdded++
                        }
                    }
                }

                val warehouseId = scalarNullable(target, "SELECT id FROM warehouses WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf("المخزن الرئيسي"))
                    ?: run {
                        target.execSQL("INSERT INTO warehouses(name) VALUES(?)", arrayOf("المخزن الرئيسي"))
                        scalar(target, "SELECT id FROM warehouses WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf("المخزن الرئيسي"))
                    }
                val snapshotDate = source.rawQuery(
                    "SELECT MAX(date_) FROM bills WHERE date_ GLOB '????-??-??'",
                    null,
                ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } else null }
                    ?: "2026-09-30"

                source.rawQuery("""SELECT id,name,item_type_id,IS_ACTIVE,qty,price,curr_id,e_date,remarks,unit_id,barcode,o_cost
                    FROM items ORDER BY id""", null).use { c ->
                    while (c.moveToNext()) {
                        val sourceId = c.getLong(0)
                        val name = c.getString(1)?.trim().orEmpty()
                        if (name.isEmpty()) {
                            putMap(target, "items", sourceId.toString(), "items", null, "SKIPPED_EMPTY_NAME")
                            continue
                        }
                        val typeId = if (c.isNull(2)) -1L else c.getLong(2)
                        val unitId = if (c.isNull(9)) -1L else c.getLong(9)
                        val category = typeNames[typeId]?.takeIf { it.isNotBlank() } ?: "عام"
                        val unit = unitNames[unitId]?.takeIf { it.isNotBlank() && it != "بدون" } ?: "حبة"
                        val currencyId = if (c.isNull(6)) baseCurrencyId else c.getLong(6)
                        val sourcePrice = latestBasePrices[sourceId] ?: (c.getString(5)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                        val price = if (currencyId == baseCurrencyId && sourcePrice.signum() > 0) toMicro(sourcePrice) else 0L
                        val expiry = c.getString(7)?.trim()?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                        val notes = buildString {
                            c.getString(8)?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it) }
                            if (currencyId != baseCurrencyId && sourcePrice.signum() != 0) {
                                if (isNotEmpty()) append(" | ")
                                append("السعر الأصلي: ").append(sourcePrice.toPlainString()).append(" (عملة المصدر رقم ").append(currencyId).append(")")
                            }
                        }
                        val barcode = c.getString(10)?.trim()?.takeIf { it.isNotEmpty() }
                        val existingByName = scalarNullable(target, "SELECT id FROM items WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        val existingByBarcode = barcode?.let { scalarNullable(target, "SELECT id FROM items WHERE barcode = ? LIMIT 1", arrayOf(it)) }
                        val existing = existingByName ?: existingByBarcode
                        val targetId: Long
                        if (existing != null) {
                            val oldPrice = scalar(target, "SELECT salePriceMicro FROM items WHERE id=?", arrayOf(existing))
                            val finalPrice = if (price > 0L) price else oldPrice
                            target.execSQL(
                                """UPDATE items SET unit=?, salePriceMicro=?, category=?,
                                   expiryDate=COALESCE(?,expiryDate),
                                   notes=CASE WHEN TRIM(?)='' THEN notes ELSE ? END,
                                   barcode=CASE WHEN barcode IS NULL OR TRIM(barcode)='' THEN ? ELSE barcode END
                                   WHERE id=?""",
                                arrayOf(unit, finalPrice, category, expiry, notes, notes, barcode, existing),
                            )
                            targetId = existing
                            putMap(target, "items", sourceId.toString(), "items", targetId, "MATCHED_NAME_OR_BARCODE")
                            itemsMatched++
                        } else {
                            target.execSQL(
                                "INSERT INTO items(name,barcode,isService,unit,salePriceMicro,minStockMicro,category,expiryDate,notes) VALUES(?,?,0,?,?,0,?,?,?)",
                                arrayOf(name, barcode, unit, price, category, expiry, notes),
                            )
                            targetId = scalar(target, "SELECT id FROM items WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                            putMap(target, "items", sourceId.toString(), "items", targetId, "INSERTED")
                            itemsAdded++
                        }

                        val alreadySnapshotted = scalar(target,
                            "SELECT COUNT(*) FROM stock_movements WHERE refType='legacy_import' AND refId=?",
                            arrayOf(sourceId))
                        val qty = latestStock[sourceId]?.first ?: (c.getString(4)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                        if (alreadySnapshotted == 0L && qty.signum() != 0) {
                            val cost = latestStock[sourceId]?.second ?: (c.getString(11)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                            val qtyMicro = toMicro(qty)
                            val costMicro = if (cost.signum() >= 0) toMicro(cost) else 0L
                            target.execSQL(
                                """INSERT INTO stock_movements(itemId,warehouseId,date,kind,qtyMicro,unitCostMicro,avgCostMicro,refType,refId)
                                   VALUES(?,?,?,'LEGACY_BALANCE',?,?,?,?,?)""",
                                arrayOf(targetId, warehouseId, snapshotDate, qtyMicro, costMicro, costMicro, "legacy_import", sourceId),
                            )
                            stockBalancesAdded++
                        }
                    }
                }


                // Move legacy bills into Golden's operational invoice and journal tables.
                // Import history first so current-period invoices appear first in the operational list.
                invoicesAdded += importBills(source, target, "bills_h", "bill_transactions_h", baseCurrencyId, latestRate, warehouseId)
                invoicesAdded += importBills(source, target, "bills", "bill_transactions", baseCurrencyId, latestRate, warehouseId)

                // Only balanced, fully-mapped receipt/payment groups are posted.
                val currentVoucherResult = importVoucherGroups(source, target, "transactions", baseCurrencyId, latestRate)
                val historicalVoucherResult = importVoucherGroups(source, target, "transactions_h", baseCurrencyId, latestRate)
                val extraHistoricalVoucherResult = importVoucherGroups(source, target, "transactions_h2", baseCurrencyId, latestRate)
                val currentTransferResult = importVoucherGroups(source, target, "transactions", baseCurrencyId, latestRate,
                    "tr_type=0 AND bill_id<0 AND p_id>0")
                val historicalTransferResult = importVoucherGroups(source, target, "transactions_h", baseCurrencyId, latestRate,
                    "tr_type=0 AND bill_id<0 AND p_id>0")
                val extraHistoricalTransferResult = importVoucherGroups(source, target, "transactions_h2", baseCurrencyId, latestRate,
                    "tr_type=0 AND bill_id<0 AND p_id>0")
                voucherGroupsPosted += currentVoucherResult.first + historicalVoucherResult.first + extraHistoricalVoucherResult.first +
                    currentTransferResult.first + historicalTransferResult.first + extraHistoricalTransferResult.first
                voucherGroupsForReview += currentVoucherResult.second + historicalVoucherResult.second + extraHistoricalVoucherResult.second +
                    currentTransferResult.second + historicalTransferResult.second + extraHistoricalTransferResult.second

                val currentManualResult = importManualPairs(source, target, "transactions", baseCurrencyId, latestRate)
                val historicalManualResult = importManualPairs(source, target, "transactions_h", baseCurrencyId, latestRate)
                val extraHistoricalManualResult = importManualPairs(source, target, "transactions_h2", baseCurrencyId, latestRate)
                manualTransactionsPosted += currentManualResult.first + historicalManualResult.first + extraHistoricalManualResult.first
                manualTransactionsForReview += currentManualResult.second + historicalManualResult.second + extraHistoricalManualResult.second

                // Every imported source row is idempotently archived. Financial records are
                // intentionally marked for reconciliation instead of inventing debit/credit sides.
                sourceTables.forEach { table ->
                    source.rawQuery("SELECT * FROM `$table`", null).use { c ->
                        val idIndex = c.getColumnIndex("id")
                        var rowIndex = 0L
                        while (c.moveToNext()) {
                            val sourceId = if (idIndex >= 0 && !c.isNull(idIndex)) c.getString(idIndex)
                                else "row:${rowIndex}"
                            val targetTable = when (table) {
                                "account_tree" -> "account_groups"
                                "customers" -> "accounts"
                                "items" -> "items"
                                "currency" -> "currencies"
                                else -> "legacy_import_payloads"
                            }
                            if (scalarNullable(target, "SELECT sourceId FROM legacy_import_map WHERE sourceTable=? AND sourceId=?", arrayOf(table, sourceId)) == null) {
                                putMap(target, table, sourceId, targetTable, null,
                                    if (table in listOf("transactions", "transactions_h", "bills", "bill_transactions", "currency_price")) "ARCHIVED_REVIEW" else "ARCHIVED")
                            }
                            rowIndex++
                        }
                    }
                }

                target.setTransactionSuccessful()
            } finally {
                target.endTransaction()
            }
            return Result(groupsAdded, accountsAdded, accountsMatched, itemsAdded, itemsMatched, currenciesAdded, stockBalancesAdded, invoicesAdded, voucherGroupsPosted, voucherGroupsForReview, manualTransactionsPosted, manualTransactionsForReview, archivedAdded, archivedExisting)
        } finally {
            source.close()
        }
    }

    private fun importBills(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        billTable: String,
        lineTable: String,
        baseCurrencyId: Long,
        latestRate: Map<Long, BigDecimal>,
        warehouseId: Long,
    ): Int {
        var imported = 0
        source.rawQuery(
            "SELECT id,date_,remarks,bill_no,curr_id,bill_type,tr_type,is_back,cus_id,amount,d_amount,tax_amount,paid_amount,bill_no2,curr_price FROM `$billTable` WHERE tr_type IN (1,2) ORDER BY date_,id",
            null,
        ).use { bills ->
            while (bills.moveToNext()) {
                val sourceId = bills.getLong(0)
                val mappedDisposition = scalarNullable(target,
                    "SELECT CASE WHEN disposition='OPERATIONAL_INVOICE' THEN 1 ELSE 0 END FROM legacy_import_map WHERE sourceTable=? AND sourceId=?",
                    arrayOf(billTable, sourceId.toString()))
                if (mappedDisposition == 1L) continue

                val date = normalizeLegacyDate(bills.getString(1))
                if (date == null) {
                    upsertMap(target, billTable, sourceId.toString(), "legacy_import_payloads", null, "ARCHIVED_REVIEW_INVALID_DATE")
                    continue
                }
                val memoSource = bills.getString(2)?.trim().orEmpty()
                val billNo = bills.getString(3)?.trim().orEmpty()
                val currencyId = bills.getLong(4)
                val isCash = bills.getLong(5) == 1L
                val kind = if (bills.getLong(6) == 1L) InvoiceKind.SALE else InvoiceKind.PURCHASE
                val isReturn = bills.getLong(7) != 0L
                val sourcePartyId = bills.getLong(8)
                val sourceRate = if (currencyId == baseCurrencyId) BigDecimal.ONE else
                    (bills.getString(14)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: latestRate[currencyId] ?: BigDecimal.ONE)
                val amount = ((bills.getString(9)?.toBigDecimalOrNull() ?: BigDecimal.ZERO) * sourceRate).max(BigDecimal.ZERO)
                val discount = ((bills.getString(10)?.toBigDecimalOrNull() ?: BigDecimal.ZERO) * sourceRate).max(BigDecimal.ZERO).min(amount)
                val tax = ((bills.getString(11)?.toBigDecimalOrNull() ?: BigDecimal.ZERO) * sourceRate).max(BigDecimal.ZERO)
                val net = (amount - discount).max(BigDecimal.ZERO)
                val total = net + tax
                val paidSource = ((bills.getString(12)?.toBigDecimalOrNull() ?: BigDecimal.ZERO) * sourceRate).max(BigDecimal.ZERO)
                val paid = if (isCash) total else paidSource.min(total)
                val number = bills.getString(13)?.toIntOrNull()?.takeIf { it > 0 }
                    ?: billNo.toIntOrNull()?.takeIf { it > 0 } ?: sourceId.toInt()
                val mappedParty = if (sourcePartyId > 0L) scalarNullable(target,
                    "SELECT targetId FROM legacy_import_map WHERE sourceTable='customers' AND sourceId=? AND targetTable='accounts'",
                    arrayOf(sourcePartyId.toString())) else scalarNullable(target,
                    "SELECT id FROM accounts WHERE id=? LIMIT 1", arrayOf(sourcePartyId))
                val partyAccountId = if (isCash) mappedParty ?: -3L else mappedParty?.takeIf { it > 0L } ?: ensureLegacySuspenseAccount(target)
                val partyName = if (sourcePartyId > 0L) {
                    source.rawQuery("SELECT name FROM customers WHERE id=? LIMIT 1", arrayOf(sourcePartyId.toString())).use { c ->
                        if (c.moveToFirst()) c.getString(0)?.trim().orEmpty() else ""
                    }.ifBlank { "حساب قديم $sourcePartyId" }
                } else if (isCash) "نقدي" else "حساب مستورد غير مربوط"

                val lineRecords = mutableListOf<Triple<Long, BigDecimal, BigDecimal>>()
                source.rawQuery(
                    "SELECT item_id,qty,qty_pr,sls_u_price,curr_id FROM `$lineTable` WHERE bill_id=?",
                    arrayOf(sourceId.toString()),
                ).use { lines ->
                    while (lines.moveToNext()) {
                        val sourceItemId = lines.getLong(0)
                        val qty = (lines.getString(2)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                            ?: lines.getString(1)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                        if (qty.signum() <= 0 || toMicro(qty) <= 0L) continue
                        val itemId = scalarNullable(target,
                            "SELECT targetId FROM legacy_import_map WHERE sourceTable='items' AND sourceId=? AND targetTable='items'",
                            arrayOf(sourceItemId.toString())) ?: continue
                        val lineCurrencyId = lines.getLong(4)
                        val lineRate = if (lineCurrencyId == baseCurrencyId) BigDecimal.ONE else latestRate[lineCurrencyId] ?: sourceRate
                        val unitPrice = ((lines.getString(3)?.toBigDecimalOrNull() ?: BigDecimal.ZERO) * lineRate).max(BigDecimal.ZERO)
                        lineRecords += Triple(itemId, qty, unitPrice)
                    }
                }

                val lineGross = lineRecords.fold(BigDecimal.ZERO) { sum, line -> sum + line.second * line.third }
                val memo = buildString {
                    append("استيراد محاسب سوفت")
                    if (billNo.isNotBlank() && billNo != "0") append(" • رقم المصدر ").append(billNo)
                    if (memoSource.isNotBlank()) append(" • ").append(memoSource)
                    if (lineRecords.isEmpty()) append(" • لا توجد تفاصيل أصناف قابلة للربط في المصدر")
                    else if ((lineGross - amount).abs() > BigDecimal("0.01")) append(" • إجمالي التفاصيل يختلف عن إجمالي رأس الفاتورة القديم")
                }

                target.execSQL(
                    """INSERT INTO invoices(kind,number,isReturn,isCash,partyAccountId,partyName,warehouseId,date,memo,
                       discountMicro,netMicro,taxMicro,feesMicro,totalMicro,paidMicro)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf(kind.name, number, if (isReturn) 1 else 0, if (isCash) 1 else 0, partyAccountId, partyName,
                        warehouseId, date, memo, toMicro(discount), toMicro(net), toMicro(tax), 0L, toMicro(total), toMicro(paid)),
                )
                val invoiceId = scalar(target, "SELECT last_insert_rowid()", emptyArray())
                lineRecords.forEach { line ->
                    target.execSQL(
                        "INSERT INTO invoice_lines(invoiceId,itemId,qtyMicro,priceMicro) VALUES(?,?,?,?)",
                        arrayOf(invoiceId, line.first, toMicro(line.second), toMicro(line.third)),
                    )
                }

                if (total.signum() > 0) {
                    val costOfGoods = if (kind == InvoiceKind.SALE && billTable == "bills") {
                        val costExpr = if (isReturn) "SUM(i_q*n_u)" else "SUM(o_q*n_u)"
                        source.rawQuery("SELECT COALESCE($costExpr,0) FROM items_cost_calc WHERE bill_id=?", arrayOf(sourceId.toString())).use { c ->
                            if (c.moveToFirst()) (c.getString(0)?.toBigDecimalOrNull() ?: BigDecimal.ZERO).max(BigDecimal.ZERO) else BigDecimal.ZERO
                        }
                    } else BigDecimal.ZERO
                    val totals = Totals(amount, discount, net, tax, BigDecimal.ZERO, total)
                    val entry = PostingEngine.invoice(kind, isReturn, isCash, partyAccountId, totals, DEFAULT_ACCOUNTS, date, memo, paid, costOfGoods)
                    target.execSQL(
                        "INSERT INTO journal_entries(date,memo,refType,refId,createdAt) VALUES(?,?,?,?,?)",
                        arrayOf(date, memo, "legacy_import_invoice", invoiceId, System.currentTimeMillis()),
                    )
                    val entryId = scalar(target, "SELECT last_insert_rowid()", emptyArray())
                    entry.lines.forEach { line ->
                        target.execSQL(
                            "INSERT INTO journal_lines(entryId,accountId,debitMicro,creditMicro,currencyId) VALUES(?,?,?,?,0)",
                            arrayOf(entryId, line.accountId, toMicro(line.debit), toMicro(line.credit)),
                        )
                    }
                }
                upsertMap(target, billTable, sourceId.toString(), "invoices", invoiceId, "OPERATIONAL_INVOICE")
                imported++
            }
        }
        return imported
    }

    private fun importVoucherGroups(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        table: String,
        baseCurrencyId: Long,
        latestRate: Map<Long, BigDecimal>,
        selection: String = "tr_type IN (5,6) AND p_id > 0",
    ): Pair<Int, Int> {
        data class VoucherLine(
            val sourceId: String, val accountId: Long, val debitMicro: Long, val creditMicro: Long,
            val date: String, val memo: String,
        )
        val groups = linkedMapOf<Triple<Long, Long, String>, MutableList<VoucherLine>>()
        val rawRows = linkedMapOf<Triple<Long, Long, String>, MutableList<Triple<String, Long, Long>>>()
        val zeroRows = linkedMapOf<Triple<Long, Long, String>, MutableList<Triple<String, Long, Long>>>()
        val counterpartBySource = mutableMapOf<String, Long?>()
        source.rawQuery(
            """SELECT id,cus_id,"in","out",date_,remarks,p_id,tr_type,curr_id,curr_price,t_cus_id
               FROM `$table` WHERE $selection ORDER BY tr_type,p_id,id""",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val pId = c.getLong(6)
                val type = c.getLong(7)
                val sourceId = c.getString(0)
                val date = normalizeLegacyDate(c.getString(4)) ?: ""
                val key = Triple(pId, type, date)
                val memo = c.getString(5)?.trim().orEmpty()
                val sourceAccountId = c.getLong(1)
                val direction = c.getString(2)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val amount = c.getString(3)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val counterpartSourceId = if (c.isNull(10)) 0L else c.getLong(10)
                counterpartBySource[sourceId] = mapLegacyAccount(target, counterpartSourceId)
                val row = Triple(sourceId, pId, type)
                if (amount.signum() == 0) {
                    zeroRows.getOrPut(key) { mutableListOf() }.add(row)
                    continue
                }

                val accountId = mapLegacyAccount(target, sourceAccountId)
                val currencyId = c.getLong(8)
                val rate = if (currencyId == baseCurrencyId) BigDecimal.ONE else
                    (c.getString(9)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: latestRate[currencyId] ?: BigDecimal.ZERO)
                val localAmount = amount.abs() * rate
                if (accountId != null && accountId != 0L && date.isNotBlank() && direction.signum() != 0 &&
                    localAmount.signum() > 0 && rate.signum() > 0) {
                    val micro = toMicro(localAmount)
                    if (micro <= 0L) {
                        zeroRows.getOrPut(key) { mutableListOf() }.add(row)
                        continue
                    }
                    groups.getOrPut(key) { mutableListOf() }.add(
                        VoucherLine(sourceId, accountId, if (direction.signum() > 0) micro else 0L,
                            if (direction.signum() < 0) micro else 0L, date, memo)
                    )
                }
                rawRows.getOrPut(key) { mutableListOf() }.add(row)
            }
        }

        var posted = 0
        var review = 0
        rawRows.forEach { (key, sourceRows) ->
            val pId = key.first
            val type = key.second
            val refType = "legacy_voucher_${table}_$type"
            val allRows = sourceRows + zeroRows[key].orEmpty()
            val groupRefId = sourceRows.firstOrNull()?.first?.toLongOrNull() ?: pId
            val existingEntry = scalarNullable(target,
                "SELECT id FROM journal_entries WHERE refType=? AND refId=? LIMIT 1", arrayOf(refType, groupRefId))
            if (existingEntry != null) {
                allRows.forEach { row -> upsertMap(target, table, row.first, "journal_entries", existingEntry, "POSTED_VOUCHER") }
                return@forEach
            }
            val lines = groups[key].orEmpty().toMutableList()
            var usedCounterpartFallback = false
            if (lines.size == 1 && sourceRows.size == 1) {
                val original = lines.single()
                val counterpart = counterpartBySource[original.sourceId]
                if (counterpart != null && counterpart != original.accountId) {
                    lines += VoucherLine("counterpart:${original.sourceId}", counterpart,
                        original.creditMicro, original.debitMicro, original.date, original.memo)
                    usedCounterpartFallback = true
                }
            }
            val debit = lines.sumOf { it.debitMicro }
            val credit = lines.sumOf { it.creditMicro }
            val expectedLineCount = sourceRows.size + if (usedCounterpartFallback) 1 else 0
            val valid = lines.size >= 2 && lines.size == expectedLineCount && debit > 0L && debit == credit &&
                lines.map { it.date }.distinct().size == 1
            if (!valid) {
                allRows.forEach { row -> upsertMap(target, table, row.first, "legacy_import_payloads", null, "ARCHIVED_REVIEW_UNBALANCED") }
                review++
                return@forEach
            }
            val date = lines.first().date
            val memo = lines.firstOrNull { it.memo.isNotBlank() }?.memo ?: "سند قديم رقم $pId"
            target.execSQL(
                "INSERT INTO journal_entries(date,memo,refType,refId,createdAt) VALUES(?,?,?,?,?)",
                arrayOf(date, "ترحيل محاسب سوفت • $memo", refType, groupRefId, System.currentTimeMillis()),
            )
            val entryId = scalar(target, "SELECT last_insert_rowid()", emptyArray())
            lines.forEach { line ->
                target.execSQL(
                    "INSERT INTO journal_lines(entryId,accountId,debitMicro,creditMicro,currencyId) VALUES(?,?,?,?,0)",
                    arrayOf(entryId, line.accountId, line.debitMicro, line.creditMicro),
                )
            }
            allRows.forEach { row -> upsertMap(target, table, row.first, "journal_entries", entryId, "POSTED_VOUCHER") }
            posted++
        }
        return posted to review
    }

    private fun importManualPairs(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        table: String,
        baseCurrencyId: Long,
        latestRate: Map<Long, BigDecimal>,
    ): Pair<Int, Int> {
        var posted = 0
        var review = 0
        val refType = "legacy_manual_pair_$table"
        source.rawQuery(
            """SELECT id,cus_id,"in","out",date_,remarks,t_cus_id,curr_id,curr_price
               FROM `$table`
               WHERE tr_type=0 AND (bill_id=0 OR (bill_id<0 AND p_id=0))
                 AND NOT (remarks LIKE '%رصيد افتتاحي%' AND date_='01-01-2026')
               ORDER BY id""",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val sourceId = c.getLong(0)
                val sourceKey = sourceId.toString()
                val already = scalarNullable(target,
                    "SELECT CASE WHEN disposition='POSTED_MANUAL_PAIR' THEN 1 ELSE 0 END FROM legacy_import_map WHERE sourceTable=? AND sourceId=?",
                    arrayOf(table, sourceKey))
                if (already == 1L) continue
                val existingEntry = scalarNullable(target,
                    "SELECT id FROM journal_entries WHERE refType=? AND refId=? LIMIT 1", arrayOf(refType, sourceId))
                if (existingEntry != null) {
                    upsertMap(target, table, sourceKey, "journal_entries", existingEntry, "POSTED_MANUAL_PAIR")
                    continue
                }

                val accountId = mapLegacyAccount(target, c.getLong(1))
                val counterpartSourceId = if (c.isNull(6)) 0L else c.getLong(6)
                val counterpartId = mapLegacyAccount(target, counterpartSourceId)
                val direction = c.getString(2)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val amount = c.getString(3)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val date = normalizeLegacyDate(c.getString(4))
                val currencyId = c.getLong(7)
                val rate = if (currencyId == baseCurrencyId) BigDecimal.ONE else
                    (c.getString(8)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: latestRate[currencyId] ?: BigDecimal.ZERO)
                val localAmount = amount.abs() * rate
                if (accountId == null || counterpartId == null || accountId == counterpartId ||
                    direction.signum() == 0 || localAmount.signum() <= 0 || rate.signum() <= 0 || date == null) {
                    upsertMap(target, table, sourceKey, "legacy_import_payloads", null, "ARCHIVED_REVIEW_UNMAPPED_PAIR")
                    review++
                    continue
                }
                val micro = toMicro(localAmount)
                if (micro <= 0L) {
                    upsertMap(target, table, sourceKey, "legacy_import_payloads", null, "ARCHIVED_REVIEW_AMOUNT_TOO_SMALL")
                    review++
                    continue
                }
                val mainDebit = if (direction.signum() > 0) micro else 0L
                val mainCredit = if (direction.signum() < 0) micro else 0L
                val contraDebit = mainCredit
                val contraCredit = mainDebit
                val memo = "ترحيل حركة قديمة • " + (c.getString(5)?.trim().orEmpty().ifBlank { "حركة رقم $sourceId" })
                target.execSQL(
                    "INSERT INTO journal_entries(date,memo,refType,refId,createdAt) VALUES(?,?,?,?,?)",
                    arrayOf(date, memo, refType, sourceId, System.currentTimeMillis()),
                )
                val entryId = scalar(target, "SELECT last_insert_rowid()", emptyArray())
                target.execSQL(
                    "INSERT INTO journal_lines(entryId,accountId,debitMicro,creditMicro,currencyId) VALUES(?,?,?,?,0)",
                    arrayOf(entryId, accountId, mainDebit, mainCredit),
                )
                target.execSQL(
                    "INSERT INTO journal_lines(entryId,accountId,debitMicro,creditMicro,currencyId) VALUES(?,?,?,?,0)",
                    arrayOf(entryId, counterpartId, contraDebit, contraCredit),
                )
                upsertMap(target, table, sourceKey, "journal_entries", entryId, "POSTED_MANUAL_PAIR")
                posted++
            }
        }
        return posted to review
    }

    private fun mapLegacyAccount(db: SupportSQLiteDatabase, sourceAccountId: Long): Long? {
        if (sourceAccountId == 0L) return null
        return if (sourceAccountId < 0L) {
            scalarNullable(db, "SELECT id FROM accounts WHERE id=? LIMIT 1", arrayOf(sourceAccountId))
        } else {
            scalarNullable(db,
                "SELECT targetId FROM legacy_import_map WHERE sourceTable='customers' AND sourceId=? AND targetTable='accounts'",
                arrayOf(sourceAccountId.toString()))
        }
    }

    private fun ensureLegacySuspenseAccount(db: SupportSQLiteDatabase): Long {
        val name = "حساب مستورد غير مربوط"
        val existing = scalarNullable(db, "SELECT id FROM accounts WHERE name=? COLLATE NOCASE LIMIT 1", arrayOf(name))
        if (existing != null) return existing
        db.execSQL("INSERT INTO accounts(name,groupId,isSystem,phone,creditLimitMicro) VALUES(?,124,0,NULL,0)", arrayOf(name))
        val id = scalar(db, "SELECT id FROM accounts WHERE name=? COLLATE NOCASE LIMIT 1", arrayOf(name))
        val category = scalarNullable(db, "SELECT id FROM account_categories WHERE name='أخرى' LIMIT 1", emptyArray())
        if (category != null) db.execSQL("INSERT OR IGNORE INTO account_category_links(accountId,categoryId) VALUES(?,?)", arrayOf(id, category))
        return id
    }

    private fun normalizeLegacyDate(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return v
        val m = Regex("^(\\d{2})-(\\d{2})-(\\d{4})$").matchEntire(v) ?: return null
        return "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}"
    }

    private fun upsertMap(db: SupportSQLiteDatabase, table: String, sourceId: String, targetTable: String, targetId: Long?, disposition: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO legacy_import_map(sourceTable,sourceId,targetTable,targetId,disposition) VALUES(?,?,?,?,?)",
            arrayOf(table, sourceId, targetTable, targetId, disposition),
        )
        db.execSQL(
            "UPDATE legacy_import_map SET targetTable=?,targetId=?,disposition=? WHERE sourceTable=? AND sourceId=?",
            arrayOf(targetTable, targetId, disposition, table, sourceId),
        )
    }

    private fun ensureCategory(db: SupportSQLiteDatabase, name: String, groupId: Long, color: String, sortOrder: Int): Long {
        val existing = scalarNullable(db, "SELECT id FROM account_categories WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
        if (existing != null) {
            db.execSQL(
                "UPDATE account_categories SET groupId=COALESCE(groupId,?),color=?,sortOrder=? WHERE id=?",
                arrayOf(groupId, color, sortOrder, existing),
            )
            return existing
        }
        db.execSQL("INSERT INTO account_categories(name,groupId,color,icon,sortOrder,enabled,isSystem) VALUES(?,?,?,'folder',?,1,1)", arrayOf(name, groupId, color, sortOrder))
        return scalar(db, "SELECT id FROM account_categories WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
    }

    private fun linkLegacyCategory(db: SupportSQLiteDatabase, accountId: Long, groupId: Long, customerType: Long) {
        val groupCategory = when (groupId) {
            123L -> "العملاء"
            126L -> "العملاء (الثقه)"
            121L -> "الصناديق"
            122L -> "البنوك"
            221L -> "الموردون"
            111L, 11L, 13L -> "الأصول الثابتة"
            1L, 125L, 127L, 128L, 129L -> "الأصول"
            12L -> "الأصول المتداولة"
            21L, 211L, 212L, 213L, 214L -> "حقوق الملكية"
            2L -> "الالتزامات وحقوق الملكية"
            22L, 23L, 222L, 223L, 224L, 225L -> "الالتزامات"
            3L, 31L, 311L, 312L, 313L, 314L, 315L, 32L, 321L, 322L, 323L, 3221L, 3222L, 3223L -> "المصروفات"
            4L, 41L, 42L, 411L, 412L, 413L, 421L -> "الإيرادات"
            else -> null
        }
        val name = groupCategory ?: when (customerType) {
            1L -> "الموردون"
            4L -> "الصناديق"
            5L -> "المصروفات"
            6L -> "الإيرادات"
            else -> "أخرى"
        }
        val categoryId = scalarNullable(db, "SELECT id FROM account_categories WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
            ?: ensureCategory(db, name, groupId, "#64748B", 99)
        db.execSQL("INSERT OR IGNORE INTO account_category_links(accountId,categoryId) VALUES(?,?)", arrayOf(accountId, categoryId))
    }

    private fun archiveSourceTable(source: SQLiteDatabase, target: SupportSQLiteDatabase, table: String): Long {
        var rows = 0L
        source.rawQuery("SELECT * FROM `$table`", null).use { c ->
            val idIndex = c.getColumnIndex("id")
            var rowIndex = 0L
            while (c.moveToNext()) {
                val sourceId = if (idIndex >= 0 && !c.isNull(idIndex)) c.getString(idIndex)
                    else "row:${rowIndex}"
                val json = JSONObject()
                for (i in 0 until c.columnCount) {
                    val value: Any? = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
                        else -> c.getString(i)
                    }
                    json.put(c.getColumnName(i), value ?: JSONObject.NULL)
                }
                target.execSQL(
                    "INSERT OR IGNORE INTO legacy_import_payloads(sourceTable,sourceId,payload,importedAt) VALUES(?,?,?,?)",
                    arrayOf(table, sourceId, json.toString(), System.currentTimeMillis()),
                )
                rows++
                rowIndex++
            }
        }
        return rows
    }

    private fun putMap(db: SupportSQLiteDatabase, table: String, sourceId: String, targetTable: String, targetId: Long?, disposition: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO legacy_import_map(sourceTable,sourceId,targetTable,targetId,disposition) VALUES(?,?,?,?,?)",
            arrayOf(table, sourceId, targetTable, targetId, disposition),
        )
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>): Long =
        scalarNullable(db, sql, args) ?: 0L

    private fun scalarNullable(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>): Long? =
        db.query(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun toMicro(value: BigDecimal): Long =
        value.setScale(4, RoundingMode.HALF_UP).movePointRight(4).longValueExact()
}
