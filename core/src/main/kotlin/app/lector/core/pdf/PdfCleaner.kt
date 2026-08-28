package app.lector.core.pdf

import kotlin.math.abs

/**
 * Tunable thresholds for the cleaning pipeline. Defaults are the values from
 * lector-pdf-cleaning-pipeline.md; they are constructor parameters so a badly
 * laid-out document can be re-run with looser settings without touching the
 * algorithms.
 */
data class PdfCleanerConfig(
    /** δy: y-band tolerance for line clustering, as a fraction of median glyph height. */
    val lineBandFactor: Float = 0.3f,
    /** τ_top: header band, as a fraction of page height. */
    val headerBand: Float = 0.09f,
    /** τ_bot: footer band, as a fraction of page height. */
    val footerBand: Float = 0.09f,
    /** θ: fraction of pages a margin line must recur on to count as running furniture. */
    val recurrenceThreshold: Float = 0.5f,
    /** Header/footer stripping needs enough pages for recurrence to mean anything. */
    val minPagesForRecurrence: Int = 3,
    /** Page-relative y quantum used to decide that two margin lines share a band. */
    val marginBandQuantum: Float = 0.006f,
    /** Inter-run gap that reads as a column gutter rather than a word space, in em. */
    val gutterEm: Float = 1.2f,
    /** Minimum consecutive conforming rows before a block is called a table. */
    val minTableRows: Int = 3,
    /** Vertical gap above this multiple of the body line height ends a paragraph. */
    val lineGapFactor: Float = 1.35f,
    /** How far a continuation line may sit from the paragraph's established margin, in em. */
    val indentToleranceEm: Float = 0.6f,
    /** How far a paragraph's FIRST line may sit from that margin — an indent or a hang. */
    val firstLineIndentEm: Float = 3.0f,
    /** Font-size deviation from body text that marks a heading. */
    val headingSizeRatio: Float = 1.15f,
    /** A heading must also be short relative to the body measure. */
    val headingWidthRatio: Float = 0.7f,
    /** Gap, in multiples of the heading's own font size, that still continues one heading. */
    val headingWrapFactor: Float = 1.6f,
    /** Drop the text inside a figure — axis labels, tick values, legends. Keeps the caption. */
    val dropFigureInternals: Boolean = true,
    /** Consecutive off-column lines before a block counts as a figure candidate. */
    val minFigureLines: Int = 3,
    /** How far off the body margin a line must start to be off-column, in em. */
    val figureMarginEm: Float = 1.5f,
    /**
     * Painted primitives over a candidate's span before it is confirmed to be a
     * figure. Measured on a LaTeX thesis: a table region draws 3–4 rules, the
     * sparsest chart draws 29, and nothing observed falls between 8 and 29. Sitting
     * in the middle of that gap keeps the margin on the side that matters, since
     * dropping a table costs real content and keeping a sparse figure costs noise.
     */
    val minInkPrimitives: Int = 16,
    /** Vertical slack when matching ink against a candidate's span, in em. */
    val figureInkPadEm: Float = 2f,
    /**
     * A line at least this fraction of the page's content width counts as
     * spanning both columns rather than sitting in one of them.
     */
    val columnSpanRatio: Float = 0.75f,
    /** Lines needed on EACH side before a page is treated as two columns. */
    val minColumnLines: Int = 4,
)

/**
 * Word frequencies taken from the document itself, used as the lexicon for
 * Stage 2. A thesis is its own best dictionary: whatever it hyphenates in the
 * middle of a line is a real compound, and whatever it writes solid elsewhere
 * is a word that a line break happened to split.
 */
class Vocabulary internal constructor(
    private val words: Map<String, Int>,
    private val compounds: Map<String, Int>,
) {
    /** Occurrences of the two fragments written solid, e.g. "understanding". */
    fun solidCount(fragment: String, tail: String): Int =
        words[(fragment + tail).lowercase()] ?: 0

    /** Occurrences of the two fragments written hyphenated, e.g. "well-being". */
    fun hyphenatedCount(fragment: String, tail: String): Int =
        compounds["$fragment-$tail".lowercase()] ?: 0

    companion object {
        /** No evidence either way — every line-end hyphen then falls to the default. */
        val EMPTY = Vocabulary(emptyMap(), emptyMap())

        fun of(lines: List<PdfLine>): Vocabulary {
            val words = HashMap<String, Int>()
            val compounds = HashMap<String, Int>()
            for (line in lines) {
                var tokens = TOKEN.findAll(line.text).map { it.value }.toList()
                // The final token of a hyphen-broken line is half a word; counting it
                // would seed the lexicon with fragments that are not words at all.
                if (endsHyphenated(line.text)) tokens = tokens.dropLast(1)
                for (token in tokens) {
                    val key = token.lowercase()
                    val bucket = if ('-' in key) compounds else words
                    bucket[key] = (bucket[key] ?: 0) + 1
                }
            }
            return Vocabulary(words, compounds)
        }

        /** Two or more letters, possibly with internal hyphens. */
        private val TOKEN = Regex("""\p{L}[\p{L}-]*\p{L}""")
    }
}

