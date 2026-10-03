# مخطط قاعدة البيانات (v1)
account_groups(id, name, parentId) — شجرة الحسابات الأصلية (34 مجموعة)
accounts(id, name unique, groupId→groups, isSystem, phone, creditLimitMicro)
journal_entries(id, date, memo, refType, refId, createdAt) idx(date, refType+refId)
journal_lines(id, entryId→entries CASCADE, accountId→accounts, debitMicro, creditMicro, currencyId)
warehouses(id, name) · items(id, name unique, barcode, isService, unit, salePriceMicro, minStockMicro)
stock_movements(id, itemId, warehouseId, date, kind, qtyMicro, unitCostMicro, avgCostMicro, refType, refId)
currencies(id, name, code, rateMicro) · settings(key, value)
قادم: invoices/lines، المرتجعات، عروض الأسعار، طلبات الشراء، المستخدمون والأدوار، audit_log، المرفقات، تحويل الوحدات، الضرائب.
