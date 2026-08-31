package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a *photo of ink on paper* into a signature: a transparent PNG containing only the
 * strokes.
 *
 * Why not a plain brightness threshold? Because a phone photo of paper is never evenly lit —
 * one corner is always in shadow, and any single cutoff either eats the strokes on the bright
 * side or floods the dark side with grey. So each pixel is compared to the *local* paper
 * brightness around it ([IntegralImage]), and how much darker it is becomes its **alpha**:
 *
 * ```
 * darkness = localPaperBrightness - pixelBrightness
 * alpha    = (darkness - floor) / softness      // clamped to 0..1
 * ```
 *
 * Using a ramp rather than a yes/no decision keeps the anti-aliased stroke edges, so the
 * result doesn't look like a fax when it is scaled up on a page.
 */
object SignatureExtractor {

    private const val WORK_DIM = 1400
    private const val SOFTNESS = 28f
    private const val INK_ALPHA_FOR_BOUNDS = 0.4f

    /**
     * @param sensitivity 0f..1f — higher picks up fainter pencil at the cost of more paper
     *                    texture and stray rules from lined paper.
     * @return a cropped, transparent-background bitmap, or null if no ink was found.
     */
    fun extract(src: Bitmap, sensitivity: Float = 0.5f): Bitmap? {
        val work = downscale(src)
        val w = work.width
        val h = work.height
        if (w < 8 || h < 8) return null

        val grey = greyscale(work)
        val integral = IntegralImage(grey, w, h)
        // A window a good deal wider than a pen stroke, so the "paper" estimate is paper.
        val radius = max(12, minOf(w, h) / 8)
        // High sensitivity -> a low floor -> faint marks still count as ink.
        val floor = 55f - sensitivity.coerceIn(0f, 1f) * 45f

        val px = IntArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val darkness = integral.mean(x, y, radius) - grey[i]
                val alpha = ((darkness - floor) / SOFTNESS).coerceIn(0f, 1f)
                px[i] = ((alpha * 255f).roundToInt() shl 24)   // black ink, variable alpha
                if (alpha >= INK_ALPHA_FOR_BOUNDS) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (work !== src) work.recycle()
        if (maxX < minX || maxY < minY) return null

        // Trim to the ink, with a small breathing margin.
        val padX = ((maxX - minX) * 0.04f).toInt() + 4
        val padY = ((maxY - minY) * 0.10f).toInt() + 4
        val x0 = (minX - padX).coerceAtLeast(0)
        val y0 = (minY - padY).coerceAtLeast(0)
        val x1 = (maxX + padX).coerceAtMost(w - 1)
        val y1 = (maxY + padY).coerceAtMost(h - 1)
        val outW = x1 - x0 + 1
        val outH = y1 - y0 + 1
        if (outW < 8 || outH < 4) return null

        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            System.arraycopy(px, (y0 + y) * w + x0, out, y * outW, outW)
        }
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    /** PNG keeps the alpha channel; JPEG would flatten it onto black. */
    fun writePng(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= WORK_DIM) return src
        val scale = WORK_DIM.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
        )
    }
}
