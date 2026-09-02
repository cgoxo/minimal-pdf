package com.minimal.pdfcreate.imaging

import android.graphics.Bitmap
import com.minimal.pdfcreate.data.PointN
import com.minimal.pdfcreate.data.Quad
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Page-boundary detection, hand written so the whole pipeline stays visible:
 *
 *   downsample -> grayscale -> box blur -> Sobel -> gradient-guided Hough transform
 *   -> peak picking -> polarity filter -> best vertical pair + best horizontal pair
 *   -> 4 intersections -> border-contrast check.
 *
 * The two steps that do the real work of telling a page apart from what is on it:
 *
 *  - **Polarity.** A page border is a step between the page and the surface under it, so the
 *    brightness always changes the *same way* as you cross any of its four edges outward:
 *    darker everywhere for white paper on a desk, brighter everywhere for a dark cover on a
 *    white table. A line of printed text has edges of both polarities within a few pixels.
 *    Recording which way each edge steps, relative to the frame centre, and keeping only the
 *    lines whose polarity is consistent throws away nearly all of the page's own contents
 *    while leaving its boundary untouched.
 *
 *  - **An adaptive gradient threshold.** The old fixed `0.30 * strongest gradient` meant one
 *    high-contrast thing in shot — a phone screen, a black header bar — raised the bar until
 *    the actual page edge fell under it. Taking a percentile of the gradient histogram
 *    instead keeps roughly the same *amount* of edge whether the page barely contrasts with
 *    the desk or sits on black felt.
 *
 * It stays deliberately conservative: when the quad it finds is implausible, or when the
 * brightness genuinely does not change across its borders, it returns null and the UI falls
 * back to the full frame with draggable corners.
 */
object EdgeDetector {

    private const val WORK_DIM = 240      // longest side of the analysis image
    private const val THETA_STEPS = 180   // 1 degree per bin
    /**
     * Votes cast either side of the gradient normal. Wider tolerates a blurrier edge but
     * smears each line into a ridge in the accumulator, and two ridges six pixels apart —
     * the bottom of a page and the last line of print on it — merge into one.
     */
    private const val THETA_SPREAD = 5
    private const val MAX_PEAKS = 40
    private const val MARGIN = 1.025f  // keep ~2.5% of white page border

    /** Fraction of pixels kept as "edge" by the adaptive threshold. */
    private const val EDGE_FRACTION = 0.12f
    private const val MIN_CUTOFF = 0.06f  // ...but never below this share of the strongest
    private const val MAX_CUTOFF = 0.45f  // ...nor above it, however busy the frame is

    /** How one-sided a line's brightness step must be to count as a boundary, 0f..1f. */
    private const val MIN_POLARITY = 0.35f

    /** Grey levels the page must differ from its surroundings by, averaged along an edge. */
    private const val MIN_EDGE_CONTRAST = 9f

    /** How far either side of an edge the contrast check samples, in working pixels. */
    private const val PROBE = 4

    /** Candidate line pairs kept per axis. */
    private const val MAX_PAIRS = 12

    private val cosT = FloatArray(THETA_STEPS) { cos(Math.toRadians(it.toDouble())).toFloat() }
    private val sinT = FloatArray(THETA_STEPS) { sin(Math.toRadians(it.toDouble())).toFloat() }

    /** [polarity] is +1 where brightness rises going outward from the frame centre, -1 where it falls. */
    private data class Line(val theta: Int, val rho: Float, val votes: Float, val polarity: Float)

