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
 *   -> peak picking -> best vertical pair + best horizontal pair -> 4 intersections.
 *
 * It is deliberately conservative: when the quad it finds is implausible it returns null
 * and the UI falls back to the full frame with draggable corners.
 */
object EdgeDetector {

    private const val WORK_DIM = 240      // longest side of the analysis image
    private const val THETA_STEPS = 180   // 1 degree per bin
    private const val THETA_SPREAD = 8    // votes cast around the gradient normal
    private const val MAX_PEAKS = 40
    private const val MARGIN = 1.025f  // keep ~2.5% of white page border

    private val cosT = FloatArray(THETA_STEPS) { cos(Math.toRadians(it.toDouble())).toFloat() }
    private val sinT = FloatArray(THETA_STEPS) { sin(Math.toRadians(it.toDouble())).toFloat() }

    private data class Line(val theta: Int, val rho: Float, val votes: Float)

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

        // Sobel + gradient guided voting.
        var maxMag = 0f
        val mag = FloatArray(w * h)
        val ang = IntArray(w * h)
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
            }
        }
        if (maxMag < 1f) return null
        val cutoff = maxMag * 0.30f

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m < cutoff) continue
                val base = ang[i]
                for (d in -THETA_SPREAD..THETA_SPREAD) {
                    val t = ((base + d) % THETA_STEPS + THETA_STEPS) % THETA_STEPS
                    val rho = x * cosT[t] + y * sinT[t]
                    val bin = (rho + diag).toInt()
                    if (bin in 0 until rhoBins) acc[t * rhoBins + bin] += m
                }
            }
        }

        val peaks = peaks(acc, rhoBins, diag)
        if (peaks.size < 4) return null

        // theta is the angle of the line *normal*: near 0/180 -> vertical line, near 90 -> horizontal.
        val verticals = peaks.filter { it.theta <= 35 || it.theta >= 145 }
        val horizontals = peaks.filter { it.theta in 55..125 }
        if (verticals.size < 2 || horizontals.size < 2) return null

        val vPair = bestPair(verticals, w * 0.35f) { l -> xAt(l, h / 2f) } ?: return null
        val hPair = bestPair(horizontals, h * 0.35f) { l -> yAt(l, w / 2f) } ?: return null

        val (vLeft, vRight) = if (xAt(vPair.first, h / 2f) <= xAt(vPair.second, h / 2f)) vPair else vPair.second to vPair.first
        val (hTop, hBottom) = if (yAt(hPair.first, w / 2f) <= yAt(hPair.second, w / 2f)) hPair else hPair.second to hPair.first

        val tl = intersect(vLeft, hTop) ?: return null
        val tr = intersect(vRight, hTop) ?: return null
        val br = intersect(vRight, hBottom) ?: return null
        val bl = intersect(vLeft, hBottom) ?: return null
        val corners = listOf(tl, tr, br, bl)

        // Plausibility: inside the frame (with slack) and covering a decent area.
        val slackX = w * 0.12f
        val slackY = h * 0.12f
        if (corners.any { it.first < -slackX || it.first > w + slackX || it.second < -slackY || it.second > h + slackY }) return null
        if (shoelace(corners) < 0.15f * w * h) return null

        // Hough locks onto the *inside* of the page border, which shaves off the white
        // margin. Push the quad out slightly from its own centre to keep that margin.
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

    private fun peaks(acc: FloatArray, rhoBins: Int, diag: Float): List<Line> {
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
                loop@ for (dt in -2..2) for (dr in -4..4) {
                    if (dt == 0 && dr == 0) continue
                    val tt = ((t + dt) % THETA_STEPS + THETA_STEPS) % THETA_STEPS
                    val rr = r + dr
                    if (rr !in 0 until rhoBins) continue
                    if (acc[tt * rhoBins + rr] > v) { isMax = false; break@loop }
                }
                if (isMax) found += Line(t, r - diag, v)
            }
        }
        return found.sortedByDescending { it.votes }.take(MAX_PEAKS)
    }

    /** Highest scoring pair of parallel-ish lines that are at least [minGap] apart. */
    private inline fun bestPair(lines: List<Line>, minGap: Float, position: (Line) -> Float): Pair<Line, Line>? {
        var best: Pair<Line, Line>? = null
        var bestScore = 0f
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            if (abs(position(a) - position(b)) < minGap) continue
            val score = a.votes + b.votes
            if (score > bestScore) { bestScore = score; best = a to b }
        }
        return best
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