/** A hyphen (U+002D) or a soft hyphen (U+00AD) at the end of the line. */
internal fun endsHyphenated(text: String): Boolean =
    text.trimEnd().lastOrNull()?.let { it == '-' || it.code == 0x00AD } == true

/**
 * Turns geometry-aware PDF runs into TTS-ready segments.
 *
 * Stage 0 (line building) -> Stage 1 (running header/footer strip) ->
 * Stage 4 (table detection, ring-fencing those line ranges) ->
 * Stage 2 (dehyphenation) -> Stage 3 (soft-wrap join) -> segments.
 *
 * Stages 2 and 3 share one decision — "are these two lines the same paragraph
 * flow?" — so the soft-wrap predicate is computed once per adjacent pair and
 * consumed by both, rather than duplicating the geometry test.
 */
class PdfCleaner(private val config: PdfCleanerConfig = PdfCleanerConfig()) {

    /** Full pipeline with no graphics information: runs in, speakable segments out. */
    fun clean(runs: List<PdfRun>): List<CleanedSegment> = prepare(runs).finish()

    /**
     * Stages 0 and 1, plus the cheap half of figure detection.
     *
     * Splitting the pipeline here is what lets a caller pay for graphics data only
     * where it can change the answer: [Prepared.figureCandidatePages] names the
     * pages carrying something that looks like a figure, and everything else can be
     * left alone. Pass whatever ink was collected to [Prepared.finish].
     */
    fun prepare(runs: List<PdfRun>): Prepared {
        if (runs.isEmpty()) return Prepared(this, BodyMetrics(emptyList(), 12f, 14f, 400f), emptyList())

        val pages = runs.groupBy { it.page }.toSortedMap()
        val lines = pages.values.flatMap { pageRuns ->
            // Two passes: the first finds out whether this page has a persistent
            // column gutter at all (Stage 0 with no gutter knowledge yet), the second
            // re-clusters knowing where it is, so two lines that only share a y-band
            // by coincidence — the top row of each column — don't fuse into one.
            val gutter = columnGutter(buildLines(pageRuns))
            val built = buildLines(pageRuns, gutter)
            if (gutter == null) built else reorderColumns(built, gutter)
        } // Stage 0 (+ 0.5)
        val body = bodyMetrics(stripRunningFurniture(lines, pages.size)) // Stage 1
        val regions = if (config.dropFigureInternals) findFigureRegions(body) else emptyList()
        return Prepared(this, body, regions)
    }

    /** The document after stages 0 and 1, waiting on graphics data it may not need. */
    class Prepared internal constructor(
        private val cleaner: PdfCleaner,
        internal val body: BodyMetrics,
        internal val regions: List<FigureRegion>,
    ) {
        /** The only pages on which collecting ink can change the outcome. */
        val figureCandidatePages: Set<Int> get() = regions.mapTo(HashSet()) { it.page }

        /** Stages 1.5 through 4. [ink] may be empty, partial, or complete. */
        fun finish(ink: Map<Int, PageInk> = emptyMap()): List<CleanedSegment> =
            cleaner.finish(body, regions, ink)
    }

    private fun finish(
        prepared: BodyMetrics,
        regions: List<FigureRegion>,
        ink: Map<Int, PageInk>,
    ): List<CleanedSegment> {
        if (prepared.lines.isEmpty()) return emptyList()

        // Stage 1.5 — figure internals, now that the ink (if any) has arrived.
        val dropped = HashSet<Int>()
        for (region in regions) if (isFigure(region, ink[region.page], prepared)) dropped += region.range
        val remaining =
            if (dropped.isEmpty()) prepared.lines
            else prepared.lines.filterIndexed { index, _ -> index !in dropped }
        if (remaining.isEmpty()) return emptyList()

        // Chart text skews the body measures and the lexicon, so both are taken again
        // once it is gone.
        val body = if (dropped.isEmpty()) prepared else bodyMetrics(remaining)
        val vocabulary = Vocabulary.of(body.lines) // lexicon for Stage 2

        // Stage 4 is per page (a table's column anchors are a page-local measure),
        // but a paragraph legitimately flows from one page onto the next — so
        // adjacent text blocks are merged back together before stages 2 and 3 run.
        val blocks = body.lines.groupBy { it.page }.toSortedMap().values
            .flatMap { splitIntoBlocks(it, body) }
            .fold(mutableListOf<Block>()) { acc, block ->
                val open = acc.lastOrNull()
                if (block is Block.Text && open is Block.Text) acc[acc.lastIndex] = Block.Text(open.lines + block.lines)
                else acc += block
                acc
            }

        val segments = blocks.flatMap { block ->
            when (block) {
                is Block.Table -> linearizeTable(block)
                is Block.Text -> flowText(block.lines, body, vocabulary) // Stages 2 + 3
            }
        }
        return segments.filter { it.text.isNotBlank() }
    }

