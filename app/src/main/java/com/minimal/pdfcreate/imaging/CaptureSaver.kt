package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * CameraX hands us JPEG bytes plus a rotation, and records that rotation in EXIF rather
 * than rotating pixels. BitmapFactory ignores EXIF, so we normalise here once: decode
 * downscaled, rotate upright, re-encode. Everything downstream can then assume the file
 * is already the right way up.
 */
object CaptureSaver {

    private const val MAX_DIM = 2600

    fun save(jpeg: ByteArray, rotationDegrees: Int, target: File): Boolean {
        val bitmap = decodeUpright(jpeg, rotationDegrees) ?: return false
        FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        bitmap.recycle()
        return true
    }

    /** Same normalisation, but handing back the bitmap instead of a file. */
    fun decodeUpright(jpeg: ByteArray, rotationDegrees: Int, maxDim: Int = MAX_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2

        var bitmap = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val longest = max(bitmap.width, bitmap.height)
        if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
            )
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }
}
