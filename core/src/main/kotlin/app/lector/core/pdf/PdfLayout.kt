package app.lector.core.pdf

/**
 * Layout model for the PDF cleaning pipeline (see lector-pdf-cleaning-pipeline.md).
 *
 * Deliberately pure Kotlin/JVM: PdfBox types stay in :app, so every stage below
 * Stage 0 is unit-testable without an Android device or a real PDF.
 *
 * Coordinate convention: y grows DOWNWARD from the top of the page (PdfBox's
 * `TextPosition.getYDirAdj()`), so `relativeY == 0` is the top edge and `1` the
 * bottom. The spec is written bottom-up in places; the code is top-down
 * throughout and the comparisons are flipped to match.
 */

/**
 * One horizontal run of text with its geometry, as captured by the stripper.
 * A run is a maximal group of glyphs with no gap wider than a word space, so
 * run boundaries are candidate cell boundaries for table detection (Stage 4).
 */
data class PdfRun(
    val page: Int,
    val pageHeight: Float,
    val text: String,
    val xStart: Float,
    val xEnd: Float,
    val y: Float,
    val height: Float,
    val fontSize: Float,
)

/** A group of runs on one line separated from its neighbours by a wide gutter. */
data class PdfCell(val text: String, val xStart: Float, val xEnd: Float)

/**
 * Stage 0 output: one visual line, built by clustering runs sharing a y-band.
 * This is the unit every later stage operates on.
 */
data class PdfLine(
    val page: Int,
    val pageHeight: Float,
    val y: Float,
    val xStart: Float,
    val xEnd: Float,
    val height: Float,
    val fontSize: Float,
    val text: String,
    val runs: List<PdfRun>,
) {
    /** Page-relative vertical position ŷ ∈ [0, 1], 0 = top edge. */
    val relativeY: Float get() = if (pageHeight > 0f) (y / pageHeight).coerceIn(0f, 1f) else 0f
    val width: Float get() = xEnd - xStart
}

/** A painted primitive's bounding box, in the same top-down space as [PdfLine]. */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * What was painted on one page, collected alongside the text.
 *
 * Text that looks like it belongs to a chart — axis labels, tick values, legend
 * entries — is only recognisable as such by where it sits, and prose that is laid
 * out unusually looks the same. Ink settles it: a chart is drawn, a paragraph is
 * not. [truncated] means the page had more primitives than were worth keeping,
 * which is itself conclusive evidence of a figure.
 */
data class PageInk(
    val page: Int,
    val primitives: List<PdfRect>,
    val truncated: Boolean = false,
) {
    /** How many painted primitives overlap the vertical span [top]..[bottom]. */
    fun primitivesOver(top: Float, bottom: Float): Int =
        primitives.count { it.bottom >= top && it.top <= bottom }
}

/**
 * How long a silence should follow a segment. Ordered ascending by pause
 * length, matching the spec: paragraph > sentence > table row > none.
 */
enum class PauseLevel { NONE, TABLE_ROW, SENTENCE, PARAGRAPH }

/** Final pipeline output: speakable text plus the prosody hint that follows it. */
data class CleanedSegment(val text: String, val pause: PauseLevel)