    /**
     * Convenience bridge to the existing text-based reading path: renders
     * segments with the whitespace conventions [app.lector.core.Segmenter]
     * already understands — a blank line for a paragraph pause, a single
     * newline for sentence/table-row pauses.
     */
    fun cleanToText(runs: List<PdfRun>): String = render(clean(runs))

    // -- Stage 1.5 - figure internals ------------------------------------------

    /** A block of lines that sits off the body column and may belong to a figure. */
    internal class FigureRegion(
        val page: Int,
        val range: IntRange,
        val top: Float,
        val bottom: Float,
        /** A figure caption follows immediately, which is evidence on its own. */
        val captionAnchored: Boolean,
    )

    /**
     * Propose the blocks that look like the inside of a figure: a run of
     * consecutive lines that do not start on the body column, bounded below by a
     * caption or by ordinary prose.
     *
     * The block is taken from the line stream rather than from a graphic's bounding
     * box because the two do not coincide — axis labels and legends sit *outside*
     * the plot panel, so anything derived from where the ink is would keep exactly
     * the text we are trying to drop.
     */
    internal fun findFigureRegions(body: BodyMetrics): List<FigureRegion> {
        val lines = body.lines
        if (lines.isEmpty()) return emptyList()
        val margin = dominantMargin(lines, body)
        val slack = body.em * config.figureMarginEm

        fun offColumn(i: Int) = abs(lines[i].xStart - margin) > slack
        fun caption(i: Int) = CAPTION.containsMatchIn(lines[i].text)

        val regions = mutableListOf<FigureRegion>()
        var i = 0
        while (i < lines.size) {
            if (!offColumn(i) || caption(i)) {
                i++
                continue
            }
            var end = i
            while (end < lines.size && lines[end].page == lines[i].page && offColumn(end) && !caption(end)) end++
            if (end - i >= config.minFigureLines) {
                regions += FigureRegion(
                    page = lines[i].page,
                    range = i until end,
                    top = (i until end).minOf { lines[it].y },
                    bottom = (i until end).maxOf { lines[it].y },
                    // Only a figure caption anchors a block. A table caption sits below
                    // its table the same way, and that content has to survive.
                    captionAnchored = end < lines.size && lines[end].page == lines[i].page &&
                        FIGURE_CAPTION.containsMatchIn(lines[end].text),
                )
            }
            i = end
        }
        return regions
    }

    /** The x the body column starts at: the most popular left edge in the document. */
    private fun dominantMargin(lines: List<PdfLine>, body: BodyMetrics): Float {
        val quantum = (body.em * 0.5f).coerceAtLeast(1f)
        val winner = lines.groupBy { (it.xStart / quantum).toInt() }
            .maxByOrNull { it.value.size }?.value ?: return lines.minOf { it.xStart }
        return winner.map { it.xStart }.average().toFloat()
    }

    /**
     * Ink decides, when there is any. A page nobody scanned falls back to the
     * caption, which is the one signal trustworthy on its own; a page that was
     * scanned and came back bare is prose that merely looks unusual, and is kept.
     */
    private fun isFigure(region: FigureRegion, ink: PageInk?, body: BodyMetrics): Boolean {
        if (ink == null) return region.captionAnchored
        if (ink.truncated) return true // too much drawn to be anything else
        val pad = body.em * config.figureInkPadEm
        if (ink.primitivesOver(region.top - pad, region.bottom + pad) >= config.minInkPrimitives) return true
        // Nothing was drawn over the block. If the page is bare too, this is prose
        // laid out oddly and it stays, whatever the caption says. But a page that
        // was drawn on heavily somewhere ELSE is a case where the two coordinate
        // systems failed to meet — landscape figures rotated inside an upright page
        // put their text in a rotated frame that the path boxes do not share — and
        // there the caption is the better evidence.
        return if (ink.primitives.size >= config.minInkPrimitives) region.captionAnchored else false
    }

    fun render(segments: List<CleanedSegment>): String {
        val sb = StringBuilder()
        for ((i, seg) in segments.withIndex()) {
            sb.append(seg.text)
            if (i == segments.lastIndex) continue
            sb.append(
                when (seg.pause) {
                    PauseLevel.PARAGRAPH -> "\n\n"
                    PauseLevel.SENTENCE, PauseLevel.TABLE_ROW -> "\n"
                    PauseLevel.NONE -> " "
                },
            )
        }
        return sb.toString().trim()
    }

    // -- Stage 0 - structured extraction --------------------------------------

