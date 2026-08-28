package app.lector.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stages 1–4 are pure geometry, so every case here is a hand-built page layout —
 * no PDF and no Android needed. Coordinates are in points with y growing down.
 */
class PdfCleanerTest {

    private val cleaner = PdfCleaner()

    // -- helpers ---------------------------------------------------------------

    private val pageHeight = 800f
    private val fontSize = 10f
    private val lineHeight = 12f

    /** One run of body text at (x, y) on [page], width estimated from the text. */
    private fun run(
        page: Int,
        y: Float,
        x: Float,
        text: String,
        size: Float = fontSize,
        width: Float = text.length * size * 0.5f,
    ) = PdfRun(
        page = page,
        pageHeight = pageHeight,
        text = text,
        xStart = x,
        xEnd = x + width,
        y = y,
        height = size,
        fontSize = size,
    )

    /** Body text laid out as consecutive lines from [top], one run per line. */
    private fun paragraph(page: Int, top: Float, x: Float, vararg lines: String): List<PdfRun> =
        lines.mapIndexed { i, text -> run(page, top + i * lineHeight, x, text) }

    /** A vocabulary built from a document that contains exactly these lines. */
    private fun vocabularyOf(vararg lines: String): Vocabulary =
        Vocabulary.of(lines.map { cleaner.buildLines(listOf(run(1, 100f, 72f, it))).first() })

    // -- Stage 0 ---------------------------------------------------------------

    @Test
    fun `runs sharing a y-band become one line, ordered left to right`() {
        val lines = cleaner.buildLines(
            listOf(
                run(1, 100f, 300f, "second"),
                run(1, 100.2f, 72f, "first"),
                run(1, 112f, 72f, "next line"),
            ),
        )
        assertEquals(2, lines.size)
        assertEquals("first second", lines[0].text)
        assertEquals("next line", lines[1].text)
    }

    // -- Stage 1 ---------------------------------------------------------------

    @Test
    fun `page numbers are dropped even though the digits change per page`() {
        val runs = (1..6).flatMap { page ->
            listOf(
                run(page, 30f, 72f, "A Short History of Everything"), // running header
                run(page, 400f, 72f, "Body sentence on page $page."),
                run(page, 770f, 300f, "Page $page of 6"), // footer, digits differ
            )
        }
        val text = cleaner.cleanToText(runs)

        assertFalse("header survived", text.contains("A Short History"))
        assertFalse("page number survived", text.contains("Page 3 of 6"))
        assertTrue(text.contains("Body sentence on page 3."))
    }

    @Test
    fun `a running head is dropped even when its text changes on every page`() {
        // A per-section running head never repeats itself, but it always sits in the
        // same band — which is the only signal available for a document like a thesis.
        val runs = (1..6).flatMap { page ->
            listOf(
                run(page, 30f, 72f, "Chapter $page overview of the section"),
                run(page, 400f, 72f, "Body sentence on page $page."),
            )
        }
        val text = cleaner.cleanToText(runs)

        assertFalse("running head survived", text.contains("overview of the section"))
        assertTrue(text.contains("Body sentence on page 4."))
    }

    @Test
    fun `a margin line that appears on only a few pages is kept`() {
        val runs = (1..6).flatMap { page ->
            buildList {
                if (page <= 2) add(run(page, 30f, 72f, "A dedication that appears at the front"))
                add(run(page, 400f, 72f, "Body sentence on page $page."))
            }
        }
        assertTrue(cleaner.cleanToText(runs).contains("A dedication that appears at the front"))
    }

    @Test
    fun `a bare page number is furniture without needing any recurrence evidence`() {
        // Two pages is below the recurrence floor, so only the page-number rule can fire.
        val runs = listOf(
            run(1, 400f, 72f, "Body sentence on the first page."),
            run(1, 770f, 300f, "i"),
            run(2, 400f, 72f, "Body sentence on the second page."),
            run(2, 770f, 300f, "ii"),
        )
        val segments = cleaner.clean(runs)
        assertEquals(2, segments.size)
        assertTrue(segments.none { it.text == "i" || it.text == "ii" })
    }

