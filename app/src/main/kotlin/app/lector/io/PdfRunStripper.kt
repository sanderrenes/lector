package app.lector.io

import app.lector.core.pdf.PageInk
import app.lector.core.pdf.PdfRect
import app.lector.core.pdf.PdfRun
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.OperatorProcessor
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Writer

/** Raised when a document has more runs than we are willing to hold in memory. */
class PdfTooComplexException(message: String) : RuntimeException(message)

/**
 * Stage 0 of the cleaning pipeline: a [PDFTextStripper] that keeps the glyph
 * geometry instead of discarding it, and notes where the page was drawn on.
 *
 * `getText(document)` flattens the page to characters, which is why the plain
 * extractor could not tell a soft wrap from a paragraph break, a page number
 * from a sentence, or a table column from a run of words. Overriding
 * [writeString] gives us the `TextPosition` list behind every run, so each
 * emitted [PdfRun] carries `(x, y, width, height, fontSize)` and the page it
 * came from — everything stages 1–4 need.
 *
 * Ink comes along for free. `PDFStreamEngine` parses the operands of every path
 * operator whether or not anything is registered to receive them, so adding
 * processors for the path ops costs a few float multiplies on data that has
 * already been tokenised — no second pass over the document. What it buys is the
 * evidence that separates a chart's axis labels from prose that merely sits in an
 * odd place (see `PdfCleaner.findFigureRegions`).
 *
 * Granularity: runs are split at gaps wider than [RUN_SPLIT_EM] so column
 * gutters become run boundaries (what Stage 4 clusters on), while ordinary word
 * gaps stay inside a run as spaces. Splitting per word instead would multiply
 * the object count by ~10 for no gain.
 *
 * Coordinates come from the `*DirAdj` accessors, which are already rotation- and
 * flip-corrected with y growing downward from the top of the page — the
 * convention [app.lector.core.pdf.PdfLine] documents. Path coordinates get the
 * same treatment by hand, which only works on an unrotated page, so ink is not
 * collected for rotated ones and those fall back to caption evidence alone.
 */
class PdfRunStripper(private val maxRuns: Int = MAX_RUNS) : PDFTextStripper() {

    private val collected = mutableListOf<PdfRun>()
    private val ink = HashMap<Int, MutableList<PdfRect>>()
    private val inkTruncated = HashSet<Int>()

    init {
        sortByPosition = true // reading order, not content-stream order
        for (name in PATH_OPS) addOperator(PathOperator(name))
        for (name in PAINT_OPS) addOperator(PaintOperator(name, emit = true))
        addOperator(PaintOperator("n", emit = false)) // end path, paint nothing
    }

    /** Extract every run in the document. Throws [PdfTooComplexException] past the cap. */
    fun runs(document: PDDocument): List<PdfRun> {
        collected.clear()
        ink.clear()
        inkTruncated.clear()
        // writeText drives the page walk and our writeString override; we consume the
        // geometry, so the assembled string it would build goes to a no-op writer.
        writeText(document, NullWriter)
        return collected.toList()
    }

    /**
     * What was drawn on [pages], for the pages the caller cares about. Everything
     * else is dropped here rather than handed on, since it cannot affect the result.
     */
    fun ink(pages: Set<Int>): Map<Int, PageInk> =
        pages.associateWith { page ->
            PageInk(page, ink[page].orEmpty().toList(), page in inkTruncated)
        }

    /** Guards the inherited entry point: this stripper's product is [runs], not text. */
    override fun getText(doc: PDDocument): String {
        runs(doc)
        return ""
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isEmpty()) return

        val page = currentPageNo
        val height = pageHeight()
        var open: RunBuilder? = null