    /**
     * Cluster one page's runs into visual lines by y-band, then order them.
     *
     * [gutterX], when known, keeps a y-band cluster from spanning it: two runs
     * that land in the same band purely by coincidence — the top line of a left
     * column and the top line of a right one, or two side-by-side tables whose
     * rows share a leading grid — would otherwise be joined into one [PdfLine]
     * by [joinRuns] before either column even exists as a separate object, which
     * [reorderColumns] downstream is too late to undo. A run that itself crosses
     * [gutterX] (a heading, a full-width table cell) is never split against.
     */
    internal fun buildLines(pageRuns: List<PdfRun>, gutterX: Float? = null): List<PdfLine> {
        if (pageRuns.isEmpty()) return emptyList()
        val tolerance = (median(pageRuns.map { it.height }) * config.lineBandFactor)
            .coerceAtLeast(0.5f)

        val sorted = pageRuns.sortedWith(compareBy<PdfRun> { it.y }.thenBy { it.xStart })
        val clusters = mutableListOf<MutableList<PdfRun>>()
        for (run in sorted) {
            val open = clusters.lastOrNull()
            val sameBand = open != null && run.y - open.first().y <= tolerance
            if (sameBand && (gutterX == null || !straddlesGutter(open!!, run, gutterX))) open!! += run
            else clusters += mutableListOf(run)
        }
        return clusters.map { cluster ->
            val ordered = cluster.sortedBy { it.xStart }
            PdfLine(
                page = ordered.first().page,
                pageHeight = ordered.first().pageHeight,
                y = ordered.minOf { it.y },
                xStart = ordered.minOf { it.xStart },
                xEnd = ordered.maxOf { it.xEnd },
                height = ordered.maxOf { it.height },
                fontSize = ordered.maxOf { it.fontSize },
                text = joinRuns(ordered),
                runs = ordered,
            )
        }.filter { it.text.isNotBlank() }
    }

    /**
     * True when the open cluster and the candidate run sit on strictly opposite
     * sides of [gutterX], with nothing on either side actually crossing it —
     * i.e. merging them would splice two different columns' lines into one.
     * A row that legitimately spans the gutter (a run straddling it, in the
     * cluster or the candidate) is left alone; that is ordinary single-column
     * content, or a table cell wider than one column, not two columns.
     */
    private fun straddlesGutter(open: List<PdfRun>, run: PdfRun, gutterX: Float): Boolean {
        if (run.xStart < gutterX && run.xEnd > gutterX) return false
        if (open.any { it.xStart < gutterX && it.xEnd > gutterX }) return false
        val openOnLeft = open.any { it.xEnd <= gutterX }
        val openOnRight = open.any { it.xStart >= gutterX }
        val runOnRight = run.xStart >= gutterX
        val runOnLeft = run.xEnd <= gutterX
        return (openOnLeft && runOnRight) || (openOnRight && runOnLeft)
    }

    /**
     * The page's column gutter, or null without persistent evidence of one:
     * at least [PdfCleanerConfig.minColumnLines] lines sitting entirely left of
     * the content's horizontal midpoint, and as many entirely right of it,
     * neither wide enough to count as spanning both sides.
     */
    private fun columnGutter(lines: List<PdfLine>): Float? {
        if (lines.size < config.minColumnLines * 2) return null
        val minX = lines.minOf { it.xStart }
        val maxX = lines.maxOf { it.xEnd }
        val contentWidth = maxX - minX
        if (contentWidth <= 0f) return null
        val midX = minX + contentWidth / 2f
        val spanWidth = contentWidth * config.columnSpanRatio
        val left = lines.count { it.width < spanWidth && it.xEnd <= midX }
        val right = lines.count { it.width < spanWidth && it.xStart >= midX }
        return if (left >= config.minColumnLines && right >= config.minColumnLines) midX else null
    }

    /**
     * Stage 0.5 — put a two-column page's lines in reading order.
     *
     * Stage 0 sorts purely by y then x, which is correct for a single column but
     * reads across the gutter on a two-column page: the top line of the right
     * column shares its y-band with the top line of the left one, so it comes
     * right after it, splicing two unrelated lines of prose (or two side-by-side
     * tables) into one. A reader never does this — the whole left column is read
     * top to bottom, then the whole right one.
     *
     * A line spanning most of the content width (a heading, a caption, a table or
     * figure laid across both columns) cannot belong to either column and acts as
     * a hard break: whatever is pending on each side flushes — left column first,
     * then right — the spanning line is emitted, and accumulation starts fresh
     * beneath it.
     */
    internal fun reorderColumns(lines: List<PdfLine>, gutterX: Float): List<PdfLine> {
        val minX = lines.minOf { it.xStart }
        val maxX = lines.maxOf { it.xEnd }
        val spanWidth = (maxX - minX) * config.columnSpanRatio

        // -1 = left column, +1 = right column, 0 = spans both (or straddles the
        // gutter without being wide enough to call spanning — safest read the same).
        fun side(line: PdfLine): Int = when {
            line.width >= spanWidth -> 0
            line.xEnd <= gutterX -> -1
            line.xStart >= gutterX -> 1
            else -> 0
        }

        val result = ArrayList<PdfLine>(lines.size)
        val pendingLeft = mutableListOf<PdfLine>()
        val pendingRight = mutableListOf<PdfLine>()
        fun flush() {
            result += pendingLeft; pendingLeft.clear()
            result += pendingRight; pendingRight.clear()
        }
        for (line in lines) {
            when (side(line)) {
                -1 -> pendingLeft += line
                1 -> pendingRight += line
                else -> { flush(); result += line }
            }
        }
        flush()
        return result
    }

