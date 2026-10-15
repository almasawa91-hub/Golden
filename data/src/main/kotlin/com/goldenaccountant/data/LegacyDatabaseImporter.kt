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
            var archivedAdded = 0L
            var archivedExisting = 0L

            target.beginTransaction()
            try {
                // Retain the legacy rows first, so source records remain recoverable even when
                // they cannot safely be posted to Golden's accounting engine.
                archiveTables.forEach { table ->
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
                target.execSQL("UPDATE account_categories SET name='الصناديق' WHERE name='الصناديق والبنوك' AND groupId=121")
                val groupNames = mutableMapOf<Long, String>()
                val parentByGroup = mutableMapOf<Long, Long>()
                target.query("SELECT id,name,parentId FROM account_groups").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        groupNames[id] = c.getString(1)?.trim().orEmpty().ifEmpty { "مجموعة $id" }
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
                // Customer rows include both contact accounts and some legacy/system rows.
                source.rawQuery("SELECT id,name,gsm,acc_p_id,cus_type_id FROM customers ORDER BY id", null).use { c ->
                    while (c.moveToNext()) {
                        val sourceId = c.getLong(0)
                        val name = c.getString(1)?.trim().orEmpty()
                        if (name.isEmpty()) {
                            putMap(target, "customers", sourceId.toString(), "accounts", null, "SKIPPED_EMPTY_NAME")
                            continue
                        }
                        val existing = scalarNullable(target, "SELECT id FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        if (existing != null) {
                            putMap(target, "customers", sourceId.toString(), "accounts", existing, "MATCHED_NAME")
                            accountsMatched++
                            continue
                        }
                        val sourceGroup = if (c.isNull(3)) 0L else c.getLong(3)
                        val groupId = if (sourceGroup in groupIds) sourceGroup else if (123L in groupIds) 123L else groupIds.firstOrNull() ?: 1L
                        val phone = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() }
                        target.execSQL(
                            "INSERT INTO accounts(name,groupId,isSystem,phone,creditLimitMicro) VALUES(?,?,0,?,0)",
                            arrayOf(name, groupId, phone),
                        )
                        val targetId = scalarNullable(target, "SELECT id FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        putMap(target, "customers", sourceId.toString(), "accounts", targetId, "INSERTED")
                        accountsAdded++
                    }
                }

                target.query("SELECT id,groupId FROM accounts").use { c ->
                    while (c.moveToNext()) {
                        val accountId = c.getLong(0)
                        var groupId = c.getLong(1)
                        val visited = mutableSetOf<Long>()
                        while (groupId != 0L && visited.add(groupId)) {
                            val categoryId = categoryByGroup[groupId]
                            if (categoryId != null) {
                                target.execSQL("INSERT OR IGNORE INTO account_category_links(accountId,categoryId) VALUES(?,?)",
                                    arrayOf(accountId, categoryId))
                            }
                            groupId = parentByGroup[groupId] ?: 0L
                        }
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
                    JOIN (SELECT item_id, MAX(date_) AS max_date FROM item_price WHERE curr_id = 0 GROUP BY item_id) x
                    ON x.item_id=p.item_id AND x.max_date=p.date_ WHERE p.curr_id = 0""", null).use { c ->
                    while (c.moveToNext()) {
                        val price = c.getString(1)?.toBigDecimalOrNull()
                        if (price != null) latestBasePrices[c.getLong(0)] = price
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
                        if (existing != null) {
                            putMap(target, "currency", id.toString(), "currencies", existing, "MATCHED_EXISTING")
                        } else {
                            val rate = if (id == baseCurrencyId) BigDecimal.ONE else latestRate[id]
                            // A missing exchange rate is recorded as 1 only for the base currency.
                            // Unknown foreign rates are kept as 0 and remain visible in the archive.
                            val rateMicro = rate?.movePointRight(4)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L
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
                        val existingByName = scalarNullable(target, "SELECT id FROM items WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        val barcode = c.getString(10)?.trim()?.takeIf { it.isNotEmpty() }
                        val existingByBarcode = barcode?.let { scalarNullable(target, "SELECT id FROM items WHERE barcode = ? LIMIT 1", arrayOf(it)) }
                        val existing = existingByName ?: existingByBarcode
                        val typeId = if (c.isNull(2)) -1L else c.getLong(2)
                        val unitId = if (c.isNull(9)) -1L else c.getLong(9)
                        val category = typeNames[typeId]?.takeIf { it.isNotBlank() } ?: "عام"
                        val unit = unitNames[unitId]?.takeIf { it.isNotBlank() } ?: "حبة"
                        val currencyId = if (c.isNull(6)) baseCurrencyId else c.getLong(6)
                        val sourcePrice = latestBasePrices[sourceId] ?: (c.getString(5)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                        val price = if (currencyId == baseCurrencyId) toMicro(sourcePrice) else 0L
                        val expiry = c.getString(7)?.trim()?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                        val notes = buildString {
                            c.getString(8)?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it) }
                            if (currencyId != baseCurrencyId && sourcePrice.signum() != 0) {
                                if (isNotEmpty()) append(" | ")
                                append("السعر الأصلي: ").append(sourcePrice.toPlainString()).append(" (عملة المصدر رقم ").append(currencyId).append(")")
                            }
                        }
                        if (existing != null) {
                            // Enrich matched rows instead of leaving previously-created blank records unchanged.
                            target.execSQL(
                                """UPDATE items SET
                                   barcode=CASE WHEN barcode IS NULL OR TRIM(barcode)='' THEN ? ELSE barcode END,
                                   unit=CASE WHEN unit IS NULL OR TRIM(unit)='' OR unit='حبة' THEN ? ELSE unit END,
                                   category=CASE WHEN category IS NULL OR TRIM(category)='' OR category='عام' THEN ? ELSE category END,
                                   salePriceMicro=CASE WHEN salePriceMicro=0 AND ? > 0 THEN ? ELSE salePriceMicro END,
                                   expiryDate=CASE WHEN expiryDate IS NULL OR TRIM(expiryDate)='' THEN ? ELSE expiryDate END,
                                   notes=CASE WHEN notes IS NULL OR TRIM(notes)='' THEN ? ELSE notes END
                                   WHERE id=?""",
                                arrayOf(barcode, unit, category, price, price, expiry, notes, existing))
                            putMap(target, "items", sourceId.toString(), "items", existing, "MATCHED_NAME_OR_BARCODE")
                            itemsMatched++
                            val qty = latestStock[sourceId]?.first ?: (c.getString(4)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                            if (qty.signum() != 0 && scalar(target, "SELECT COUNT(*) FROM stock_movements WHERE itemId=?", arrayOf(existing)) == 0L) {
                                val cost = latestStock[sourceId]?.second ?: (c.getString(11)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                                val costMicro = if (cost.signum() >= 0) toMicro(cost) else 0L
                                target.execSQL(
                                    """INSERT INTO stock_movements(itemId,warehouseId,date,kind,qtyMicro,unitCostMicro,avgCostMicro,refType,refId)
                                       VALUES(?,?,?,'LEGACY_BALANCE',?,?,?,?,?)""",
                                    arrayOf(existing, warehouseId, snapshotDate, toMicro(qty), costMicro, costMicro, "legacy_import", sourceId),
                                )
                                stockBalancesAdded++
                            }
                            continue
                        }
                        target.execSQL(
                            "INSERT INTO items(name,barcode,isService,unit,salePriceMicro,minStockMicro,category,expiryDate,notes) VALUES(?,?,0,?,?,0,?,?,?)",
                            arrayOf(name, barcode, unit, price, category, expiry, notes),
                        )
                        val targetId = scalar(target, "SELECT id FROM items WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(name))
                        putMap(target, "items", sourceId.toString(), "items", targetId, "INSERTED")
                        itemsAdded++

                        val qty = latestStock[sourceId]?.first ?: (c.getString(4)?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                        if (qty.signum() != 0) {
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

                // Every imported source row is idempotently archived. Financial records are
                // intentionally marked for reconciliation instead of inventing debit/credit sides.
                archiveTables.forEach { table ->
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
            return Result(groupsAdded, accountsAdded, accountsMatched, itemsAdded, itemsMatched, currenciesAdded, stockBalancesAdded, archivedAdded, archivedExisting)
        } finally {
            source.close()
        }
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
