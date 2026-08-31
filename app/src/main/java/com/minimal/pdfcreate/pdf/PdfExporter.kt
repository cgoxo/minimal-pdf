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

    fun export(context: Context, repo: DocumentRepository, doc: ScanDocument): Uri {
        require(doc.pages.isNotEmpty()) { "Refusing to export a document with no pages" }
        val pdf = PdfDocument()
        try {
            doc.pages.forEachIndexed { index, page ->
                val bitmap = PageRenderer.render(repo, doc.id, page, PageRenderer.EXPORT_DIM)

                // The page is cut to the *scan's* proportions rather than forced onto a fixed
                // A4 sheet, and the image is drawn edge to edge. Anything else would frame the
                // scan in white, which is exactly what a scanner should never do.
                val (pageWidth, pageHeight) = if (bitmap == null) {
                    A4_WIDTH_PT to A4_HEIGHT_PT
                } else {
                    val scale = minOf(
                        A4_WIDTH_PT.toFloat() / bitmap.width,
                        A4_HEIGHT_PT.toFloat() / bitmap.height,
                    )
                    (bitmap.width * scale).toInt().coerceAtLeast(1) to
                        (bitmap.height * scale).toInt().coerceAtLeast(1)
                }

                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = pdf.startPage(info)
                if (bitmap != null) {
                    pdfPage.canvas.drawBitmap(
                        bitmap, null, Rect(0, 0, pageWidth, pageHeight),
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