    /** Concatenate runs, inserting a space wherever the glyphs left one. */
    private fun joinRuns(runs: List<PdfRun>): String {
        val sb = StringBuilder()
        for (run in runs) {
            val piece = run.text.trim()
            if (piece.isEmpty()) continue
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(piece)
        }
        return sb.toString().trim()
    }

    // -- Stage 1 - header / footer / page-number removal -----------------------

    /**
     * Drop the furniture in the top and bottom bands. Three signals, because a
     * running header only sometimes repeats its text:
     *
     *  (a) the digit-normalized text recurs on more than θ of the pages — this is
     *      what catches "Page 7 of 92", whose literal text differs every time;
     *  (b) the y-band recurs on more than θ of the pages whatever the text says —
     *      a per-section running head ("3.2. Data Preparation 14") never repeats
     *      itself, but it always sits in the same place, and body text does not
     *      live in the outer 9% of a page;
     *  (c) the line is nothing but a page number, in any of the usual spellings.
     *      A bare numeral alone in a margin is never content, so this one needs
     *      no recurrence evidence and works on a two-page document.
     */
    internal fun stripRunningFurniture(lines: List<PdfLine>, pageCount: Int): List<PdfLine> {
        val byText = HashMap<String, MutableSet<Int>>()
        val byBand = HashMap<Int, MutableSet<Int>>()
        for (line in lines) {
            if (!inMargin(line)) continue
            byText.getOrPut(normalizeKey(line.text)) { mutableSetOf() } += line.page
            byBand.getOrPut(marginBand(line)) { mutableSetOf() } += line.page
        }

        val enoughPages = pageCount >= config.minPagesForRecurrence
        val threshold = config.recurrenceThreshold
        val furnitureText: Set<String> = if (!enoughPages) emptySet()
        else byText.filterValues { it.size.toFloat() / pageCount > threshold }.keys
        // A band is counted together with its immediate neighbours: a baseline that
        // wobbles a point or two across pages would otherwise split one running head
        // over two bands, leaving each below θ and the header on the page.
        val furnitureBands: Set<Int> = if (!enoughPages) emptySet() else byBand.keys.filterTo(HashSet()) { band ->
            val pages = HashSet<Int>()
            for (neighbour in band - 1..band + 1) pages.addAll(byBand[neighbour] ?: emptySet())
            pages.size.toFloat() / pageCount > threshold
        }

        return lines.filterNot { line ->
            if (!inMargin(line)) return@filterNot false
            val key = normalizeKey(line.text)
            isPageNumber(key) || key in furnitureText || marginBand(line) in furnitureBands
        }
    }

    private fun inMargin(line: PdfLine): Boolean =
        line.relativeY <= config.headerBand || line.relativeY >= 1f - config.footerBand

    private fun marginBand(line: PdfLine): Int =
        (line.relativeY / config.marginBandQuantum).toInt()