    @Test
    fun `a two-page document keeps its margins - recurrence is meaningless`() {
        val runs = (1..2).flatMap { page ->
            listOf(run(page, 30f, 72f, "Running Head"), run(page, 400f, 72f, "Body."))
        }
        assertTrue(cleaner.cleanToText(runs).contains("Running Head"))
    }

    // -- Stage 2 ---------------------------------------------------------------

    @Test
    fun `a line-end hyphen goes when the document offers no evidence either way`() {
        assertTrue(cleaner.dehyphenates("the pipeline is compre-", "hensive and fast", Vocabulary.EMPTY))
        assertTrue(cleaner.dehyphenates("a sense of well-", "being matters", Vocabulary.EMPTY))
    }

    @Test
    fun `a compound the document hyphenates elsewhere keeps its hyphen`() {
        val vocabulary = vocabularyOf("well-being is the goal", "our well-being improved")
        assertFalse(cleaner.dehyphenates("a sense of well-", "being matters", vocabulary))
    }

    @Test
    fun `a word the document writes solid elsewhere is rejoined`() {
        // "under" would be a plausible compound prefix; the document says otherwise.
        val vocabulary = vocabularyOf("gave me a deeper understanding of the data")
        assertTrue(cleaner.dehyphenates("a deeper under-", "standing of both", vocabulary))
    }

    @Test
    fun `line-end artifacts keep their hyphen whatever the document says`() {
        val vocabulary = Vocabulary.EMPTY
        assertFalse("single-letter fragment", cleaner.dehyphenates("the a-", "typical case", vocabulary))
        assertFalse("next line starts a new sentence", cleaner.dehyphenates("cost-", "Benefit ratio", vocabulary))
        assertFalse("em dash, not a hyphen", cleaner.dehyphenates("she paused—", "then spoke", vocabulary))
        assertFalse("no hyphen at all", cleaner.dehyphenates("plain line", "continues here", vocabulary))
    }

    @Test
    fun `dehyphenation fires end to end inside a paragraph`() {
        val runs = paragraph(1, 100f, 72f, "The extraction stage is compre-", "hensive enough.")
        assertEquals("The extraction stage is comprehensive enough.", cleaner.cleanToText(runs))
    }

    // -- Stage 3 ---------------------------------------------------------------

    @Test
    fun `soft-wrapped lines join into one paragraph`() {
        val runs = paragraph(
            1, 100f, 72f,
            "A sentence that runs past the",
            "edge of the measure and keeps",
            "going to its end.",
        )
        assertEquals(
            "A sentence that runs past the edge of the measure and keeps going to its end.",
            cleaner.cleanToText(runs),
        )
    }

    @Test
    fun `a sentence end plus an indent starts a new paragraph`() {
        val runs = listOf(
            run(1, 100f, 72f, "The first paragraph ends here."),
            run(1, 112f, 90f, "The second one is indented."), // first-line indent
        )
        assertEquals(
            "The first paragraph ends here.\n\nThe second one is indented.",
            cleaner.cleanToText(runs),
        )
    }

    @Test
    fun `a first-line indent does not split the paragraph at its second line`() {
        val runs = listOf(
            run(1, 100f, 90f, "Indented opening line that wraps"),
            run(1, 112f, 72f, "back to the body margin below"),
            run(1, 124f, 72f, "and finishes."),
        )
        assertEquals(
            "Indented opening line that wraps back to the body margin below and finishes.",
            cleaner.cleanToText(runs),
        )
    }

    @Test
    fun `a hanging indent does not split the entry at its second line`() {
        // A bibliography: first line on the margin, continuations indented right, and
        // no terminal punctuation anywhere to fall back on.
        val runs = listOf(
            run(1, 100f, 72f, "Bass, F. M. (1969). A new product growth for model consumer durables"),
            run(1, 112f, 90f, "and the diffusion of durables"),
            run(1, 124f, 72f, "Rogers, E. M. (1983). Diffusion of innovation, a review of the field"),
            run(1, 136f, 90f, "and its many applications"),
        )
        val segments = cleaner.clean(runs)

        assertEquals(2, segments.size)
        assertTrue(segments[0].text.startsWith("Bass, F. M."))
        assertTrue(segments[0].text.endsWith("and the diffusion of durables"))
        assertTrue(segments[1].text.startsWith("Rogers, E. M."))
    }