        for (position in textPositions) {
            val glyph = position.unicode ?: continue
            if (glyph.isBlank()) {
                // A real space glyph: keep it inside the run, don't start a new one.
                open?.appendSpace()
                continue
            }
            val x = position.xDirAdj
            val width = position.widthDirAdj
            val builder = open
            if (builder == null || x - builder.xEnd > builder.fontSize * RUN_SPLIT_EM) {
                builder?.let(::emit)
                open = RunBuilder(page, height, position).also { it.append(glyph, x, width) }
            } else {
                if (x - builder.xEnd > builder.fontSize * WORD_GAP_EM) builder.appendSpace()
                builder.append(glyph, x, width)
                builder.absorb(position)
            }
        }
        open?.let(::emit)
    }

    private fun emit(builder: RunBuilder) {
        val run = builder.build() ?: return
        if (collected.size >= maxRuns) {
            throw PdfTooComplexException("PDF exceeds $maxRuns text runs; falling back to plain extraction")
        }
        collected += run
    }

    // ── Ink ───────────────────────────────────────────────────────────────────

    private var collectInk = true
    private var pathStarted = false
    private var pathLeft = 0f
    private var pathTop = 0f
    private var pathRight = 0f
    private var pathBottom = 0f

    override fun startPage(page: PDPage) {
        // The path coordinates below are flipped by hand, which assumes an upright
        // page. Rotated pages simply contribute no ink.
        collectInk = ((page.rotation % 360) + 360) % 360 == 0
        pathStarted = false
        super.startPage(page)
    }

    /** Grow the current path's box to include a point given in unrotated user space. */
    private fun extendPath(x: Float, y: Float) {
        if (!collectInk) return
        val m = graphicsState.currentTransformationMatrix
        val deviceX = m.getValue(0, 0) * x + m.getValue(1, 0) * y + m.getValue(2, 0)
        val deviceY = m.getValue(0, 1) * x + m.getValue(1, 1) * y + m.getValue(2, 1)
        val top = pageHeight() - deviceY // PDF space is bottom-up; PdfLine is not
        if (!pathStarted) {
            pathStarted = true
            pathLeft = deviceX; pathRight = deviceX
            pathTop = top; pathBottom = top
            return
        }
        if (deviceX < pathLeft) pathLeft = deviceX
        if (deviceX > pathRight) pathRight = deviceX
        if (top < pathTop) pathTop = top
        if (top > pathBottom) pathBottom = top
    }

    private fun finishPath(emit: Boolean) {
        if (emit && pathStarted && collectInk) {
            val page = currentPageNo
            val primitives = ink.getOrPut(page) { mutableListOf() }
            // Past the cap the page is unarguably a figure, so precision stops paying
            // for itself and we only remember that there was more.
            if (primitives.size < MAX_INK_PRIMITIVES) {
                primitives += PdfRect(pathLeft, pathTop, pathRight, pathBottom)
            } else {
                inkTruncated += page
            }
        }
        pathStarted = false
    }

    /** `m`, `l`, `c`, `v`, `y`: coordinate pairs. Control points count — the box only grows. */
    private inner class PathOperator(private val op: String) : OperatorProcessor() {
        override fun getName(): String = op

        override fun process(operator: Operator, operands: List<COSBase>) {
            if (!collectInk) return
            if (op == "re") {
                val x = number(operands, 0) ?: return
                val y = number(operands, 1) ?: return
                val w = number(operands, 2) ?: return
                val h = number(operands, 3) ?: return
                extendPath(x, y)
                extendPath(x + w, y + h)
                return
            }
            var i = 0
            while (i + 1 < operands.size) {
                val x = number(operands, i) ?: return
                val y = number(operands, i + 1) ?: return
                extendPath(x, y)
                i += 2
            }
        }
    }

    /** `S`, `f`, `B`, … paint the current path; `n` discards it. */
    private inner class PaintOperator(private val op: String, private val emit: Boolean) : OperatorProcessor() {
        override fun getName(): String = op
        override fun process(operator: Operator, operands: List<COSBase>) = finishPath(emit)
    }

    private fun number(operands: List<COSBase>, index: Int): Float? =
        (operands.getOrNull(index) as? COSNumber)?.floatValue()

    /**
     * Page height in the same (rotation-corrected) space as `getYDirAdj`, so
     * `y / pageHeight` is a true page fraction for the header/footer bands.
     */
    private fun pageHeight(): Float {
        val page = currentPage ?: return 0f
        val box = page.mediaBox
        val rotated = ((page.rotation % 360) + 360) % 360 in setOf(90, 270)
        return if (rotated) box.width else box.height
    }

    /** Accumulates glyphs into one run, growing its bounding box as it goes. */
    private class RunBuilder(
        private val page: Int,
        private val pageHeight: Float,
        first: TextPosition,
    ) {
        private val text = StringBuilder()
        private val xStart = first.xDirAdj
        var xEnd = first.xDirAdj
            private set
        private var y = first.yDirAdj
        private var glyphHeight = first.heightDir
        var fontSize = first.fontSizeInPt.takeIf { it > 0f } ?: first.heightDir
            private set

        fun append(glyph: String, x: Float, width: Float) {
            text.append(glyph)
            xEnd = maxOf(xEnd, x + width)
        }

        fun appendSpace() {
            if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
        }

        /** Widen the run's type metrics to the largest glyph it contains. */
        fun absorb(position: TextPosition) {
            y = maxOf(y, position.yDirAdj)
            glyphHeight = maxOf(glyphHeight, position.heightDir)
            val size = position.fontSizeInPt
            if (size > fontSize) fontSize = size
        }

        fun build(): PdfRun? {
            val value = text.toString().trim()
            if (value.isEmpty()) return null
            return PdfRun(
                page = page,
                pageHeight = pageHeight,
                text = value,
                xStart = xStart,
                xEnd = maxOf(xEnd, xStart),
                y = y,
                height = glyphHeight.coerceAtLeast(1f),
                fontSize = fontSize.coerceAtLeast(1f),
            )
        }
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    private companion object {
        /** Gap that starts a new run — a column gutter, not a word space. */
        const val RUN_SPLIT_EM = 0.8f

        /** Gap that reads as a word space inside a run (PdfBox's own default ballpark). */
        const val WORD_GAP_EM = 0.22f

        /**
         * Ceiling on retained runs. Runs are line/cell-granular, so this covers
         * thousands of pages; past it we would risk an OOM on a low-memory device
         * and the caller falls back to plain extraction instead.
         */
        const val MAX_RUNS = 300_000

        /**
         * Ceiling on retained primitives per page. A scatter plot draws every point
         * as four béziers, so a single panel can run to tens of thousands; a few
         * hundred already settles the question.
         */
        const val MAX_INK_PRIMITIVES = 512

        /** Path construction. `Do` is deliberately absent: overriding it would break
         * PdfBox's own recursion into form XObjects, which is where some text lives. */
        val PATH_OPS = listOf("re", "m", "l", "c", "v", "y")

        /** Path painting. Each of these consumes the current path. */
        val PAINT_OPS = listOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*")
    }
}
