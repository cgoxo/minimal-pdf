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

    /** Applies the full filter (tone matrix + any per-pixel mode) into a new bitmap. */
    fun apply(src: Bitmap, s: FilterSettings): Bitmap {
        val toned = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(toned).drawBitmap(src, 0f, 0f, Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(matrixValues(s))
        })
        return when (s.mode) {
            FilterMode.ORIGINAL, FilterMode.GRAYSCALE -> toned
            FilterMode.BW -> binarise(toned, s.threshold).also { toned.recycle() }
            FilterMode.DOCUMENT -> document(toned, s.threshold).also { toned.recycle() }
        }
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