    /** Detect from a camera luma (Y) plane — no bitmap allocation on the analysis path. */
    fun detect(luma: ByteArray, width: Int, height: Int, rowStride: Int): Quad? {
        val step = maxOf(1, maxOf(width, height) / WORK_DIM)
        val w = width / step
        val h = height / step
        if (w < 16 || h < 16) return null
        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * step * rowStride
            for (x in 0 until w) {
                gray[y * w + x] = (luma[rowBase + x * step].toInt() and 0xFF).toFloat()
            }
        }
        return detectFromGray(gray, w, h)
    }

    /** Detect from a bitmap (used when re-cropping an already captured page). */
    fun detect(bitmap: Bitmap): Quad? {
        val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / WORK_DIM)
        val w = bitmap.width / step
        val h = bitmap.height / step
        if (w < 16 || h < 16) return null
        val row = IntArray(bitmap.width)
        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y * step, bitmap.width, 1)
            for (x in 0 until w) {
                val p = row[x * step]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[y * w + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return detectFromGray(gray, w, h)
    }

    private fun detectFromGray(gray: FloatArray, w: Int, h: Int): Quad? {
        val blurred = boxBlur(gray, w, h)

        val diag = hypot(w.toFloat(), h.toFloat())
        val rhoBins = (2 * diag).toInt() + 1
        val acc = FloatArray(THETA_STEPS * rhoBins)
        // Signed twin of `acc`: the same votes, but each carrying which way its edge steps.
        val pol = FloatArray(THETA_STEPS * rhoBins)

        // Sobel. gx/gy keep their signs, because the direction of the step is the whole point.
        var maxMag = 0f
        val mag = FloatArray(w * h)
        val ang = IntArray(w * h)
        val outward = FloatArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val tl = blurred[i - w - 1]; val t = blurred[i - w]; val tr = blurred[i - w + 1]
                val l = blurred[i - 1]; val r = blurred[i + 1]
                val bl = blurred[i + w - 1]; val b = blurred[i + w]; val br = blurred[i + w + 1]
                val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                val m = hypot(gx, gy)
                mag[i] = m
                if (m > maxMag) maxMag = m
                var deg = Math.toDegrees(Math.atan2(gy.toDouble(), gx.toDouble())).toInt()
                deg = ((deg % 180) + 180) % 180
                ang[i] = deg
                // Does brightness increase as you move away from the middle of the frame?
                // The page is somewhere near the middle, so this is a usable stand-in for
                // "away from the page" without knowing where the page is yet.
                outward[i] = if (gx * (x - cx) + gy * (y - cy) >= 0f) 1f else -1f
            }
        }
        if (maxMag < 1f) return null
        val cutoff = adaptiveCutoff(mag, maxMag)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m < cutoff) continue
                val base = ang[i]
                val sign = outward[i]
                for (d in -THETA_SPREAD..THETA_SPREAD) {
                    val t = ((base + d) % THETA_STEPS + THETA_STEPS) % THETA_STEPS
                    val rho = x * cosT[t] + y * sinT[t]
                    val bin = (rho + diag).toInt()
                    if (bin in 0 until rhoBins) {
                        acc[t * rhoBins + bin] += m
                        pol[t * rhoBins + bin] += m * sign
                    }
                }
            }
        }

        val allPeaks = peaks(acc, pol, rhoBins, diag)
        if (allPeaks.size < 4) return null

        // Which way this page steps against its background: whichever polarity carries the
        // most weight overall. Every one of the four borders should then agree with it.
        val bias = allPeaks.sumOf { (it.polarity * it.votes).toDouble() }
        val dominant = if (bias >= 0.0) 1f else -1f
        val consistent = allPeaks.filter {
            abs(it.polarity) >= MIN_POLARITY && (if (it.polarity >= 0f) 1f else -1f) == dominant
        }

        // Boundary-like lines first; the unfiltered set is only a fallback for the awkward
        // frame where a shadow flips one border, and it still has to pass the contrast check.
        return quadFrom(consistent, blurred, w, h) ?: quadFrom(allPeaks, blurred, w, h)
    }

    /**
     * The gradient magnitude above which a pixel counts as an edge.
     *
     * A histogram, then walk down from the top until [EDGE_FRACTION] of the frame is
     * accounted for. Clamped at both ends so a blank wall does not promote its own noise and
     * a frame full of hard edges does not raise the bar past the page.
     */
    private fun adaptiveCutoff(mag: FloatArray, maxMag: Float): Float {
        val bins = 64
        val hist = IntArray(bins)
        var counted = 0
        for (m in mag) {
            if (m <= 0f) continue
            hist[((m / maxMag) * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
            counted++
        }
        if (counted == 0) return maxMag * MAX_CUTOFF
        val want = (counted * EDGE_FRACTION).toInt().coerceAtLeast(1)
        var running = 0
        var bin = bins - 1
        while (bin > 0 && running < want) {
            running += hist[bin]
            bin--
        }
        val fraction = (bin.toFloat() / (bins - 1)).coerceIn(MIN_CUTOFF, MAX_CUTOFF)
        return maxMag * fraction
    }

    /** Turns a set of candidate lines into a quad, or null if they do not describe a page. */
    private fun quadFrom(peaks: List<Line>, blurred: FloatArray, w: Int, h: Int): Quad? {
        if (peaks.size < 4) return null

        // theta is the angle of the line *normal*: near 0/180 -> vertical line, near 90 -> horizontal.
        val verticals = peaks.filter { it.theta <= 35 || it.theta >= 145 }
        val horizontals = peaks.filter { it.theta in 55..125 }
        if (verticals.size < 2 || horizontals.size < 2) return null

        // Several candidate pairs per axis, best-scoring first, rather than committing to the
        // single top pair. The top pair is sometimes a line of print — very straight, very
        // high contrast, and completely wrong — and the checks below are what tell the
        // difference. Giving them a shortlist to reject from is what makes them useful.
        val vPairs = rankedPairs(verticals, w * 0.35f, w.toFloat()) { l -> xAt(l, h / 2f) }
        val hPairs = rankedPairs(horizontals, h * 0.35f, h.toFloat()) { l -> yAt(l, w / 2f) }
        if (vPairs.isEmpty() || hPairs.isEmpty()) return null

        val slackX = w * 0.12f
        val slackY = h * 0.12f

        for (vPair in vPairs) {
            for (hPair in hPairs) {
                val (vLeft, vRight) = if (xAt(vPair.first, h / 2f) <= xAt(vPair.second, h / 2f)) vPair else vPair.second to vPair.first
                val (hTop, hBottom) = if (yAt(hPair.first, w / 2f) <= yAt(hPair.second, w / 2f)) hPair else hPair.second to hPair.first

                val tl = intersect(vLeft, hTop) ?: continue
                val tr = intersect(vRight, hTop) ?: continue
                val br = intersect(vRight, hBottom) ?: continue
                val bl = intersect(vLeft, hBottom) ?: continue
                val corners = listOf(tl, tr, br, bl)

                // Plausibility: inside the frame (with slack) and covering a decent area.
                if (corners.any { it.first < -slackX || it.first > w + slackX || it.second < -slackY || it.second > h + slackY }) continue
                if (shoelace(corners) < 0.15f * w * h) continue

                // The last word belongs to the pixels, not the accumulator: four lines can
                // score well and still not be a page.
                if (!hasBorderContrast(corners, blurred, w, h)) continue

                // Hough locks onto the *inside* of the page border, which shaves off the
                // white margin. Push the quad out slightly from its own centre to keep it.
                return expand(
                    Quad(
                        PointN(tl.first / w, tl.second / h),
                        PointN(tr.first / w, tr.second / h),
                        PointN(br.first / w, br.second / h),
                        PointN(bl.first / w, bl.second / h),
                    ),
                    MARGIN,
                )
            }
        }
        return null
    }

    /**
     * Samples just inside and just outside each of the four borders and asks whether the page
     * is really lighter (or really darker) than what surrounds it.
     *
     * An edge whose probes land outside the frame has no opinion and is skipped; at least
     * three must remain. Every edge that *could* be measured then has to clear
     * [MIN_EDGE_CONTRAST], and all of them have to step the same way.
     *
     * Measured-but-flat is the important case, and it is fatal rather than merely unhelpful:
     * it means the far side of that border looks exactly like the near side, so the border is
     * not the outline of anything. That is precisely what a printed rule across the middle of
     * a page looks like, and it is how one gets rejected in favour of the real bottom edge.
     *
     * The outside is sampled at two distances and the *weaker* of the two has to clear the
     * bar. That is what separates a page border from a line of print: step off the page and
     * the surface underneath keeps going, but step across a printed rule and you are back on
     * white paper a few pixels later.
     */
    private fun hasBorderContrast(
        corners: List<Pair<Float, Float>>,
        blurred: FloatArray,
        w: Int,
        h: Int,
    ): Boolean {
        val cx = corners.sumOf { it.first.toDouble() }.toFloat() / corners.size
        val cy = corners.sumOf { it.second.toDouble() }.toFloat() / corners.size

        fun sample(x: Float, y: Float): Float? {
            val xi = x.toInt()
            val yi = y.toInt()
            if (xi !in 0 until w || yi !in 0 until h) return null
            return blurred[yi * w + xi]
        }

        val steps = mutableListOf<Float>()
        for (i in corners.indices) {
            val (x1, y1) = corners[i]
            val (x2, y2) = corners[(i + 1) % corners.size]

            // Outward unit normal: perpendicular to the edge, flipped to point away from the
            // quad's own centre.
            var nx = -(y2 - y1)
            var ny = (x2 - x1)
            val len = hypot(nx, ny)
            if (len < 1e-3f) continue
            nx /= len; ny /= len
            val mx = (x1 + x2) / 2f
            val my = (y1 + y2) / 2f
            if (nx * (mx - cx) + ny * (my - cy) < 0f) { nx = -nx; ny = -ny }

            var inside = 0f
            var outNear = 0f
            var outFar = 0f
            var n = 0
            for (t in intArrayOf(20, 35, 50, 65, 80)) {
                val f = t / 100f
                val px = x1 + (x2 - x1) * f
                val py = y1 + (y2 - y1) * f
                val a = sample(px - nx * PROBE, py - ny * PROBE) ?: continue
                val near = sample(px + nx * PROBE, py + ny * PROBE) ?: continue
                val far = sample(px + nx * PROBE * 2f, py + ny * PROBE * 2f) ?: continue
                inside += a
                outNear += near
                outFar += far
                n++
            }
            if (n == 0) continue

            val stepNear = (outNear - inside) / n
            val stepFar = (outFar - inside) / n
            // The two distances must agree with each other, and the weaker of them is the one
            // that has to qualify; disagreement means whatever is out there is not a surface,
            // so it is recorded as no step at all.
            steps += when {
                stepNear >= 0f != stepFar >= 0f -> 0f
                abs(stepNear) <= abs(stepFar) -> stepNear
                else -> stepFar
            }
        }

        if (steps.size < 3) return false
        if (steps.any { abs(it) < MIN_EDGE_CONTRAST }) return false
        // Unanimous, not merely a majority: a quad whose fourth side steps the other way has
        // one side sitting on something that is not the edge of the page.
        return steps.all { it > 0f } || steps.all { it < 0f }
    }

    /** Scales a quad about its centroid, clamped to the frame. */
    private fun expand(quad: Quad, factor: Float): Quad {
        val pts = quad.toList()
        val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
        return Quad.fromList(
            pts.map {
                PointN(
                    (cx + (it.x - cx) * factor).coerceIn(0f, 1f),
                    (cy + (it.y - cy) * factor).coerceIn(0f, 1f),
                )
            }
        )
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy; val xx = x + dx
                    if (yy in 0 until h && xx in 0 until w) { sum += src[yy * w + xx]; n++ }
                }
                out[y * w + x] = sum / n
            }
        }
        return out
    }

    private fun peaks(acc: FloatArray, pol: FloatArray, rhoBins: Int, diag: Float): List<Line> {
        var max = 0f
        for (v in acc) if (v > max) max = v
        if (max <= 0f) return emptyList()
        val floor = max * 0.25f
        val found = mutableListOf<Line>()
        for (t in 0 until THETA_STEPS) {
            for (r in 2 until rhoBins - 2) {
                val v = acc[t * rhoBins + r]
                if (v < floor) continue
                var isMax = true
                // Suppression window: wide enough to collapse one line's own smear, narrow
                // enough to leave a second line a few pixels away standing. At the old
                // +/-4 the page's bottom border was being suppressed by the printed rule
                // nearest it, and vanished from the results entirely.
                loop@ for (dt in -1..1) for (dr in -3..3) {
                    if (dt == 0 && dr == 0) continue
                    val tt = ((t + dt) % THETA_STEPS + THETA_STEPS) % THETA_STEPS
                    val rr = r + dr
                    if (rr !in 0 until rhoBins) continue
                    if (acc[tt * rhoBins + rr] > v) { isMax = false; break@loop }
                }
                if (isMax) found += Line(t, r - diag, v, pol[t * rhoBins + r] / v)
            }
        }
        return found.sortedByDescending { it.votes }.take(MAX_PEAKS)
    }

    /**
     * Pairs of parallel-ish lines at least [minGap] apart, best scoring first.
     *
     * Score is votes weighted by how far apart the pair sits, as a fraction of [span]. Votes
     * alone rank a densely printed page's own rules above its borders — there are a dozen of
     * them and each is blacker than the edge of the paper — but the borders are always the
     * pair furthest apart. For an ordinary page the border pair wins on both counts anyway,
     * so this only changes the order in the case that needs it changed.
     *
     * Capped at [MAX_PAIRS] so the search over vertical x horizontal combinations stays a
     * fixed, small amount of work on every camera frame.
     */
    private inline fun rankedPairs(
        lines: List<Line>,
        minGap: Float,
        span: Float,
        position: (Line) -> Float,
    ): List<Pair<Line, Line>> {
        val scored = mutableListOf<Pair<Float, Pair<Line, Line>>>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            val gap = abs(position(a) - position(b))
            if (gap < minGap || !gap.isFinite()) continue
            scored += (a.votes + b.votes) * (gap / span).coerceAtMost(1f) to (a to b)
        }
        return scored.sortedByDescending { it.first }.take(MAX_PAIRS).map { it.second }
    }

    private fun xAt(l: Line, y: Float): Float {
        val c = cosT[l.theta]
        return if (abs(c) < 1e-4f) Float.MAX_VALUE else (l.rho - y * sinT[l.theta]) / c
    }

    private fun yAt(l: Line, x: Float): Float {
        val s = sinT[l.theta]
        return if (abs(s) < 1e-4f) Float.MAX_VALUE else (l.rho - x * cosT[l.theta]) / s
    }

    private fun intersect(a: Line, b: Line): Pair<Float, Float>? {
        val a1 = cosT[a.theta]; val b1 = sinT[a.theta]; val c1 = a.rho
        val a2 = cosT[b.theta]; val b2 = sinT[b.theta]; val c2 = b.rho
        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-5f) return null
        return ((c1 * b2 - c2 * b1) / det) to ((a1 * c2 - a2 * c1) / det)
    }

    private fun shoelace(p: List<Pair<Float, Float>>): Float {
        var area = 0f
        for (i in p.indices) {
            val (x1, y1) = p[i]
            val (x2, y2) = p[(i + 1) % p.size]
            area += x1 * y2 - x2 * y1
        }
        return abs(area) / 2f
    }

    /** Rotates a normalised quad by [degrees] (0/90/180/270) so it matches a rotated frame. */
    fun rotateQuad(q: Quad, degrees: Int): Quad {
        fun rot(p: PointN): PointN = when (((degrees % 360) + 360) % 360) {
            90 -> PointN(1f - p.y, p.x)
            180 -> PointN(1f - p.x, 1f - p.y)
            270 -> PointN(p.y, 1f - p.x)
            else -> p
        }
        val pts = q.toList().map(::rot)
        // Re-order so the corner nearest the origin stays top-left.
        val shift = when (((degrees % 360) + 360) % 360) { 90 -> 3; 180 -> 2; 270 -> 1; else -> 0 }
        return Quad.fromList(List(4) { pts[(it + shift) % 4] })
    }
}
