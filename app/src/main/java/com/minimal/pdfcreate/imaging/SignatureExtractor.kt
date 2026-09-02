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
     * Ink neighbours a pixel needs before it counts towards the bounds.
     *
     * Two, because that is what tells a stroke from a speck: ink runs, so even a hairline
     * ascender one pixel wide has the pixel above and the pixel below it. A fleck of paper
     * texture has nobody.
     */
    private const val MIN_NEIGHBOURS = 2

    /** Share of the surviving ink still trimmed off each edge, for the odd noise cluster. */
    private const val EDGE_TRIM = 0.002f

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
        val alpha = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val darkness = integral.mean(x, y, radius) - grey[i]
                val a = ((darkness - floor) / SOFTNESS).coerceIn(0f, 1f)
                alpha[i] = a
                px[i] = ((a * 255f).roundToInt() shl 24)   // black ink, variable alpha
            }
        }
        if (work !== src) work.recycle()

        val (minX, minY, maxX, maxY) = inkBounds(alpha, w, h) ?: return null

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

    /** Left, top, right, bottom of the ink, inclusive. Destructured by [extract]. */
    internal data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    /**
     * Where the ink actually is, ignoring speckle.
     *
     * The obvious version — the bounding box of every pixel above [INK_ALPHA_FOR_BOUNDS] —
     * is decided by its four most extreme pixels, so a single fleck of paper texture in a
     * corner stretches the box to that corner. Raising the ink sensitivity produces exactly
     * such flecks, which is why the result would suddenly balloon to the whole selection
     * while the slider moved.
     *
     * So a pixel only votes if it has [MIN_NEIGHBOURS] ink neighbours — it has to be part of
     * a stroke, not a lone dot — and then the surviving ink is projected onto each axis and
     * the outermost [EDGE_TRIM] of its mass trimmed, which catches the occasional noise
     * cluster that is big enough to support itself.
     */
    internal fun inkBounds(alpha: FloatArray, w: Int, h: Int): Bounds? {
        val rows = FloatArray(h)
        val cols = FloatArray(w)
        var total = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = alpha[y * w + x]
                if (a < INK_ALPHA_FOR_BOUNDS) continue
                var neighbours = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        if (alpha[ny * w + nx] >= INK_ALPHA_FOR_BOUNDS) neighbours++
                    }
                }
                if (neighbours < MIN_NEIGHBOURS) continue
                rows[y] += a
                cols[x] += a
                total += a
            }
        }
        if (total <= 0f) return null

        val drop = total * EDGE_TRIM

        fun first(profile: FloatArray): Int {
            var run = 0f
            for (i in profile.indices) {
                run += profile[i]
                if (run > drop) return i
            }
            return profile.size - 1
        }

        fun last(profile: FloatArray): Int {
            var run = 0f
            for (i in profile.indices.reversed()) {
                run += profile[i]
                if (run > drop) return i
            }
            return 0
        }

        val minX = first(cols)
        val maxX = last(cols)
        val minY = first(rows)
        val maxY = last(rows)
        if (maxX < minX || maxY < minY) return null
        return Bounds(minX, minY, maxX, maxY)
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