    @Test
    fun `an extra-wide vertical gap ends the paragraph`() {
        val runs = listOf(
            run(1, 100f, 72f, "One line of body text"),
            run(1, 140f, 72f, "and a block far below it"),
        )
        assertTrue(cleaner.cleanToText(runs).contains("\n\n"))
    }

    @Test
    fun `a heading in a larger face is its own utterance`() {
        val runs = listOf(
            run(1, 100f, 72f, "Chapter Two", size = 18f, width = 90f),
            run(1, 130f, 72f, "The body text of the chapter starts here and runs on", width = 400f),
            run(1, 142f, 72f, "for a second line.", width = 400f),
        )
        val text = cleaner.cleanToText(runs)
        assertTrue(text.startsWith("Chapter Two\n\n"))
        assertTrue(text.contains("starts here and runs on for a second line."))
    }

    @Test
    fun `a display title wrapped over two lines stays one heading`() {
        val runs = listOf(
            run(1, 100f, 72f, "Forecasting residential", size = 40f, width = 260f),
            run(1, 150f, 72f, "PV adoption at", size = 40f, width = 200f),
        ) + (0..4).map { i ->
            run(1, 300f + i * lineHeight, 72f, "A body line of the abstract that follows", width = 400f)
        }
        val segments = cleaner.clean(runs)
        assertEquals("Forecasting residential PV adoption at", segments.first().text)
    }

    @Test
    fun `a numbered subheading set in body-sized type is still a heading`() {
        val runs = listOf(
            run(1, 100f, 72f, "3.1.1. Stedin Dataset", size = 11f, width = 120f),
            run(1, 118f, 72f, "One of the two main data sources used in this study is", width = 400f),
            run(1, 130f, 72f, "the electricity consumption dataset.", width = 400f),
        )
        val text = cleaner.cleanToText(runs)
        assertTrue(text.startsWith("3.1.1. Stedin Dataset\n\n"))
    }

    @Test
    fun `a wrapped list item is not mistaken for a numbered heading`() {
        val runs = listOf(
            run(1, 100f, 90f, "1. The electricity consumption dataset provided by Stedin", width = 400f),
            run(1, 112f, 105f, "in its published form"),
            run(1, 124f, 90f, "2. The neighbourhood statistics dataset published by CBS", width = 400f),
            run(1, 136f, 105f, "in its published form"),
        )
        val segments = cleaner.clean(runs)
        assertEquals(2, segments.size)
        assertTrue(segments[0].text.startsWith("1. The electricity"))
        assertTrue(segments[1].text.startsWith("2. The neighbourhood"))
    }

    @Test
    fun `a paragraph continues across a page break`() {
        val runs = listOf(
            run(1, 700f, 72f, "The argument continues on the"),
            run(2, 100f, 72f, "following page without a pause."),
        )
        assertEquals(
            "The argument continues on the following page without a pause.",
            cleaner.cleanToText(runs),
        )
    }

    // -- Stage 4 ---------------------------------------------------------------

    /** A three-column table: header plus two data rows, each cell its own run. */
    private fun tableRuns(): List<PdfRun> = listOf(
        run(1, 100f, 72f, "Region", width = 40f),
        run(1, 100f, 200f, "Units", width = 35f),
        run(1, 100f, 320f, "Revenue", width = 50f),
        run(1, 112f, 72f, "North", width = 35f),
        run(1, 112f, 200f, "120", width = 20f),
        run(1, 112f, 320f, "4.2m", width = 28f),
        run(1, 124f, 72f, "South", width = 35f),
        run(1, 124f, 200f, "95", width = 15f),
        run(1, 124f, 320f, "3.1m", width = 28f),
    )

    @Test
    fun `a table is read by column, one utterance per row`() {
        val segments = cleaner.clean(tableRuns())
        assertEquals(2, segments.size) // header row supplies labels, not an utterance
        assertEquals("Row 1: Region is North, Units is 120, Revenue is 4.2m.", segments[0].text)
        assertEquals("Row 2: Region is South, Units is 95, Revenue is 3.1m.", segments[1].text)
        assertTrue(segments.all { it.pause == PauseLevel.TABLE_ROW })
    }