    /** h(l): digits to '#', whitespace collapsed, case-folded. */
    private fun normalizeKey(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c.isDigit()) '#' else c)
        return sb.toString().replace(WHITESPACE, " ").trim().lowercase()
    }

    /** True for a normalized margin line that carries a page number and nothing else. */
    private fun isPageNumber(key: String): Boolean = PAGE_NUMBER.matches(key)

    // -- Document-level measures shared by later stages ------------------------

    /**
     * Body-text statistics the paragraph and heading tests are measured against.
     * Computed once over the whole document so a page of headings can't redefine
     * what "body font" means.
     */
    internal data class BodyMetrics(
        val lines: List<PdfLine>,
        val fontSize: Float,
        val lineHeight: Float,
        val width: Float,
    ) {
        /** One em of body text, the unit the x-tolerances are expressed in. */
        val em: Float get() = fontSize
    }

    internal fun bodyMetrics(lines: List<PdfLine>): BodyMetrics {
        if (lines.isEmpty()) return BodyMetrics(lines, 12f, 14f, 400f)
        val fontSize = median(lines.map { it.fontSize }).coerceAtLeast(1f)
        // Line height from consecutive same-page gaps; column jumps go upward and
        // inter-paragraph gaps are outliers, so both are filtered before the median.
        val gaps = lines.zipWithNext()
            .filter { (a, b) -> a.page == b.page && b.y > a.y }
            .map { (a, b) -> b.y - a.y }
            .filter { it < fontSize * 4f }
        val lineHeight = if (gaps.isEmpty()) fontSize * 1.2f else median(gaps).coerceAtLeast(1f)
        // Body measure = a high percentile of line widths, i.e. a full-width body line.
        val widths = lines.map { it.width }.sorted()
        val width = widths[minOf(widths.size - 1, (widths.size * 9) / 10)].coerceAtLeast(1f)
        return BodyMetrics(lines, fontSize, lineHeight, width)
    }

    /** A line whose type deviates from body text and is short: its own utterance. */
    internal fun isHeading(line: PdfLine, body: BodyMetrics): Boolean {
        val short = line.width <= body.width * config.headingWidthRatio
        if (!short) return false
        // A lone superscript affiliation marker ("a", "b", "1") is short and often
        // sits in a smaller face than body text, which would otherwise satisfy the
        // size-deviation heading test below and strand it as its own one-character
        // utterance instead of leaving it to flow with the affiliation line it
        // marks. A real heading, numbered or all-caps, is never this short.
        val letters = line.text.filter { it.isLetter() }
        if (letters.length < 3) return false
        val sizeRatio = line.fontSize / body.fontSize
        if (sizeRatio >= config.headingSizeRatio || sizeRatio <= 1f / config.headingSizeRatio) return true
        // A numbered section heading. Academic templates set the deeper levels barely
        // larger than body text, too close to call on size, but a short line that opens
        // with its own section number and does not close like a sentence is a heading.
        // A wrapped list item can open the same way, which is why "short" comes first.
        if (NUMBERED_HEADING.containsMatchIn(line.text) && !SENTENCE_END.containsMatchIn(line.text)) return true
        // Style deviation we can see without font names: a short ALL-CAPS line.
        return letters.all { it.isUpperCase() }
    }

    /**
     * True when two adjacent heading lines are one heading wrapped over two lines
     * (a display title) rather than two separate headings. Display type is set
     * with generous leading, so the gap is measured against the heading's own
     * size rather than the body line height.
     */
    private fun continuesHeading(line: PdfLine, next: PdfLine): Boolean {
        if (next.page != line.page || next.y <= line.y) return false
        if (abs(next.fontSize - line.fontSize) > line.fontSize * 0.05f) return false
        if (abs(next.xStart - line.xStart) > line.fontSize) return false
        return next.y - line.y <= line.fontSize * config.headingWrapFactor
    }

    // -- Stage 4 - table detection and linearization ---------------------------

    private sealed interface Block {
        data class Text(val lines: List<PdfLine>) : Block
        data class Table(val rows: List<List<PdfCell>>) : Block
    }

    /** Split one page's lines into table blocks and plain-text blocks. */
    private fun splitIntoBlocks(pageLines: List<PdfLine>, body: BodyMetrics): List<Block> {
        val blocks = mutableListOf<Block>()
        val pending = mutableListOf<PdfLine>()
        var i = 0
        while (i < pageLines.size) {
            val table = detectTableAt(pageLines, i, body)
            if (table == null) {
                pending += pageLines[i]
                i++
            } else {
                if (pending.isNotEmpty()) {
                    blocks += Block.Text(pending.toList())
                    pending.clear()
                }
                blocks += table.block
                i = table.endExclusive
            }
        }
        if (pending.isNotEmpty()) blocks += Block.Text(pending)
        return blocks
    }

    private data class TableMatch(val block: Block.Table, val endExclusive: Int)

    /**
     * A table is a maximal run of consecutive lines that each split into two or
     * more gutter-separated cells, where the modal cell count k is reached by at
     * least [PdfCleanerConfig.minTableRows] of them and those rows form real,
     * non-overlapping columns.
     *
     * The grid is read positionally, not by x-coordinate: a header cell is often
     * far wider than the values beneath it (a right-aligned numeric column under
     * a long label), so matching cells to columns by their left edge would throw
     * the header row out of the table and promote the first data row into its
     * place. Rows that are short a cell fall back to nearest-anchor placement.
     */
    private fun detectTableAt(lines: List<PdfLine>, start: Int, body: BodyMetrics): TableMatch? {
        var end = start
        val candidates = mutableListOf<List<PdfCell>>()
        while (end < lines.size) {
            val cells = splitCells(lines[end], body)
            if (cells.size < 2) break
            candidates += cells
            end++
        }
        if (candidates.size < config.minTableRows) return null

        // Ties go to the wider grid: a table with a few short rows is still that table.
        val columns = candidates.groupingBy { it.size }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
            .first().key
        if (columns < 2) return null

        val full = candidates.filter { it.size == columns }
        if (full.size < config.minTableRows) return null
        if (!columnsSeparate(full, columns)) return null

        val anchors = (0 until columns).map { j -> median(full.map { it[j].xStart }) }
        return TableMatch(Block.Table(candidates.map { alignToAnchors(it, anchors) }), end)
    }

    /**
     * Real columns never overlap: every cell of column j ends before any cell of
     * column j+1 begins. This holds whatever the alignment inside the column is,
     * and it is what separates a table from a few ragged lines that happen to
     * carry a wide gap.
     */
    private fun columnsSeparate(rows: List<List<PdfCell>>, columns: Int): Boolean {
        for (j in 0 until columns - 1) {
            if (rows.maxOf { it[j].xEnd } >= rows.minOf { it[j + 1].xStart }) return false
        }
        return true
    }

    /** Group a line's runs into cells, breaking wherever the gutter exceeds [gutterEm]. */
    internal fun splitCells(line: PdfLine, body: BodyMetrics): List<PdfCell> {
        if (line.runs.isEmpty()) return emptyList()
        val gutter = body.em * config.gutterEm
        val cells = mutableListOf<MutableList<PdfRun>>()
        for (run in line.runs) {
            val open = cells.lastOrNull()
            if (open != null && run.xStart - open.last().xEnd <= gutter) open += run
            else cells += mutableListOf(run)
        }
        return cells.mapNotNull { group ->
            val text = joinRuns(group)
            if (text.isBlank()) null
            else PdfCell(text, group.minOf { it.xStart }, group.maxOf { it.xEnd })
        }
    }

    /**
     * A full row keeps its reading order; a short row has each cell placed in its
     * nearest column, leaving the unfilled slots empty.
     */
    private fun alignToAnchors(cells: List<PdfCell>, anchors: List<Float>): List<PdfCell> {
        if (cells.size == anchors.size) return cells
        val slots = MutableList(anchors.size) { PdfCell("", anchors[it], anchors[it]) }
        for (cell in cells) {
            var best = 0
            for (k in anchors.indices) {
                if (abs(anchors[k] - cell.xStart) < abs(anchors[best] - cell.xStart)) best = k
            }
            // Two cells nearest the same anchor append rather than overwrite, so a
            // misclustered column never silently drops text.
            val existing = slots[best]
            slots[best] = if (existing.text.isBlank()) cell
            else PdfCell("${existing.text} ${cell.text}", existing.xStart, cell.xEnd)
        }
        return slots
    }

    /**
     * "Row j: c1 is v1, c2 is v2." — one utterance per row, so a listener hears
     * column semantics instead of PdfBox's raster stream of cell fragments.
     */
    private fun linearizeTable(table: Block.Table): List<CleanedSegment> {
        val header = table.rows.first().map { it.text.trim() }
        val dataRows = table.rows.drop(1)
        if (dataRows.isEmpty()) {
            // Degenerate block: read it as plain rows rather than lose the content.
            return table.rows.map { row ->
                CleanedSegment(row.joinToString(" ") { it.text }.trim(), PauseLevel.TABLE_ROW)
            }
        }
        val labelled = header.all { it.isNotBlank() }
        return dataRows.mapIndexed { j, row ->
            val spoken = row.mapIndexed { k, cell ->
                val value = cell.text.trim().ifBlank { "blank" }
                val label = header.getOrNull(k).orEmpty()
                if (labelled) "$label is $value" else value
            }.joinToString(", ")
            CleanedSegment("Row ${j + 1}: $spoken.", PauseLevel.TABLE_ROW)
        }
    }

    // -- Stages 2 + 3 - dehyphenation and soft-wrap joining ---------------------

    /**
     * Walk a block of body lines. Heading lines are pulled out first — a display
     * title wrapped over several lines becomes one heading, not one per line —
     * and everything between them is flowed into paragraphs.
     */
    private fun flowText(lines: List<PdfLine>, body: BodyMetrics, vocabulary: Vocabulary): List<CleanedSegment> {
        val segments = mutableListOf<CleanedSegment>()
        var i = 0
        while (i < lines.size) {
            if (isHeading(lines[i], body)) {
                var j = i + 1
                while (j < lines.size && isHeading(lines[j], body) && continuesHeading(lines[j - 1], lines[j])) j++
                segments += CleanedSegment(joinLines(lines.subList(i, j), vocabulary), PauseLevel.PARAGRAPH)
                i = j
            } else {
                var j = i
                while (j < lines.size && !isHeading(lines[j], body)) j++
                segments += flowParagraphs(lines.subList(i, j), body, vocabulary)
                i = j
            }
        }
        return segments
    }

    /** Concatenate lines, dehyphenating across each break rather than spacing it. */
    private fun joinLines(lines: List<PdfLine>, vocabulary: Vocabulary): String {
        val sb = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            sb.append(line.text)
            val next = lines.getOrNull(i + 1) ?: continue
            if (dehyphenates(line.text, next.text, vocabulary)) sb.setLength(sb.length - 1)
            else sb.append(' ')
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    private fun flowParagraphs(lines: List<PdfLine>, body: BodyMetrics, vocabulary: Vocabulary): List<CleanedSegment> {
        val segments = mutableListOf<CleanedSegment>()
        val current = StringBuilder()
        var lineCount = 0
        // The paragraph's margin is what its SECOND line onward agrees on. The first
        // line is free to sit either side of it: indented (a first-line indent) or
        // out-dented (the hanging indent of a reference or a list item).
        var bodyLeft: Float? = null

        fun flush() {
            val text = current.toString().replace(WHITESPACE, " ").trim()
            if (text.isNotEmpty()) segments += CleanedSegment(text, PauseLevel.PARAGRAPH)
            current.setLength(0)
            lineCount = 0
            bodyLeft = null
        }

        for ((i, line) in lines.withIndex()) {
            current.append(line.text)
            lineCount++
            if (lineCount == 2) bodyLeft = line.xStart
            else if (lineCount > 2) bodyLeft = minOf(bodyLeft ?: line.xStart, line.xStart)

            val next = lines.getOrNull(i + 1) ?: continue
            if (!isSoftWrap(line, next, bodyLeft, body)) {
                flush()
                continue
            }
            // Stage 2 runs only inside a confirmed soft wrap — that is exactly the
            // "still inside the same paragraph flow" precondition the spec puts on
            // dehyphenation.
            if (dehyphenates(line.text, next.text, vocabulary)) {
                current.setLength(current.length - 1) // drop the hyphen, insert no space
            } else {
                current.append(' ')
            }
        }
        flush()
        return segments
    }

    /**
     * Stage 3. [bodyLeft] is the paragraph's established margin, or null while
     * [next] would only be its second line and the margin is not yet known.
     */
    internal fun isSoftWrap(line: PdfLine, next: PdfLine, bodyLeft: Float?, body: BodyMetrics): Boolean {
        // (1) no sentence-terminal punctuation, ignoring trailing quotes/brackets.
        if (SENTENCE_END.containsMatchIn(line.text)) return false
        // (2) the next line sits on the paragraph's margin. Before that margin exists,
        // allow the wider swing of a first-line indent or a hanging indent either way.
        val allowed = if (bodyLeft == null) config.firstLineIndentEm else config.indentToleranceEm
        if (abs(next.xStart - (bodyLeft ?: line.xStart)) > body.em * allowed) return false
        // (3) a single line-height step down. Across a page break there is no gap to
        // measure — a paragraph legitimately continues onto the next page, so the
        // other conditions decide there.
        if (next.page != line.page) return true
        if (next.y <= line.y) return false // a new column, not the next line
        return next.y - line.y <= body.lineHeight * config.lineGapFactor
    }

    /**
     * Stage 2: true when [line] ends in a word-splitting hyphen that should be
     * removed before [next] is appended.
     *
     * After the structural guards, the document's own usage decides. Whichever
     * spelling the text uses more often elsewhere wins, and when it uses neither
     * the hyphen is dropped: a hyphen at a line end is far more often typesetting
     * than orthography, so removal is the better default.
     */
    internal fun dehyphenates(line: String, next: String, vocabulary: Vocabulary): Boolean {
        val trimmed = line.trimEnd()
        if (!endsHyphenated(trimmed)) return false
        val stem = trimmed.dropLast(1)
        if (stem.lastOrNull()?.isLowerCase() != true) return false // en/em dash, "A-", "3-"
        val fragment = stem.takeLastWhile { it.isLetter() }
        if (fragment.length < 2) return false // single-letter line-end artifact
        val tail = next.trimStart().takeWhile { it.isLetter() }
        if (tail.isEmpty() || !tail.first().isLowerCase()) return false
        if (tail.drop(1).any { it.isUpperCase() }) return false // internal capital: not one word
        return vocabulary.solidCount(fragment, tail) >= vocabulary.hyphenatedCount(fragment, tail)
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")

        /**
         * Sentence-terminal punctuation at end of line, allowing trailing closing
         * quotes/brackets after the mark (`…end."`, `…end.)`).
         */
        val SENTENCE_END = Regex("""[.?!:;][\p{Pf}"')\]}”’]*\s*$""")

        /** A line opening with its own section number, e.g. "3.6.5. Forecast bounding". */
        val NUMBERED_HEADING = Regex("""^\d+(\.\d+)*\.?\s+\p{L}""")

        /**
         * A caption label, which bounds an off-column block from below.
         *
         * The colon is optional and the number need not be a number — LaTeX writes
         * "Table 3.1: Cleaned CBS dataset" while Cambridge writes "Figure PII.2 A
         * decision tree" — so what actually separates a caption from a sentence
         * that merely opens with a cross-reference is the capital letter after the
         * label. "Table 4.1 Top 20 interest groups" is a caption; "Table 4.1 gives
         * a notion of the diversity" is prose about it.
         */
        val CAPTION = Regex("""^(Figure|Fig\.|Table|Tab\.|Listing|Algorithm|Scheme)\s+[0-9A-Z]+([.-][0-9A-Za-z]+)*[.:]?\s+\p{Lu}""")

        /** Only a figure caption anchors a block for removal. */
        val FIGURE_CAPTION = Regex("""^(Figure|Fig\.)\s+[0-9A-Z]+([.-][0-9A-Za-z]+)*[.:]?\s+\p{Lu}""")

        /**
         * A margin line that is only a page number, digit-normalized: "7", "vii",
         * "- 7 -", "7 of 92", "page 7 of 92".
         */
        val PAGE_NUMBER = Regex("""^(-\s*)?(#+|[ivxlcdm]{1,7}|(page\s+)?#+(\s+of\s+#+)?)(\s*-)?$""")
    }
}

/** Median of a float list; 0 for an empty one. */
private fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
