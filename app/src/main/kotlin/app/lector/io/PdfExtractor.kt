package app.lector.io

import android.content.Context
import android.util.Log
import app.lector.core.pdf.PdfCleaner
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF text extraction via PdfBox-Android (Apache-2.0). Extracts the text layer;
 * scanned/image-only PDFs yield little or nothing (OCR is a future feature).
 *
 * Extraction is layout-aware: [PdfRunStripper] keeps the glyph geometry and
 * [PdfCleaner] uses it to drop running headers and page numbers, rejoin
 * soft-wrapped lines, repair hyphenated word breaks, and read tables by column
 * instead of as a raster of cell fragments. Plain `getText()` discards the
 * geometry all of that depends on, so it is kept only as a fallback.
 *
 * PdfBox needs a one-time resource init with a Context for its font handling.
 */
class PdfExtractor(context: Context) {

    private val cleaner = PdfCleaner()

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun extract(input: InputStream): String =
        PDDocument.load(input).use { doc ->
            val cleaned = runCatching {
                // One pass over the document yields both the text geometry and the ink.
                // The prepared document then names the handful of pages where the ink
                // can actually change the answer, and only those are handed back.
                val stripper = PdfRunStripper()
                val prepared = cleaner.prepare(stripper.runs(doc))
                cleaner.render(prepared.finish(stripper.ink(prepared.figureCandidatePages)))
            }
                .onFailure { Log.w(TAG, "Layout-aware extraction failed; using plain text", it) }
                .getOrNull()

            // A PDF whose geometry defeats the pipeline (or that trips the run cap)
            // should still be readable, so fall back rather than return nothing.
            if (!cleaned.isNullOrBlank()) cleaned
            else PDFTextStripper().apply { sortByPosition = true }.getText(doc).trim()
        }

    private companion object {
        const val TAG = "PdfExtractor"
    }
}