    @Test
    fun `a wide header over a right-aligned column still labels that column`() {
        // The header cell starts far left of the digits beneath it. Matching cells to
        // columns by their left edge would throw this row out of the table entirely.
        val runs = mutableListOf(
            run(1, 100f, 72f, "Year", width = 28f),
            run(1, 100f, 250f, "Number of neighbourhood observations", width = 190f),
        )
        listOf("12003", "12005", "12237", "12822").forEachIndexed { i, count ->
            val y = 112f + i * lineHeight
            runs += run(1, y, 72f, "${2013 + i}", width = 28f)
            runs += run(1, y, 412f, count, width = 28f)
        }
        val rows = cleaner.clean(runs).filter { it.pause == PauseLevel.TABLE_ROW }

        assertEquals(4, rows.size)
        assertEquals(
            "Row 1: Year is 2013, Number of neighbourhood observations is 12003.",
            rows.first().text,
        )
    }

    @Test
    fun `table rows do not fuse with the prose around them`() {
        val runs = paragraph(1, 60f, 72f, "Sales by region are shown below.") + tableRuns() +
            paragraph(1, 160f, 72f, "The northern result was the strongest.")
        val segments = cleaner.clean(runs)

        assertEquals("Sales by region are shown below.", segments.first().text)
        assertEquals("The northern result was the strongest.", segments.last().text)
        assertEquals(2, segments.count { it.pause == PauseLevel.TABLE_ROW })
    }

    @Test
    fun `two ragged columns are not mistaken for a table`() {
        val runs = listOf(
            run(1, 100f, 72f, "A line with a", width = 60f),
            run(1, 100f, 300f, "wide gap in it", width = 60f),
            run(1, 112f, 72f, "Only two such lines here", width = 200f),
        )
        assertTrue(cleaner.clean(runs).none { it.pause == PauseLevel.TABLE_ROW })
    }

    @Test
    fun `an empty cell is spoken as blank rather than shifting the columns`() {
        val runs = listOf(
            run(1, 100f, 72f, "Item", width = 30f),
            run(1, 100f, 200f, "Qty", width = 25f),
            run(1, 100f, 320f, "Price", width = 35f),
            run(1, 112f, 72f, "Bolt", width = 30f),
            run(1, 112f, 200f, "12", width = 15f),
            run(1, 112f, 320f, "0.10", width = 28f),
            run(1, 124f, 72f, "Nut", width = 25f),
            run(1, 124f, 200f, "7", width = 10f),
            run(1, 124f, 320f, "0.05", width = 28f),
            run(1, 136f, 72f, "Screw", width = 38f),
            run(1, 136f, 320f, "0.20", width = 28f), // Qty missing on this row
        )
        val rows = cleaner.clean(runs).filter { it.pause == PauseLevel.TABLE_ROW }

        assertEquals(3, rows.size)
        // The value must stay in the Price column instead of sliding left into Qty.
        assertEquals("Row 3: Item is Screw, Qty is blank, Price is 0.20.", rows.last().text)
    }

    // -- Stage 1.5 - figure internals ------------------------------------------

    /** Body prose, a chart's stray labels, its caption, then more prose. */
    private fun figurePage(caption: String): List<PdfRun> = listOf(
        run(1, 100f, 72f, "The aggregate series is reported below in full", width = 400f),
        run(1, 112f, 72f, "for every year of the study period.", width = 400f),
        run(1, 150f, 200f, "0 5 10 15 20 25", width = 80f),
        run(1, 162f, 210f, "2013 2014 2015 2016", width = 75f),
        run(1, 174f, 230f, "All neighbourhoods", width = 90f),
        run(1, 190f, 100f, caption, width = 350f),
        run(1, 220f, 72f, "The aggregate series conceals variation between areas.", width = 400f),
    )

    /** [count] painted primitives spanning the chart's vertical band. */
    private fun inkOver(count: Int, top: Float = 145f, bottom: Float = 180f) =
        mapOf(1 to PageInk(1, List(count) { PdfRect(200f, top, 400f, bottom) }))

