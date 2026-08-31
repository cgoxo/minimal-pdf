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

enum class FilterMode { ORIGINAL, GRAYSCALE, BW, DOCUMENT }

/**
 * Tone controls. [brightness] is an offset in -1f..1f, [contrast] a multiplier around the
 * mid grey, [threshold] only matters for [FilterMode.BW] / [FilterMode.DOCUMENT].
 */
@Serializable
data class FilterSettings(
    val mode: FilterMode = FilterMode.ORIGINAL,
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
    /** Display name of the exported file inside Documents/minimalPdf, once exported. */
    val pdfFileName: String? = null,
) {
    fun page(pageId: String): Page? = pages.firstOrNull { it.id == pageId }

    fun replacePage(page: Page): ScanDocument =
        copy(pages = pages.map { if (it.id == page.id) page else it }, updatedAt = System.currentTimeMillis())
}
