package com.goldenaccountant.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.OutputStream

/** إنشاء تقارير PDF متعددة الصفحات مع اتجاه RTL وتخطيط نص Android للنص العربي. */
object ReportPdf {
    fun write(
        output: OutputStream,
        title: String,
        columns: List<String>,
        rows: List<List<String>>,
    ) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 34f
        val usableWidth = (pageWidth - margin * 2).toInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 11.5f
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(15, 23, 42)
            textSize = 18f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textSize = 9f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            strokeWidth = 0.7f
        }
        fun layout(text: String, paint: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, usableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun startPage(): PdfDocument.Page {
            pageNumber++
            val started = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            page = started
            canvas = started.canvas
            y = margin
            val titleLayout = layout(title, titlePaint)
            titleLayout.draw(started.canvas.apply { save(); translate(margin, y) })
            started.canvas.restore()
            y += titleLayout.height + 8f
            val meta = layout("تاريخ التقرير: ${today()}    •    المحاسب الذهبي", smallPaint)
            started.canvas.save()
            started.canvas.translate(margin, y)
            meta.draw(started.canvas)
            started.canvas.restore()
            y += meta.height + 12f
            started.canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 8f
            if (columns.isNotEmpty()) {
                val headerText = columns.mapIndexed { index, name -> "${name}: " }.joinToString("     ")
                val header = layout(headerText, titlePaint.apply { textSize = 10.5f })
                started.canvas.save()
                started.canvas.translate(margin, y)
                header.draw(started.canvas)
                started.canvas.restore()
                y += header.height + 6f
                started.canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
                y += 8f
                titlePaint.textSize = 18f
            }
            return started
        }

        fun drawRow(text: String) {
            var block = layout(text, textPaint)
            if (page == null || y + block.height > pageHeight - margin) {
                page?.let { document.finishPage(it) }
                startPage()
                block = layout(text, textPaint)
            }
            val currentCanvas = canvas ?: return
            currentCanvas.save()
            currentCanvas.translate(margin, y)
            block.draw(currentCanvas)
            currentCanvas.restore()
            y += block.height + 7f
            currentCanvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 7f
        }

        try {
            startPage()
            if (rows.isEmpty()) {
                drawRow("لا توجد بيانات لعرضها.")
            } else {
                rows.forEachIndexed { index, cells ->
                    val line = if (columns.isEmpty()) cells.joinToString("   •   ")
                    else cells.mapIndexed { i, cell ->
                        val label = columns.getOrNull(i)
                        if (label.isNullOrBlank()) cell else "$label: $cell"
                    }.joinToString("     |     ")
                    drawRow("${index + 1}. $line")
                }
            }
            page?.let { document.finishPage(it) }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
