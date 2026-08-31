package com.minimal.pdfcreate.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.minimal.pdfcreate.data.DocumentRepository
import com.minimal.pdfcreate.data.PdfStore
import com.minimal.pdfcreate.data.ScanDocument
import com.minimal.pdfcreate.imaging.PageRenderer

/**
 * Writes the document with the platform's own [PdfDocument] — no third-party PDF library.
 *
 * PDF user space is in points (1/72 inch), so an A4 page is 595 x 842 regardless of the
 * bitmap resolution; the bitmap is embedded at its own pixel density and merely *placed*
 * into that rectangle, which is why a 2200 px scan still prints sharp.
 */
object PdfExporter {

    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842
    private const val MARGIN_PT = 18

    fun export(context: Context, repo: DocumentRepository, doc: ScanDocument): Uri {
        require(doc.pages.isNotEmpty()) { "Refusing to export a document with no pages" }
        val pdf = PdfDocument()
        try {
            doc.pages.forEachIndexed { index, page ->
                val bitmap = PageRenderer.render(repo, doc.id, page, PageRenderer.EXPORT_DIM)
                val landscape = bitmap != null && bitmap.width > bitmap.height
                val pageWidth = if (landscape) A4_HEIGHT_PT else A4_WIDTH_PT
                val pageHeight = if (landscape) A4_WIDTH_PT else A4_HEIGHT_PT

                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = pdf.startPage(info)
                if (bitmap != null) {
                    val availW = pageWidth - 2 * MARGIN_PT
                    val availH = pageHeight - 2 * MARGIN_PT
                    val scale = minOf(availW.toFloat() / bitmap.width, availH.toFloat() / bitmap.height)
                    val drawW = (bitmap.width * scale).toInt()
                    val drawH = (bitmap.height * scale).toInt()
                    val left = (pageWidth - drawW) / 2
                    val top = (pageHeight - drawH) / 2
                    pdfPage.canvas.drawBitmap(
                        bitmap, null, Rect(left, top, left + drawW, top + drawH),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    bitmap.recycle()
                }
                pdf.finishPage(pdfPage)
            }

            val name = fileName(doc)
            return PdfStore.write(context, name) { out -> pdf.writeTo(out) }
        } finally {
            pdf.close()
        }
    }

    fun fileName(doc: ScanDocument): String =
        doc.pdfFileName ?: (doc.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".pdf")
}
