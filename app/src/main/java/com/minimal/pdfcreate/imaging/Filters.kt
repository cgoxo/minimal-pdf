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
        // Colour doc is driven by its ink knob too, but the whole point is that the colour
        // survives — so saturation is the one tone control it keeps.
        if (s.mode == FilterMode.COLOR_DOC) {
            return ColorMatrix().apply { setSaturation(s.saturation) }.array
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

    /** True when this matrix would copy every pixel to itself. */
    private fun isIdentity(m: FloatArray): Boolean {
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                val expected = if (row == col) 1f else 0f
                if (kotlin.math.abs(m[row * 5 + col] - expected) > 1e-4f) return false
            }
        }
        return true
    }

    /** Applies the full filter (tone matrix + any per-pixel mode) into a new bitmap. */
    fun apply(src: Bitmap, s: FilterSettings): Bitmap {
        val matrix = matrixValues(s)
        // Colour doc leaves the tone matrix alone, so this would otherwise allocate a second
        // full bitmap and draw the whole image into it to change nothing. On the small bitmap
        // the filter sliders preview against, that copy was a meaningful part of the cost of
        // every single slider position.
        val toned = if (isIdentity(matrix)) {
            src
        } else {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
                    isFilterBitmap = true
                    colorFilter = ColorMatrixColorFilter(matrix)
                })
            }
        }
        // `toned` may now be `src` itself, which belongs to the caller — so it is only ever
        // recycled when we made it.
        val ownsToned = toned !== src
        val processed = when (s.mode) {
            FilterMode.ORIGINAL, FilterMode.GRAYSCALE ->
                if (ownsToned) toned else copyOf(toned)
            FilterMode.BW -> binarise(toned, s.threshold).also { if (ownsToned) toned.recycle() }
            FilterMode.DOCUMENT -> document(toned, s.threshold).also { if (ownsToned) toned.recycle() }
            FilterMode.COLOR_DOC -> colourDocument(toned, s.threshold).also { if (ownsToned) toned.recycle() }
        }
        return processed
    }

    /** Callers own and recycle what [apply] returns, so it must never hand back its input. */
    private fun copyOf(src: Bitmap): Bitmap =
        src.copy(src.config ?: Bitmap.Config.ARGB_8888, false) ?: src

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

    /**
     * "Colour doc": [document]'s shadow flattening, but colour-preserving.
     *
     * Document computes a new grey value per pixel and writes it to all three channels, which
     * is what throws the colour away. Here the same lifted value is turned into a *gain*
     * against the pixel's own luma, and that one gain multiplies R, G and B together:
     *
     *     gain = lifted / luma        out = (r, g, b) * gain
     *
     * Scaling all three channels by the same number leaves their ratios — and so the hue —
     * untouched, while paper that was grey at luma 190 is pushed to 255. The result reads as a
     * scan rather than a photo, but a red stamp is still red.
     */
    private fun colourDocument(src: Bitmap, ink: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // Luma is derived from the pixels already in hand. Calling `greyscale(src)` here would
        // pull the whole image out of the bitmap a second time for no new information.
        val grey = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            grey[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val integral = IntegralImage(grey, w, h)
        val radius = maxOf(8, minOf(w, h) / 16)
        val white = (200f + ink.coerceIn(0f, 1f) * 55f).toInt().coerceAtLeast(1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val luma = grey[i]
                // A pixel with no light in it has no colour to preserve, and dividing by its
                // luma would be a divide by zero — leave it black.
                if (luma <= 0) {
                    px[i] = 0xFF000000.toInt()
                    continue
                }
                val mean = integral.mean(x, y, radius).coerceAtLeast(1)
                val v = (luma * 255 / mean).coerceIn(0, 255)
                val lifted = if (v >= white) 255 else (v * 255 / white).coerceIn(0, 255)

                val p = px[i]
                fun channel(shift: Int) =
                    (((p shr shift) and 0xFF) * lifted / luma).coerceIn(0, 255)
                px[i] = 0xFF000000.toInt() or
                    (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