    @Test
    fun `a figure caption is enough to drop the labels above it`() {
        val text = cleaner.cleanToText(figurePage("Figure 4.1: Aggregate PV adoption in the study area."))

        assertFalse("axis labels survived", text.contains("2013 2014 2015 2016"))
        assertFalse("legend survived", text.contains("All neighbourhoods"))
        assertTrue("caption was lost", text.contains("Figure 4.1: Aggregate PV adoption"))
        assertTrue(text.contains("The aggregate series conceals variation"))
    }

    @Test
    fun `ink over the block confirms it and the labels go`() {
        val prepared = cleaner.prepare(figurePage("Figure 4.1: Aggregate PV adoption in the study area."))
        assertEquals(setOf(1), prepared.figureCandidatePages)

        val text = cleaner.render(prepared.finish(inkOver(40)))
        assertFalse(text.contains("2013 2014 2015 2016"))
    }

    @Test
    fun `a scanned page that came back bare keeps its text`() {
        // Prose laid out oddly, under a caption, but nothing was drawn there: the ink
        // outranks the caption, because the caption is the weaker signal.
        val prepared = cleaner.prepare(figurePage("Figure 4.1: Aggregate PV adoption in the study area."))
        val text = cleaner.render(prepared.finish(inkOver(3)))

        assertTrue(text.contains("2013 2014 2015 2016"))
    }

    @Test
    fun `an off-column block with no caption survives without ink to convict it`() {
        val runs = figurePage("A further remark that is not a caption at all")
        val text = cleaner.cleanToText(runs)

        assertTrue(text.contains("2013 2014 2015 2016"))
    }

    @Test
    fun `a table caption does not anchor its table for removal`() {
        // A table sits off the body column and is captioned from below, exactly like a
        // figure. Its rules are the only ink, and there are far too few of them.
        val runs = listOf(
            run(1, 76f, 72f, "The coverage of the panel is reported below in the", width = 400f),
            run(1, 88f, 72f, "table that follows, which lists the neighbourhoods", width = 400f),
            run(1, 100f, 72f, "observed in each year.", width = 400f),
            run(1, 130f, 200f, "Year", width = 28f),
            run(1, 130f, 320f, "Neighbourhoods", width = 80f),
            run(1, 142f, 200f, "2013", width = 28f),
            run(1, 142f, 320f, "2471", width = 28f),
            run(1, 154f, 200f, "2014", width = 28f),
            run(1, 154f, 320f, "2477", width = 28f),
            run(1, 166f, 200f, "2015", width = 28f),
            run(1, 166f, 320f, "2482", width = 28f),
            run(1, 185f, 100f, "Table 4.1: Coverage of the analysis panel by year.", width = 350f),
            run(1, 210f, 72f, "Coverage is broadly stable across the study period,", width = 400f),
            run(1, 222f, 72f, "with one exception in the middle of it.", width = 400f),
            run(1, 234f, 72f, "That exception is 2019.", width = 400f),
        )
        val prepared = cleaner.prepare(runs)
        val segments = prepared.finish(mapOf(1 to PageInk(1, List(4) { PdfRect(200f, 128f, 400f, 168f) })))

        val rows = segments.filter { it.pause == PauseLevel.TABLE_ROW }
        assertEquals(3, rows.size)
        assertEquals("Row 1: Year is 2013, Neighbourhoods is 2471.", rows.first().text)
        assertEquals("Row 3: Year is 2015, Neighbourhoods is 2482.", rows.last().text)
        assertTrue(segments.any { it.text.startsWith("Table 4.1:") })
    }

    @Test
    fun `a caption with no colon and a non-numeric label still anchors`() {
        // Cambridge house style: "Figure PII.2 A decision tree…", no colon, roman label.
        val text = cleaner.cleanToText(figurePage("Figure PII.2 A decision tree mapping institutions."))

        assertFalse(text.contains("2013 2014 2015 2016"))
        assertTrue(text.contains("Figure PII.2 A decision tree"))
    }

    @Test
    fun `a sentence that opens with a cross-reference is not a caption`() {
        // "Table 4.1 gives a notion…" is prose about the table, not the table's caption.
        // The lowercase word after the label is what separates the two.
        val runs = figurePage("Figure 4.1 shows the aggregate series for every year")
        assertTrue(cleaner.cleanToText(runs).contains("2013 2014 2015 2016"))
    }

