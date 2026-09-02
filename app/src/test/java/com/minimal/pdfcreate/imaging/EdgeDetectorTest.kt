package com.minimal.pdfcreate.imaging

import com.minimal.pdfcreate.data.Quad
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Exercises the detector's maths directly, on synthetic frames, with no device involved.
 *
 * `detectFromGray` is the whole algorithm minus the two Android-specific ways of filling a
 * grey array, so it can be driven from a plain JVM test. Reached by reflection rather than
 * being widened to internal: the entry points callers use are the public ones.
 */
class EdgeDetectorTest {

    private val detectFromGray = EdgeDetector::class.java
        .getDeclaredMethod("detectFromGray", FloatArray::class.java, Int::class.java, Int::class.java)
        .apply { isAccessible = true }

    private fun detect(gray: FloatArray, w: Int, h: Int): Quad? =
        detectFromGray.invoke(EdgeDetector, gray, w, h) as Quad?

    /** A [page] rectangle of one brightness on a [background] of another, plus a little noise. */
    private fun frame(
        w: Int = 240,
        h: Int = 320,
        left: Int = 40,
        top: Int = 50,
        right: Int = 200,
        bottom: Int = 270,
        page: Float = 220f,
        background: Float = 60f,
        noise: Float = 3f,
        content: Boolean = false,
    ): FloatArray {
        val rng = Random(7)
        val g = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inside = x in left until right && y in top until bottom
                var v = if (inside) page else background
                // Dense high-contrast "print" on the page: the thing that used to drag the
                // global threshold up above the page's own border.
                if (content && inside && (y - top) % 14 < 4 && x > left + 12 && x < right - 12) {
                    v = if (page > background) 10f else 245f
                }
                g[y * w + x] = v + rng.nextFloat() * noise
            }
        }
        return g
    }

    private fun assertCloseTo(quad: Quad, l: Float, t: Float, r: Float, b: Float, tol: Float = 0.06f) {
        val xs = quad.toList().map { it.x }
        val ys = quad.toList().map { it.y }
        assertTrue("left ${xs.min()} vs $l", abs(xs.min() - l) < tol)
        assertTrue("right ${xs.max()} vs $r", abs(xs.max() - r) < tol)
        assertTrue("top ${ys.min()} vs $t", abs(ys.min() - t) < tol)
        assertTrue("bottom ${ys.max()} vs $b", abs(ys.max() - b) < tol)
    }

    @Test
    fun `finds a light page on a dark surface`() {
        val quad = detect(frame(), 240, 320)
        assertNotNull("no page found", quad)
        assertCloseTo(quad!!, 40 / 240f, 50 / 320f, 200 / 240f, 270 / 320f)
    }

    @Test
    fun `finds a dark page on a light surface`() {
        val quad = detect(frame(page = 55f, background = 225f), 240, 320)
        assertNotNull("no page found", quad)
        assertCloseTo(quad!!, 40 / 240f, 50 / 320f, 200 / 240f, 270 / 320f)
    }

    /** The regression this rework is for: heavy print must not outvote the page border. */
    @Test
    fun `finds the border of a page covered in print`() {
        val quad = detect(frame(content = true), 240, 320)
        assertNotNull("no page found", quad)
        assertCloseTo(quad!!, 40 / 240f, 50 / 320f, 200 / 240f, 270 / 320f)
    }

    /** Low contrast — white paper on a pale desk — is the case that used to be missed. */
    @Test
    fun `finds a page that barely contrasts with the surface`() {
        val quad = detect(frame(page = 210f, background = 175f), 240, 320)
        assertNotNull("no page found", quad)
        assertCloseTo(quad!!, 40 / 240f, 50 / 320f, 200 / 240f, 270 / 320f)
    }

    @Test
    fun `returns null for a frame with no page in it`() {
        val rng = Random(11)
        val g = FloatArray(240 * 320) { 128f + rng.nextFloat() * 8f }
        assertNull(detect(g, 240, 320))
    }
}
