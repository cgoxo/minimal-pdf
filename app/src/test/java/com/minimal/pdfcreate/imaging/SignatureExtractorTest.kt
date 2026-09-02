package com.minimal.pdfcreate.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The ink-bounds trim, on synthetic alpha maps.
 *
 * This is the part that decides how big an extracted signature comes out, and the part that
 * used to be thrown off by a single speckle in a corner.
 */
class SignatureExtractorTest {

    private val w = 200
    private val h = 100

    /** A dense horizontal "stroke" band, as a real signature's body would be. */
    private fun withStroke(
        left: Int = 60,
        top: Int = 40,
        right: Int = 140,
        bottom: Int = 60,
    ): FloatArray {
        val a = FloatArray(w * h)
        for (y in top..bottom) for (x in left..right) a[y * w + x] = 1f
        return a
    }

    @Test
    fun `finds the stroke it is given`() {
        val b = SignatureExtractor.inkBounds(withStroke(), w, h)
        assertNotNull(b)
        assertEquals(60, b!!.minX)
        assertEquals(140, b.maxX)
        assertEquals(40, b.minY)
        assertEquals(60, b.maxY)
    }

    /** The reported bug: a fleck in the corner used to stretch the box to the whole crop. */
    @Test
    fun `ignores an isolated speckle in the corner`() {
        val a = withStroke()
        a[2 * w + 3] = 1f          // top-left fleck
        a[(h - 3) * w + (w - 4)] = 1f  // bottom-right fleck
        val b = SignatureExtractor.inkBounds(a, w, h)
        assertNotNull(b)
        assertTrue("left ${b!!.minX} pulled to the corner", b.minX >= 55)
        assertTrue("right ${b.maxX} pulled to the corner", b.maxX <= 145)
        assertTrue("top ${b.minY} pulled to the corner", b.minY >= 35)
        assertTrue("bottom ${b.maxY} pulled to the corner", b.maxY <= 65)
    }

    /** Sensitivity turned up: scattered paper texture everywhere, plus the real stroke. */
    @Test
    fun `keeps the stroke when paper texture is speckled across the frame`() {
        val a = withStroke()
        val rng = Random(3)
        repeat(300) { a[rng.nextInt(h) * w + rng.nextInt(w)] = 1f }
        val b = SignatureExtractor.inkBounds(a, w, h)
        assertNotNull(b)
        assertTrue("box ${b!!.minX}..${b.maxX} swallowed the frame", b.minX > 20 && b.maxX < 180)
    }

    /**
     * A thin ascender is real ink and must survive. A few pixels off its very tip is fine —
     * the caller pads the box afterwards — but it must not be cut back to the stroke body.
     */
    @Test
    fun `keeps a thin ascender above the body of the stroke`() {
        val a = withStroke()
        for (y in 12 until 40) a[y * w + 100] = 1f
        val b = SignatureExtractor.inkBounds(a, w, h)
        assertNotNull(b)
        assertTrue("ascender at y=12 was trimmed back to ${b!!.minY}", b.minY <= 20)
    }

    @Test
    fun `returns null for a blank frame`() {
        assertNull(SignatureExtractor.inkBounds(FloatArray(w * h), w, h))
    }

    /** Alpha below the ink cutoff is paper, not faint ink, and must not move the bounds. */
    @Test
    fun `ignores sub-threshold haze`() {
        val a = withStroke()
        for (i in a.indices) if (a[i] == 0f) a[i] = 0.2f
        val b = SignatureExtractor.inkBounds(a, w, h)
        assertNotNull(b)
        assertEquals(60, b!!.minX)
        assertEquals(140, b.maxX)
    }
}