    @Test
    fun `ink elsewhere on the page lets the caption decide`() {
        // A landscape figure rotated inside an upright page puts its text in a frame
        // the path boxes do not share, so the ink lands nowhere near the block.
        val prepared = cleaner.prepare(figurePage("Figure 4.1 Aggregate PV adoption in the study area."))
        val elsewhere = mapOf(1 to PageInk(1, List(40) { PdfRect(200f, 400f, 400f, 500f) }))

        assertFalse(cleaner.render(prepared.finish(elsewhere)).contains("2013 2014 2015 2016"))
    }

    @Test
    fun `a page drawn on past the retention cap is a figure without counting`() {
        val prepared = cleaner.prepare(figurePage("A further remark that is not a caption at all"))
        val truncated = mapOf(1 to PageInk(1, emptyList(), truncated = true))

        assertFalse(cleaner.render(prepared.finish(truncated)).contains("2013 2014 2015 2016"))
    }

    @Test
    fun `figure suppression can be turned off`() {
        val keepEverything = PdfCleaner(PdfCleanerConfig(dropFigureInternals = false))
        val text = keepEverything.cleanToText(figurePage("Figure 4.1: Aggregate PV adoption in the study area."))

        assertTrue(text.contains("2013 2014 2015 2016"))
    }

    // -- Rendering and edge cases ---------------------------------------------

    @Test
    fun `render maps pause levels onto the whitespace Segmenter reads`() {
        val rendered = cleaner.render(
            listOf(
                CleanedSegment("First paragraph.", PauseLevel.PARAGRAPH),
                CleanedSegment("Row 1: a is b.", PauseLevel.TABLE_ROW),
                CleanedSegment("Row 2: a is c.", PauseLevel.TABLE_ROW),
                CleanedSegment("Last.", PauseLevel.PARAGRAPH),
            ),
        )
        assertEquals("First paragraph.\n\nRow 1: a is b.\nRow 2: a is c.\nLast.", rendered)
    }

    @Test
    fun `an empty document yields no segments`() {
        assertTrue(cleaner.clean(emptyList()).isEmpty())
        assertEquals("", cleaner.cleanToText(emptyList()))
    }

    @Test
    fun `a document that is entirely running furniture yields no segments`() {
        val runs = (1..5).map { page -> run(page, 30f, 72f, "Running Head") }
        assertTrue(cleaner.clean(runs).isEmpty())
    }

    // -- Stage 0.5 - column reading order ---------------------------------------

    /**
     * Ten rows of two-column prose. The columns are given different line heights
     * (12pt vs 14pt) so their y-grids drift apart after the first couple of rows —
     * a real two-column page rarely keeps both columns' baselines in lockstep for
     * long, since paragraphs break at different points in each. That drift is what
     * lets column detection bootstrap itself before a single line has been split:
     * most rows are already unambiguous separate lines even under plain y-then-x
     * clustering, and only the first row or two land in the same y-band by luck.
     */
    private fun twoColumnProse(rows: Int = 10): List<PdfRun> =
        (0 until rows).map { i -> run(1, 100f + i * 12f, 72f, "Left sentence ${i + 1}.", width = 90f) } +
            (0 until rows).map { i -> run(1, 100f + i * 14f, 250f, "Right sentence ${i + 1}.", width = 90f) }

    @Test
    fun `two columns of prose are read column by column, not across the gutter`() {
        val segments = cleaner.clean(twoColumnProse())
        val texts = segments.map { it.text }
        assertEquals((1..10).map { "Left sentence $it." }, texts.filter { it.startsWith("Left") })
        assertEquals((1..10).map { "Right sentence $it." }, texts.filter { it.startsWith("Right") })
        // Every left line precedes every right line — the columns don't interleave.
        assertTrue(texts.indexOfLast { it.startsWith("Left") } < texts.indexOfFirst { it.startsWith("Right") })
    }

