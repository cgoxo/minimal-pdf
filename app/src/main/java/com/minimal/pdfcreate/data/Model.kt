package com.minimal.pdfcreate.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** A point in normalised image space: 0f..1f on both axes. Resolution independent. */
@Serializable
data class PointN(val x: Float, val y: Float)

/** Four corners of the detected page, in normalised space, clockwise from top-left. */
@Serializable
data class Quad(val tl: PointN, val tr: PointN, val br: PointN, val bl: PointN) {
    fun toList() = listOf(tl, tr, br, bl)

    companion object {
        val FULL = Quad(PointN(0f, 0f), PointN(1f, 0f), PointN(1f, 1f), PointN(0f, 1f))

        fun fromList(p: List<PointN>) = Quad(p[0], p[1], p[2], p[3])
    }
}

/**
 * [COLOR_DOC] is [DOCUMENT]'s colour sibling: same shadow-flattening, but the lift is applied
 * to all three channels at once so stamps, highlighter and letterheads keep their colour while
 * the paper still goes white. Appended rather than inserted — the manifest stores these by
 * name, so order is free to change, but keeping it stable keeps the chip row stable too.
 */
enum class FilterMode { ORIGINAL, GRAYSCALE, BW, DOCUMENT, COLOR_DOC }

/**
 * Tone controls. [brightness] is an offset in -1f..1f, [contrast] a multiplier around the
 * mid grey, [threshold] only matters for [FilterMode.BW] / [FilterMode.DOCUMENT] /
 * [FilterMode.COLOR_DOC].
 */
@Serializable
data class FilterSettings(
    /**
     * A capture defaults to the colour scan, not the raw camera frame — this is a scanner, and
     * a photo of a page is the starting material rather than the result. Colour rather than
     * [FilterMode.DOCUMENT] because throwing colour away is the one thing the user cannot undo
     * by eye: a stamp, a highlight or a signature in blue ink should still be there by default.
     * The Original chip puts the camera's own image back whenever it was the better one.
     */
    val mode: FilterMode = FilterMode.COLOR_DOC,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val threshold: Float = 0.5f,
)

/** One freehand brush stroke. Width is normalised against the image width. */
@Serializable
data class Stroke(
    val color: Long,
    val widthN: Float,
    val points: List<PointN>,
)

@Serializable
sealed interface Overlay {
    val id: String
    val posN: PointN

    @Serializable
    data class Text(
        override val id: String = UUID.randomUUID().toString(),
        val text: String,
        override val posN: PointN,
        val sizeN: Float = 0.05f,
        val color: Long = 0xFF000000L,
    ) : Overlay

    @Serializable
    data class Signature(
        override val id: String = UUID.randomUUID().toString(),
        val fileName: String,
        override val posN: PointN,
        val widthN: Float = 0.35f,
        /** Clockwise degrees, about the centre of the placed signature. */
        val rotation: Float = 0f,
    ) : Overlay
}

/** A single scanned page: the untouched capture plus non-destructive edits. */
@Serializable
data class Page(
    val id: String = UUID.randomUUID().toString(),
    val imageName: String,
    val crop: Quad? = null,
    val rotation: Int = 0,
    val filter: FilterSettings = FilterSettings(),
    val strokes: List<Stroke> = emptyList(),
    val overlays: List<Overlay> = emptyList(),
)

@Serializable
data class ScanDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pages: List<Page> = emptyList(),
    /** Display name of the exported file inside Documents/Scanly, once exported. */
    val pdfFileName: String? = null,
) {
    fun page(pageId: String): Page? = pages.firstOrNull { it.id == pageId }

    fun replacePage(page: Page): ScanDocument =
        copy(pages = pages.map { if (it.id == page.id) page else it }, updatedAt = System.currentTimeMillis())
}
