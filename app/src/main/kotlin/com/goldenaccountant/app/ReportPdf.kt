package com.goldenaccountant.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.OutputStream
import kotlin.math.max

/** إنشاء ملفات PDF بجداول حقيقية، خلايا متعددة الأسطر، وتكرار رأس الجدول في كل صفحة مع دعم RTL. */
object ReportPdf {
    fun write(
        output: OutputStream,
        title: String,
        columns: List<String>,
        rows: List<List<String>>,
        pageWidth: Int = 595,
        pageHeight: Int = 842,
    ) {
        require(pageWidth >= 100 && pageHeight >= 160) { "أبعاد صفحة PDF غير صالحة" }
        val document = PdfDocument()
        val thermal = pageWidth < 300
        val margin = if (thermal) 8f else 30f
        val padding = if (thermal) 3f else 6f
        val usableWidth = (pageWidth - margin * 2).toInt().coerceAtLeast(1)
        val bottom = pageHeight - margin
        val tableColumnCount = (if (columns.isNotEmpty()) columns.size else rows.maxOfOrNull { it.size } ?: 1).coerceIn(1, 8)
        val columnWidth = FloatArray(tableColumnCount) { usableWidth.toFloat() / tableColumnCount }
        if (tableColumnCount > 0) columnWidth[tableColumnCount - 1] = usableWidth - columnWidth.dropLast(1).sum()

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = if (thermal) 8.5f else 10.5f
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(15, 23, 42)
            textSize = if (thermal) 12f else 18f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(30, 64, 175)
            textSize = if (thermal) 8f else 10f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textSize = if (thermal) 7f else 9f
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            strokeWidth = if (thermal) 0.5f else 0.7f
            style = Paint.Style.STROKE
        }
        val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(241, 245, 249)
            style = Paint.Style.FILL
        }

        fun layout(text: String, paint: TextPaint, width: Int): StaticLayout {
            val safeText = text.ifEmpty { " " }
            return StaticLayout.Builder.obtain(safeText, 0, safeText.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        }

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = margin

        fun drawCellText(target: Canvas, textLayout: StaticLayout, left: Float, top: Float) {
            target.save()
            target.translate(left + padding, top + padding)
            textLayout.draw(target)
            target.restore()
        }

        fun drawColumnCells(target: Canvas, values: List<String>, top: Float, heights: List<StaticLayout>, rowHeight: Float, fill: Paint?) {
            var x = margin
            for (i in 0 until tableColumnCount) {
                val width = columnWidth[i]
                if (fill != null) target.drawRect(x, top, x + width, top + rowHeight, fill)
                target.drawRect(x, top, x + width, top + rowHeight, borderPaint)
                drawCellText(target, heights[i], x, top)
                x += width
            }
        }

        fun headerLayouts(): List<StaticLayout> = (0 until tableColumnCount).map { i ->
            layout(columns.getOrNull(i).orEmpty(), headerPaint, (columnWidth[i] - padding * 2).toInt())
        }

        fun startPage() {
            pageNumber++
            val started = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            page = started
            canvas = started.canvas
            y = margin
            val titleLayout = layout(title, titlePaint, usableWidth)
            drawCellText(started.canvas, titleLayout, margin, y)
            y += titleLayout.height + if (thermal) 4f else 8f
            val meta = layout("تاريخ التقرير: ${today()} • المحاسب الذهبي", metaPaint, usableWidth)
            drawCellText(started.canvas, meta, margin, y)
            y += meta.height + if (thermal) 6f else 12f
            started.canvas.drawLine(margin, y, pageWidth - margin, y, borderPaint)
            y += if (thermal) 4f else 8f

            if (columns.isNotEmpty()) {
                val layouts = headerLayouts()
                val headerHeight = max(if (thermal) 18f else 24f, layouts.maxOfOrNull { it.height.toFloat() } ?: 0f + padding * 2)
                drawColumnCells(started.canvas, columns, y, layouts, headerHeight, headerFill)
                y += headerHeight
            }
        }

        fun rowValues(cells: List<String>): List<String> =
            (0 until tableColumnCount).map { cells.getOrNull(it).orEmpty() }

        fun drawRow(cells: List<String>) {
            val values = rowValues(cells)
            val layouts = values.mapIndexed { i, value ->
                layout(value, textPaint, (columnWidth[i] - padding * 2).toInt())
            }
            val rowHeight = max(if (thermal) 18f else 22f, (layouts.maxOfOrNull { it.height.toFloat() } ?: 0f) + padding * 2)
            if (page == null || y + rowHeight > bottom) {
                page?.let { document.finishPage(it) }
                startPage()
            }
            val target = canvas ?: return
            drawColumnCells(target, values, y, layouts, rowHeight, null)
            y += rowHeight
        }

        try {
            startPage()
            if (rows.isEmpty()) {
                drawRow(if (tableColumnCount == 1) listOf("لا توجد بيانات لعرضها.") else listOf("لا توجد بيانات لعرضها.", ""))
            } else {
                rows.forEach { drawRow(it) }
            }
            page?.let { document.finishPage(it) }
            page = null
            document.writeTo(output)
        } finally {
            page?.let { runCatching { document.finishPage(it) } }
            document.close()
        }
    }
}
