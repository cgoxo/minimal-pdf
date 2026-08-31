package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap

/** ITU-R 601 luma. The same weights the eye-ish grey conversion uses everywhere else here. */
fun greyscale(bitmap: Bitmap): IntArray {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    val grey = IntArray(w * h)
    for (i in px.indices) {
        val p = px[i]
        grey[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
    return grey
}

/**
 * Summed-area table (integral image): `sat[y][x]` holds the sum of every pixel above and to
 * the left of (x, y). The sum of *any* rectangle is then four lookups:
 *
 * ```
 * sum(x0..x1, y0..y1) = S[y1+1][x1+1] - S[y0][x1+1] - S[y1+1][x0] + S[y0][x0]
 * ```
 *
 * So a local average costs the same whether the window is 8 px or 800 px wide — which is what
 * makes shadow-flattening (Document filter) and ink lifting (signature scan) cheap enough to
 * run on a slider drag.
 */
class IntegralImage(private val grey: IntArray, private val width: Int, private val height: Int) {

    private val sat = LongArray((width + 1) * (height + 1))

    init {
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += grey[y * width + x]
                sat[(y + 1) * (width + 1) + (x + 1)] = sat[y * (width + 1) + (x + 1)] + rowSum
            }
        }
    }

    /** Mean brightness of the square window of side `2 * radius + 1` centred on (x, y). */
    fun mean(x: Int, y: Int, radius: Int): Int {
        val x0 = (x - radius).coerceAtLeast(0)
        val x1 = (x + radius).coerceAtMost(width - 1)
        val y0 = (y - radius).coerceAtLeast(0)
        val y1 = (y + radius).coerceAtMost(height - 1)
        val area = ((x1 - x0 + 1) * (y1 - y0 + 1)).toLong()
        val sum = sat[(y1 + 1) * (width + 1) + (x1 + 1)] -
            sat[y0 * (width + 1) + (x1 + 1)] -
            sat[(y1 + 1) * (width + 1) + x0] +
            sat[y0 * (width + 1) + x0]
        return (sum / area).toInt()
    }
}
