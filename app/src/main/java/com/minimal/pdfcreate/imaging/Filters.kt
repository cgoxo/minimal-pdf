package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.minimal.pdfcreate.data.FilterMode
import com.minimal.pdfcreate.data.FilterSettings

/**
 * Tone controls.
 *
 * Brightness/contrast/saturation are a 4x5 [ColorMatrix], so they are free to preview
 * (the GPU applies them while drawing). B&W and Document need real pixel work, so those
 * two run once on a background thread whenever the mode or threshold changes.
 */
object Filters {

    /**
     * The contrast/brightness/saturation part, as the values Android's ColorMatrix wants.
     *
     * B&W and Document are driven by their single "ink" knob instead, so they deliberately
     * ignore brightness/contrast — otherwise a slider you moved in another mode would still
     * be secretly affecting the result.
     */
    fun matrixValues(s: FilterSettings): FloatArray {
        if (s.mode == FilterMode.BW || s.mode == FilterMode.DOCUMENT) {
            return ColorMatrix().apply { setSaturation(0f) }.array
        }
        val c = s.contrast
        // Pivot contrast around mid grey, then add brightness as a 0..255 offset.
        val t = (1f - c) * 127.5f + s.brightness * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val sat = ColorMatrix().apply {
            setSaturation(if (s.mode == FilterMode.ORIGINAL) s.saturation else 0f)
        }
        cm.postConcat(sat)
        return cm.array
    }

    /**
     * Runs a single colour through a ColorMatrix by hand.
     *
     * The eyedropper reads the *source* bitmap, but tone-only modes are previewed by handing
     * the matrix to the GPU at draw time — so without this the sampled colour would not match
     * the colour on screen.
     */
    fun applyMatrixToColor(argb: Int, m: FloatArray): Int {
        val a = ((argb shr 24) and 0xFF).toFloat()
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        fun channel(o: Int) =
            (m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 3] * a + m[o + 4])
                .toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(0) shl 16) or (channel(5) shl 8) or channel(10)
    }

    /** Applies the full filter (tone matrix + any per-pixel mode) into a new bitmap. */
    fun apply(src: Bitmap, s: FilterSettings): Bitmap {
        val toned = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(toned).drawBitmap(src, 0f, 0f, Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(matrixValues(s))
        })
        val processed = when (s.mode) {
            FilterMode.ORIGINAL, FilterMode.GRAYSCALE -> toned
            FilterMode.BW -> binarise(toned, s.threshold).also { toned.recycle() }
            FilterMode.DOCUMENT -> document(toned, s.threshold).also { toned.recycle() }
        }
        // Sharpening a two-tone image only makes the jaggies crisper, so B&W skips it.
        if (s.sharpen <= 0.01f || s.mode == FilterMode.BW) return processed
        return sharpen(processed, s.sharpen).also { if (it !== processed) processed.recycle() }
    }

    /**
     * Unsharp mask: subtract a blurred copy to find the fine detail, then add that detail
     * back, amplified.
     *
     *     detail = pixel - localAverage
     *     out    = pixel + amount * detail
     *
     * The local average comes from the same [IntegralImage] the Document filter uses, with a
     * radius of a few pixels, so it costs one pass regardless of the radius. It is computed on
     * luma and applied to all three channels, which sharpens edges without shifting colour.
     */
    private fun sharpen(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 4 || h < 4) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val grey = greyscale(src)
        val integral = IntegralImage(grey, w, h)
        val radius = maxOf(1, minOf(w, h) / 320)
        val gain = amount.coerceIn(0f, 1f) * 1.8f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val detail = grey[i] - integral.mean(x, y, radius)
                if (detail == 0) continue
                val boost = (detail * gain).toInt()
                val p = px[i]
                val r = (((p shr 16) and 0xFF) + boost).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + boost).coerceIn(0, 255)
                val b = ((p and 0xFF) + boost).coerceIn(0, 255)
                px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Global threshold — hard black/white, smallest files. */
    private fun binarise(src: Bitmap, threshold: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val cut = (threshold * 255f).toInt()
        for (i in px.indices) {
            val p = px[i]
            val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            px[i] = if (lum >= cut) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * "Document" look: divide each pixel by a local mean (a box mean via a summed-area
     * table), which flattens shadows and lifts paper to white while keeping grey text.
     */
    private fun document(src: Bitmap, ink: Float): Bitmap {
        val w = src.width
        val h = src.height
        val grey = greyscale(src)
        val integral = IntegralImage(grey, w, h)
        val radius = maxOf(8, minOf(w, h) / 16)
        val white = (200f + ink.coerceIn(0f, 1f) * 55f).toInt().coerceAtLeast(1)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val mean = integral.mean(x, y, radius).coerceAtLeast(1)
                val v = (grey[y * w + x] * 255 / mean).coerceIn(0, 255)
                // The single ink knob is the white point: low keeps only strong marks and
                // whitens everything else, high preserves faint pencil and paper tone.
                val out = if (v >= white) 255 else (v * 255 / white).coerceIn(0, 255)
                px[y * w + x] = 0xFF000000.toInt() or (out shl 16) or (out shl 8) or out
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
