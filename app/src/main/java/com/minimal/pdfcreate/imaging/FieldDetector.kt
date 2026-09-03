package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap

/**
 * Finds the blank places on a scanned form that a person is expected to write in.
 *
 * A form marks its fields the same two ways whether it was printed or photocopied: a **rule**
 * to write on top of, and a **box** to write inside. Both are long straight strokes of ink
 * with nothing next to them, which is exactly what this looks for:
 *
 *   local-mean threshold -> ink mask -> long thin horizontal runs -> merge into rules
 *   -> pair rules into boxes where verticals close them -> discard anything already written in
 *
 * The last step is the one that makes it usable. A rule under an answer somebody has already
 * filled in is not a field you want offered, so a candidate whose target area carries more
 * than a trace of ink is dropped.
 *
 * This reads shapes, not characters. It has no idea what any field is *called* — that would
 * need OCR, and this app deliberately carries no text-recognition engine.
 */
object FieldDetector {

    /** A rectangle in normalised page space: 0f..1f on both axes. */
    data class RectN(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    enum class Kind {
        /** A rule to write on top of. */
        LINE,

        /** A closed box to write inside. */
        BOX,
    }

    data class Field(val rect: RectN, val kind: Kind)

    private const val WORK_DIM = 700

    /** A rule has to span this much of the page before it is a field and not a stray mark. */
    private const val MIN_RULE_FRACTION = 0.10f

    /** Thicker than this and it is a printed bar, a table border in bold, or a black edge. */
    private const val MAX_RULE_THICKNESS = 0.006f

    /** How tall the writing space above a rule is taken to be, as a fraction of page height. */
    private const val LINE_FIELD_HEIGHT = 0.028f

    /** A box shorter or taller than this is a cell of something else, not a field. */
    private const val MIN_BOX_HEIGHT = 0.012f
    private const val MAX_BOX_HEIGHT = 0.20f

    /** Above this ink density the space is already written in. */
    private const val MAX_FILL_DENSITY = 0.035f

    /** Enough is enough: a dense table would otherwise offer a hundred of these. */
    private const val MAX_FIELDS = 40

    fun detect(bitmap: Bitmap): List<Field> {
        val work = PageRenderer.scaled(bitmap, WORK_DIM)
        val w = work.width
        val h = work.height
        if (w < 40 || h < 40) return emptyList()

        val grey = greyscale(work)
        val integral = IntegralImage(grey, w, h)
        // A window far wider than any stroke, so the comparison is against paper.
        val radius = maxOf(8, minOf(w, h) / 16)
        val ink = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // Ink is what sits clearly below the local paper brightness. The same trick
                // the Document filter uses, and for the same reason: a photo of a page is
                // never evenly lit, so a single global cutoff would read a shadow as ink.
                ink[i] = grey[i] < integral.mean(x, y, radius) - 28
            }
        }
        if (work !== bitmap) work.recycle()
        return detectFromMask(ink, w, h)
    }

    /**
     * The shape-finding half, over a plain ink mask. Split out so it can be exercised on
     * synthetic forms without a device or a bitmap in sight.
     */
    internal fun detectFromMask(ink: BooleanArray, w: Int, h: Int): List<Field> {
        val runs = horizontalRuns(ink, w, h, (w * MIN_RULE_FRACTION).toInt().coerceAtLeast(8))
        val rules = mergeIntoRules(runs, (h * MAX_RULE_THICKNESS).toInt().coerceAtLeast(1))
        if (rules.isEmpty()) return emptyList()

        val fields = mutableListOf<Field>()
        val usedAsBox = BooleanArray(rules.size)

        // Boxes first: a pair of rules of much the same width, one above the other, with ink
        // running down both ends to join them. Claimed rules are then not offered again as
        // lines, or every box would also advertise its own lid.
        for (i in rules.indices) {
            for (j in i + 1 until rules.size) {
                if (usedAsBox[i] || usedAsBox[j]) continue
                val top = rules[i]
                val bottom = rules[j]
                val gap = bottom.y - top.y
                if (gap < h * MIN_BOX_HEIGHT || gap > h * MAX_BOX_HEIGHT) continue
                if (!alignedWithin(top, bottom, w * 0.04f)) continue
                if (!closedAtEnds(ink, w, h, top, bottom)) continue

                val inner = RectN(
                    (top.x0 + 2f) / w, (top.y + 2f) / h,
                    (top.x1 - 2f) / w, (bottom.y - 2f) / h,
                )
                if (inner.width <= 0f || inner.height <= 0f) continue
                if (density(ink, w, h, inner) > MAX_FILL_DENSITY) continue
                usedAsBox[i] = true
                usedAsBox[j] = true
                fields += Field(inner, Kind.BOX)
            }
        }

        for (i in rules.indices) {
            if (usedAsBox[i]) continue
            val rule = rules[i]
            // The writing space sits *above* a rule, which is the whole point of a rule.
            val fieldH = h * LINE_FIELD_HEIGHT
            val top = (rule.y - fieldH).coerceAtLeast(0f)
            val above = RectN(
                rule.x0.toFloat() / w, top / h,
                rule.x1.toFloat() / w, (rule.y - 1f).coerceAtLeast(0f) / h,
            )
            if (above.height <= 0f || above.width <= 0f) continue
            if (density(ink, w, h, above) > MAX_FILL_DENSITY) continue
            fields += Field(above, Kind.LINE)
        }

        // Biggest first: on a form the long "Name" rule matters more than a stray short one,
        // and the cap has to fall on the least useful candidates.
        return fields
            .sortedByDescending { it.rect.width * it.rect.height }
            .take(MAX_FIELDS)
    }

    /** One horizontal stretch of ink on a single row. */
    private data class Run(val y: Int, val x0: Int, val x1: Int)

    /** A run merged down through the rows it repeats on. */
    private data class Rule(val y: Int, val x0: Int, val x1: Int, val thickness: Int)

    private fun horizontalRuns(ink: BooleanArray, w: Int, h: Int, minLength: Int): List<Run> {
        val runs = mutableListOf<Run>()
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) { x++; continue }
                val start = x
                // A rule survives photocopying with the odd pixel missing, so a one-pixel
                // hole does not end the run.
                var gap = 0
                while (x < w && gap <= 1) {
                    if (ink[y * w + x]) gap = 0 else gap++
                    x++
                }
                val end = x - 1 - gap
                if (end - start + 1 >= minLength) runs += Run(y, start, end)
            }
        }
        return runs
    }

    private fun mergeIntoRules(runs: List<Run>, maxThickness: Int): List<Rule> {
        val rules = mutableListOf<Rule>()
        val open = mutableListOf<MutableList<Run>>()
        for (run in runs) {
            val match = open.firstOrNull { stack ->
                val last = stack.last()
                last.y >= run.y - 1 && kotlin.math.abs(last.x0 - run.x0) <= 3 &&
                    kotlin.math.abs(last.x1 - run.x1) <= 3
            }
            if (match == null) open += mutableListOf(run) else match += run
        }
        for (stack in open) {
            val thickness = stack.size
            // A thick band is a printed rule turned solid, or a black header. Not writable.
            if (thickness > maxThickness + 2) continue
            val first = stack.first()
            rules += Rule(
                y = first.y,
                x0 = stack.minOf { it.x0 },
                x1 = stack.maxOf { it.x1 },
                thickness = thickness,
            )
        }
        return rules.sortedBy { it.y }
    }

    private fun alignedWithin(a: Rule, b: Rule, tolerance: Float): Boolean =
        kotlin.math.abs(a.x0 - b.x0).toFloat() <= tolerance &&
            kotlin.math.abs(a.x1 - b.x1).toFloat() <= tolerance

    /** Is there ink running down both ends, joining the two rules into a box? */
    private fun closedAtEnds(ink: BooleanArray, w: Int, h: Int, top: Rule, bottom: Rule): Boolean {
        fun sideIsInked(x: Int): Boolean {
            if (x !in 0 until w) return false
            var hits = 0
            var rows = 0
            for (y in top.y + 1 until bottom.y) {
                if (y !in 0 until h) continue
                rows++
                // Allow the vertical to wobble by a pixel or two, as a scan will.
                if ((-2..2).any { d -> (x + d) in 0 until w && ink[y * w + x + d] }) hits++
            }
            return rows > 0 && hits.toFloat() / rows >= 0.7f
        }
        return sideIsInked(top.x0) && sideIsInked(top.x1)
    }

    private fun density(ink: BooleanArray, w: Int, h: Int, r: RectN): Float {
        val x0 = (r.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (r.right * w).toInt().coerceIn(0, w - 1)
        val y0 = (r.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (r.bottom * h).toInt().coerceIn(0, h - 1)
        if (x1 <= x0 || y1 <= y0) return 1f
        var hits = 0
        for (y in y0..y1) for (x in x0..x1) if (ink[y * w + x]) hits++
        return hits.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
    }
}
