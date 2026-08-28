package app.lector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {

    private fun sentence(index: Int, text: String) = Sentence(index, text, 0, text.length)

    // -- secondsFor --------------------------------------------------------------

    @Test
    fun `a sentence at the base rate takes word count over words-per-minute`() {
        // 31 words at 155 wpm (1.0x) is exactly 12 seconds.
        val text = (1..31).joinToString(" ") { "word" }
        assertEquals(12f, PlaybackClock.secondsFor(text, speed = 1.0f), 0.01f)
    }

    @Test
    fun `double speed takes half the time`() {
        val text = (1..31).joinToString(" ") { "word" }
        assertEquals(6f, PlaybackClock.secondsFor(text, speed = 2.0f), 0.01f)
    }

    @Test
    fun `empty text takes no time`() {
        assertEquals(0f, PlaybackClock.secondsFor("   ", speed = 1.0f), 0.0f)
    }

    // -- skip ----------------------------------------------------------------------

    @Test
    fun `skipping forward past several short sentences lands on the one that absorbs the remainder`() {
        // Each sentence is ~31 words = 12s at 1.0x. Skipping 30s from sentence 0
        // should consume sentence 0 (12s) and sentence 1 (12s), landing on 2 with
        // 6s left over — i.e. sentence index 2.
        val sentences = (0 until 5).map { i -> sentence(i, (1..31).joinToString(" ") { "word" }) }
        assertEquals(2, PlaybackClock.skip(sentences, fromIndex = 0, seconds = 30f, speed = 1.0f))
    }

    @Test
    fun `skipping backward walks back through the timeline the same way forward does`() {
        // Sentence i's timeline slot is [12i, 12i+12). fromIndex 4 starts at t=48;
        // moving back 30s lands at t=18, which is inside sentence 1's [12, 24).
        val sentences = (0 until 5).map { i -> sentence(i, (1..31).joinToString(" ") { "word" }) }
        assertEquals(1, PlaybackClock.skip(sentences, fromIndex = 4, seconds = -30f, speed = 1.0f))
    }

    @Test
    fun `skipping forward past the end of the document clamps to the last sentence`() {
        val sentences = (0 until 3).map { i -> sentence(i, "short sentence") }
        assertEquals(2, PlaybackClock.skip(sentences, fromIndex = 0, seconds = 300f, speed = 1.0f))
    }

    @Test
    fun `skipping backward past the start clamps to the first sentence`() {
        val sentences = (0 until 3).map { i -> sentence(i, "short sentence") }
        assertEquals(0, PlaybackClock.skip(sentences, fromIndex = 2, seconds = -300f, speed = 1.0f))
    }

    @Test
    fun `a faster speed covers more sentences in the same skip`() {
        val sentences = (0 until 5).map { i -> sentence(i, (1..31).joinToString(" ") { "word" }) }
        val at1x = PlaybackClock.skip(sentences, fromIndex = 0, seconds = 30f, speed = 1.0f)
        val at2x = PlaybackClock.skip(sentences, fromIndex = 0, seconds = 30f, speed = 2.0f)
        assertTrue(at2x > at1x)
    }

    @Test
    fun `zero seconds is a no-op`() {
        val sentences = (0 until 5).map { i -> sentence(i, "some words here") }
        assertEquals(3, PlaybackClock.skip(sentences, fromIndex = 3, seconds = 0f, speed = 1.0f))
    }

    @Test
    fun `an out-of-range starting index is clamped before skipping`() {
        val sentences = (0 until 3).map { i -> sentence(i, "short sentence") }
        assertEquals(2, PlaybackClock.skip(sentences, fromIndex = 99, seconds = 0f, speed = 1.0f))
    }

    @Test
    fun `an empty document has nothing to skip to`() {
        assertEquals(0, PlaybackClock.skip(emptyList(), fromIndex = 0, seconds = 30f, speed = 1.0f))
    }
}
