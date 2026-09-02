package com.minimal.pdfcreate.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import com.minimal.pdfcreate.data.DocumentRepository
import com.minimal.pdfcreate.data.FilterMode
import com.minimal.pdfcreate.data.FilterSettings
import com.minimal.pdfcreate.data.Page
import java.io.ByteArrayInputStream
import java.io.FileOutputStream

/**
 * Bringing outside material in as pages.
 *
 * Both paths end at the same place a capture does — a JPEG in the document folder plus a
 * [Page] entry — so an imported photo or PDF page is editable, filterable and re-croppable
 * exactly like something scanned through the camera. Nothing here needs a storage
 * permission: the pickers hand back a uri that is already readable.
 *
 * Every call touches disk and decodes bitmaps, so callers must run it off the main thread.
 */
object Importer {

    /**
     * Imports arrive as-is. A capture defaults to the inked look because it is a photo of
     * paper under room light; a picked image or an already-clean PDF page is neither, and
     * re-inking one would be undoing work somebody already did.
     */
    private val UNFILTERED = FilterSettings(mode = FilterMode.ORIGINAL)

    /** PDF pages are measured in points; 200 dpi is the usual scanner default. */
    private const val PDF_DPI = 200f
    private const val PDF_MAX_DIM = 2600

    /**
     * Copies each picked image in as a page, returning how many landed.
     *
     * Deliberately no auto-crop: a picture chosen from the gallery is as likely to be an
     * already-cropped screenshot as a photo of a page on a desk, and guessing a page boundary
     * that is not there would crop the image to nothing. The crop tool is one tap away.
     */
    fun importImages(
        context: Context,
        repo: DocumentRepository,
        docId: String,
        uris: List<Uri>,
    ): Int {
        var added = 0
        val pages = mutableListOf<Page>()
        uris.forEach { uri ->
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@forEach

            val file = repo.newImageFile(docId)
            // The picker hands back the file as shot: portrait photos are landscape pixels
            // plus an EXIF orientation tag. CaptureSaver already knows how to normalise that,
            // so it only needs the rotation reading the tag implies.
            if (CaptureSaver.save(bytes, exifRotation(bytes), file)) {
                pages += Page(imageName = file.name, filter = UNFILTERED)
                added++
            }
        }
        if (pages.isNotEmpty()) appendPages(repo, docId, pages)
        return added
    }

    /**
     * Rasterises every page of a picked PDF in as a page image.
     *
     * The pages arrive as pixels, not text — this app's whole model is "a page is an image
     * plus edit instructions", and there is no way to import into that losslessly. 200 dpi is
     * the compromise: sharp enough to re-export and print, small enough to edit smoothly.
     */
    fun importPdf(
        context: Context,
        repo: DocumentRepository,
        docId: String,
        uri: Uri,
    ): Int {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull() ?: return 0

        val pages = mutableListOf<Page>()
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(descriptor)
            for (index in 0 until renderer.pageCount) {
                val page = renderer.openPage(index)
                try {
                    val scale = pageScale(page.width, page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer composites onto whatever is already in the bitmap, and a
                    // fresh one is transparent — which flattens to black in a JPEG. Paper
                    // first, then the page.
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val file = repo.newImageFile(docId)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    bitmap.recycle()
                    pages += Page(imageName = file.name, filter = UNFILTERED)
                } finally {
                    page.close()
                }
            }
        } catch (_: Throwable) {
            // A password-protected or malformed PDF throws from the constructor or mid-render.
            // Whatever rendered before that is still worth keeping, so fall through and save.
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor.close() }
        }

        if (pages.isNotEmpty()) appendPages(repo, docId, pages)
        return pages.size
    }

    private fun pageScale(widthPt: Int, heightPt: Int): Float {
        val dpi = PDF_DPI / 72f
        val longest = maxOf(widthPt, heightPt) * dpi
        return if (longest > PDF_MAX_DIM) PDF_MAX_DIM / maxOf(widthPt, heightPt).toFloat() else dpi
    }

    private fun appendPages(repo: DocumentRepository, docId: String, pages: List<Page>) {
        val doc = repo.getOrNew(docId)
        repo.save(doc.copy(pages = doc.pages + pages))
    }

    private fun exifRotation(bytes: ByteArray): Int =
        runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
}