    @Test
    fun `a heading spanning both columns flushes what came before it and starts fresh beneath`() {
        val heading = run(1, 250f, 72f, "RESULTS", size = 10f, width = 268f) // full content width
        val runs = twoColumnProse(rows = 5) + listOf(heading) +
            (0 until 5).map { i -> run(1, 280f + i * 12f, 72f, "After-left ${i + 1}.", width = 90f) } +
            (0 until 5).map { i -> run(1, 280f + i * 14f, 250f, "After-right ${i + 1}.", width = 90f) }
        val texts = cleaner.clean(runs).map { it.text }

        val heads = texts.indexOf("RESULTS")
        assertTrue(heads > 0)
        // Everything before the heading is old-column content, everything after is new.
        assertTrue(texts.subList(0, heads).all { it.startsWith("Left") || it.startsWith("Right") })
        assertTrue(texts.subList(heads + 1, texts.size).all { it.startsWith("After") })
        assertTrue(texts.indexOfLast { it.startsWith("After-left") } < texts.indexOfFirst { it.startsWith("After-right") })
    }

    @Test
    fun `two tables side by side stay two tables instead of fusing their rows`() {
        // Bootstrap the gutter from ordinary prose above the tables, the way a real
        // page would: the tables' own rows share an identical leading grid left to
        // right, which alone gives Stage 0 nothing to detect a column split from.
        val prose = twoColumnProse()
        val leftTable = listOf(
            run(1, 300f, 72f, "Region", width = 40f),
            run(1, 300f, 130f, "Units", width = 35f),
            run(1, 312f, 72f, "North", width = 35f),
            run(1, 312f, 130f, "120", width = 20f),
            run(1, 324f, 72f, "South", width = 35f),
            run(1, 324f, 130f, "95", width = 15f),
        )
        val rightTable = listOf(
            run(1, 300f, 250f, "Country", width = 45f),
            run(1, 300f, 310f, "Score", width = 35f),
            run(1, 312f, 250f, "UK", width = 20f),
            run(1, 312f, 310f, "88", width = 15f),
            run(1, 324f, 250f, "NL", width = 20f),
            run(1, 324f, 310f, "91", width = 15f),
        )
        val texts = cleaner.clean(prose + leftTable + rightTable).map { it.text }

        assertTrue("Row 1: Region is North, Units is 120." in texts)
        assertTrue("Row 2: Region is South, Units is 95." in texts)
        assertTrue("Row 1: Country is UK, Score is 88." in texts)
        assertTrue("Row 2: Country is NL, Score is 91." in texts)
        // Neither table's rows fused with the other's into one wide row.
        assertFalse(texts.any { "Region" in it && "Country" in it })
        // The left table reads out before the right one, same as the prose above it.
        assertTrue(
            texts.indexOf("Row 2: Region is South, Units is 95.") <
                texts.indexOf("Row 1: Country is UK, Score is 88."),
        )
    }

    @Test
    fun `a single-column page is untouched by column detection`() {
        val runs = paragraph(1, 100f, 72f, "One paragraph", "of ordinary single-column prose.")
        val segments = cleaner.clean(runs)
        assertEquals(1, segments.size)
        assertEquals("One paragraph of ordinary single-column prose.", segments.first().text)
    }

    // -- Stage 2/3 heading guard --------------------------------------------------

    @Test
    fun `a paragraph flowing from one page's right column into the next page's left column is not split`() {
        // Page 1 ends in the right column, mid-sentence (no terminal punctuation).
        // Page 2 picks the sentence back up in the LEFT column — the only valid
        // transition in a two-column layout, since each page restarts left-to-right.
        // Left/right use different line heights (as in twoColumnProse) so the two
        // columns don't share a y-band and merge into one line before Stage 0.5
        // ever gets a chance to keep them apart — matching twoColumnProse's own
        // row count, since the first couple of rows still coincide before the
        // drift adds up to more than the y-band tolerance.
        val rows = 8
        val page1 = (0 until rows).map { i -> run(1, 100f + i * 12f, 72f, "L${i + 1}.", width = 90f) } +
            (0 until rows - 1).map { i -> run(1, 100f + i * 14f, 250f, "R${i + 1}.", width = 90f) } +
            listOf(run(1, 100f + (rows - 1) * 14f, 250f, "and then it", width = 90f)) // no terminal punctuation
        val page2 = listOf(run(2, 100f, 72f, "picks up here.", width = 90f)) + // continuation, left column
            (1 until rows).map { i -> run(2, 100f + i * 12f, 72f, "L${rows + 1 + i}.", width = 90f) } +
            (0 until rows).map { i -> run(2, 100f + i * 14f, 250f, "R${rows + 1 + i}.", width = 90f) }
        val texts = cleaner.clean(page1 + page2).map { it.text }
        assertTrue("and then it picks up here." in texts)
    }

