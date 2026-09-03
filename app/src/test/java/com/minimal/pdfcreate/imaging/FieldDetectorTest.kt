package com.minimal.pdfcreate.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape-finding half of the form-field detector, on synthetic ink masks.
 *
 * These stand in for the cases a real form throws at it: a rule to write on, a box to write
 * in, a rule somebody has already used, and a page of prose that contains no fields at all
 * and must not be turned into a hundred of them.
 */
class FieldDetectorTest {

    private val w = 400
    private val h = 560

    private fun blank() = BooleanArray(w * h)

    private fun BooleanArray.rule(y: Int, x0: Int, x1: Int, thickness: Int = 2) {
        for (t in 0 until thickness) for (x in x0..x1) this[(y + t) * w + x] = true
    }

    private fun BooleanArray.vertical(x: Int, y0: Int, y1: Int) {
        for (y in y0..y1) this[y * w + x] = true
    }

    /** Scribble, as an answer written into a field would look. */
    private fun BooleanArray.scribble(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0..y1) for (x in x0..x1) if ((x + y) % 3 == 0) this[y * w + x] = true
    }

    @Test
    fun `finds a rule to write on, and puts the field above it`() {
        val ink = blank()
        ink.rule(y = 300, x0 = 60, x1 = 340)
        val fields = FieldDetector.detectFromMask(ink, w, h)
        assertEquals(1, fields.size)
        val f = fields.single()
        assertEquals(FieldDetector.Kind.LINE, f.kind)
        assertTrue("field should sit above the rule", f.rect.bottom <= 300f / h)
        assertTrue("field should be near the rule", f.rect.bottom > 280f / h)
        assertTrue("width should follow the rule", f.rect.width > 0.6f)
    }

    @Test
    fun `finds a closed box and offers its inside`() {
        val ink = blank()
        ink.rule(y = 200, x0 = 80, x1 = 320, thickness = 1)
        ink.rule(y = 260, x0 = 80, x1 = 320, thickness = 1)
        ink.vertical(x = 80, y0 = 200, y1 = 260)
        ink.vertical(x = 320, y0 = 200, y1 = 260)
        val fields = FieldDetector.detectFromMask(ink, w, h)
        assertEquals(1, fields.size)
        val f = fields.single()
        assertEquals(FieldDetector.Kind.BOX, f.kind)
        assertTrue("inside the box vertically", f.rect.top > 200f / h && f.rect.bottom < 260f / h)
    }

    /** The point of the emptiness check: a field already answered is not on offer. */
    @Test
    fun `skips a rule that has already been written on`() {
        val ink = blank()
        ink.rule(y = 300, x0 = 60, x1 = 340)
        ink.scribble(x0 = 70, y0 = 285, x1 = 330, y1 = 298)
        assertTrue(FieldDetector.detectFromMask(ink, w, h).isEmpty())
    }

    /** A page of text has plenty of ink but no rules, and must yield nothing. */
    @Test
    fun `finds nothing on a page of prose`() {
        val ink = blank()
        var y = 40
        while (y < h - 40) {
            // Broken-up "words", never a continuous span.
            var x = 40
            while (x < w - 40) {
                val wordLen = 6 + (x + y) % 14
                for (dx in 0 until wordLen) for (t in 0 until 6) {
                    if ((dx + t) % 2 == 0) ink[(y + t) * w + x + dx] = true
                }
                x += wordLen + 7
            }
            y += 16
        }
        assertTrue(FieldDetector.detectFromMask(ink, w, h).isEmpty())
    }

    /** A solid black banner is not something you can write on. */
    @Test
    fun `ignores a thick printed bar`() {
        val ink = blank()
        ink.rule(y = 100, x0 = 40, x1 = 360, thickness = 20)
        assertTrue(FieldDetector.detectFromMask(ink, w, h).isEmpty())
    }

    @Test
    fun `finds every rule on a short form`() {
        val ink = blank()
        ink.rule(y = 150, x0 = 60, x1 = 340)
        ink.rule(y = 220, x0 = 60, x1 = 340)
        ink.rule(y = 290, x0 = 60, x1 = 220)
        val fields = FieldDetector.detectFromMask(ink, w, h)
        assertEquals(3, fields.size)
        assertTrue(fields.all { it.kind == FieldDetector.Kind.LINE })
    }
}
