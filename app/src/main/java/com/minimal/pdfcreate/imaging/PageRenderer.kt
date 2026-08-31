package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.minimal.pdfcreate.data.DocumentRepository
import com.minimal.pdfcreate.data.Overlay
import com.minimal.pdfcreate.data.Page
import com.minimal.pdfcreate.data.Quad
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

/**
 * The single render pipeline, shared by the editor preview, the page thumbnails and the
 * PDF export:
 *
 *   decode -> perspective crop -> rotate -> filter -> strokes -> overlays
 *
 * Because every edit is stored as data on [Page], re-editing a page later is just running
 * this again with different numbers — nothing is ever destructively baked into the source.
 */
object PageRenderer {

    const val EDIT_DIM = 1600
    const val EXPORT_DIM = 3000
    const val THUMB_DIM = 400

    fun decode(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= maxDim) return decoded
        val scale = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    /**
     * Perspective-corrects [src] to the rectangle implied by [quad].
     *
     * `Matrix.setPolyToPoly` with four point pairs is a full homography — Skia does the
     * warp for us, which is both shorter and far faster than sampling by hand in Kotlin.
     */
    fun crop(src: Bitmap, quad: Quad?): Bitmap {
        if (quad == null || quad == Quad.FULL) return src
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val p = quad.toList().map { floatArrayOf(it.x * w, it.y * h) }

        val topLen = hypot(p[1][0] - p[0][0], p[1][1] - p[0][1])
        val bottomLen = hypot(p[2][0] - p[3][0], p[2][1] - p[3][1])
        val leftLen = hypot(p[3][0] - p[0][0], p[3][1] - p[0][1])
        val rightLen = hypot(p[2][0] - p[1][0], p[2][1] - p[1][1])
        val outW = max(topLen, bottomLen).toInt().coerceIn(16, 6000)
        val outH = max(leftLen, rightLen).toInt().coerceIn(16, 6000)

        val srcPts = floatArrayOf(
            p[0][0], p[0][1], p[1][0], p[1][1], p[2][0], p[2][1], p[3][0], p[3][1]
        )
        val dstPts = floatArrayOf(
            0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()
        )
        val matrix = Matrix().apply { setPolyToPoly(srcPts, 0, dstPts, 0, 4) }

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    /**
     * Source image after rotation + crop, before any filter or annotation.
     *
     * Rotation is applied *first* and the crop quad is stored relative to the rotated frame.
     * That ordering is what lets the crop editor show a rotation immediately, with the corner
     * handles still sitting on the right part of the page.
     */
    fun base(repo: DocumentRepository, docId: String, page: Page, maxDim: Int): Bitmap? {
        val src = decode(repo.imageFile(docId, page.imageName), maxDim) ?: return null
        val rotated = rotate(src, page.rotation)
        val cropped = crop(rotated, page.crop)
        if (cropped !== rotated) rotated.recycle()
        return cropped
    }

    /** The finished page: everything applied. */
    fun render(repo: DocumentRepository, docId: String, page: Page, maxDim: Int): Bitmap? {
        val base = base(repo, docId, page, maxDim) ?: return null
        val filtered = Filters.apply(base, page.filter)
        if (filtered != base) base.recycle()
        val out = filtered.copy(Bitmap.Config.ARGB_8888, true)
        filtered.recycle()
        drawAnnotations(Canvas(out), out.width, out.height, page, repo)
        return out
    }

    /** Strokes and overlays, drawn in normalised space so they scale to any output size. */
    fun drawAnnotations(canvas: Canvas, w: Int, h: Int, page: Page, repo: DocumentRepository) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in page.strokes) {
            if (stroke.points.isEmpty()) continue
            paint.color = stroke.color.toInt()
            paint.strokeWidth = stroke.widthN * w
            if (stroke.points.size == 1) {
                val p = stroke.points.first()
                canvas.drawPoint(p.x * w, p.y * h, Paint(paint).apply { style = Paint.Style.FILL })
                continue
            }
            val path = Path()
            path.moveTo(stroke.points[0].x * w, stroke.points[0].y * h)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x * w, stroke.points[i].y * h)
            }
            canvas.drawPath(path, paint)
        }

        for (overlay in page.overlays) {
            when (overlay) {
                is Overlay.Text -> {
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = overlay.color.toInt()
                        textSize = overlay.sizeN * h
                        isFakeBoldText = false
                    }
                    var y = overlay.posN.y * h
                    for (line in overlay.text.split("\n")) {
                        canvas.drawText(line, overlay.posN.x * w, y, textPaint)
                        y += textPaint.textSize * 1.2f
                    }
                }

                is Overlay.Signature -> {
                    val file = File(repo.signaturesDir, overlay.fileName)
                    val sig = decode(file, 1200) ?: continue
                    val targetW = overlay.widthN * w
                    val targetH = targetW * sig.height / sig.width
                    val left = overlay.posN.x * w
                    val top = overlay.posN.y * h
                    val dst = android.graphics.RectF(left, top, left + targetW, top + targetH)
                    val saved = canvas.save()
                    if (overlay.rotation != 0f) {
                        canvas.rotate(overlay.rotation, left + targetW / 2f, top + targetH / 2f)
                    }
                    canvas.drawBitmap(sig, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.restoreToCount(saved)
                    sig.recycle()
                }
            }
        }
    }
}