    @Test
    fun `a lone superscript affiliation marker does not strand itself as a heading`() {
        // The marker is raised above the affiliation text it labels, so it lands
        // on its own line, one line above — and in a smaller face, which would
        // otherwise satisfy the "short and off body-size" heading test on its own
        // and strand it as a one-character utterance instead of letting it flow
        // into the paragraph beneath it.
        val runs = listOf(
            run(1, 100f, 72f, "a", size = 7f, width = 4f),
            run(1, 112f, 72f, "Department of Engineering,", width = 200f),
            run(1, 124f, 72f, "Example University.", width = 200f),
        )
        val text = cleaner.cleanToText(runs)
        assertEquals("a Department of Engineering, Example University.", text)
    }

    // -- Stage 0.9 - footnotes ---------------------------------------------------

    @Test
    fun `an inline footnote marker is dropped from the spoken text`() {
        val runs = listOf(
            run(1, 400f, 72f, "The effect", width = 60f),
            run(1, 400f, 132f, "1", size = 6f, width = 5f), // superscript: smaller face
            run(1, 400f, 137f, "was significant.", width = 110f),
        )
        assertEquals("The effect was significant.", cleaner.cleanToText(runs))
    }

    @Test
    fun `a normal-size number split into its own run is not mistaken for a footnote marker`() {
        // Same font size as the rest of the line — an ordinary number, not a
        // superscript, even though it happens to land in its own run.
        val runs = listOf(
            run(1, 400f, 72f, "In", width = 20f),
            run(1, 400f, 95f, "1948", width = 30f),
            run(1, 400f, 128f, "the study began.", width = 130f),
        )
        assertEquals("In 1948 the study began.", cleaner.cleanToText(runs))
    }

    @Test
    fun `a footnote block at the foot of the page is dropped`() {
        val body = (0 until 5).map { i -> run(1, 200f + i * 20f, 72f, "Body sentence $i continues here.", width = 300f) }
        val footnotes = listOf(
            run(1, 600f, 72f, "1 See Smith 2020 for the full derivation.", size = 7f, width = 300f),
            run(1, 612f, 72f, "2 The dataset excludes weekends.", size = 7f, width = 300f),
        )
        val text = cleaner.cleanToText(body + footnotes)

        assertTrue(text.contains("Body sentence 0 continues here."))
        assertTrue(text.contains("Body sentence 4 continues here."))
        assertFalse(text.contains("Smith"))
        assertFalse(text.contains("weekends"))
    }

    @Test
    fun `a same-size page number below the footnotes does not block finding them`() {
        val body = (0 until 5).map { i -> run(1, 200f + i * 20f, 72f, "Body sentence $i continues here.", width = 300f) }
        val footnote = run(1, 600f, 72f, "1 See Smith 2020 for the full derivation.", size = 7f, width = 300f)
        val pageNumber = run(1, 760f, 300f, "7", width = 10f) // normal size, sits in the footer margin band
        val text = cleaner.cleanToText(body + listOf(footnote, pageNumber))

        assertTrue(text.contains("Body sentence 0 continues here."))
        assertFalse(text.contains("Smith"))
    }

    @Test
    fun `footnote handling can be turned off`() {
        val cleaner = PdfCleaner(PdfCleanerConfig(dropFootnoteMarkers = false, dropFootnoteBlocks = false))
        val body = (0 until 5).map { i -> run(1, 200f + i * 20f, 72f, "Body sentence $i continues here.", width = 300f) }
        val footnote = run(1, 600f, 72f, "1 See Smith 2020 for the full derivation.", size = 7f, width = 300f)
        val text = cleaner.cleanToText(body + listOf(footnote))
        assertTrue(text.contains("Smith"))
    }
}
