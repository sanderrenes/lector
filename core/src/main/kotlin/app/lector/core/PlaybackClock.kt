package app.lector.core

/**
 * Converts a time-based skip ("jump forward 30 seconds", the shape of a hardware
 * media button on a watch or headset) into a sentence index.
 *
 * TTS has no audio timeline to seek within — there is no "30 seconds" of encoded
 * audio sitting on disk, only text still to be synthesized. What a listener wants
 * from a skip button is the same regardless: move further into (or back out of)
 * the reading by roughly that much listening time. This estimates it from word
 * count and the current speech rate rather than a fixed number of sentences, so
 * a skip means the same amount of listening time whether the sentences nearby
 * are short or long.
 */
object PlaybackClock {

    /** Average reading-aloud rate at normal (1.0×) speed, in words per minute. */
    const val BASE_WORDS_PER_MINUTE = 155f

    /** Estimated seconds to speak [text] at [speed] (1.0 = normal). */
    fun secondsFor(text: String, speed: Float): Float {
        val words = wordCount(text)
        if (words == 0) return 0f
        val wpm = BASE_WORDS_PER_MINUTE * speed.coerceIn(0.25f, 4.0f)
        return (words / wpm) * 60f
    }

    /**
     * The sentence index reached by moving [seconds] (negative = backward) from
     * [fromIndex], on a timeline built from each sentence's estimated spoken
     * duration at [speed]. Clamps to the first/last sentence rather than
     * wrapping or throwing on an out-of-range [fromIndex] or an overshoot past
     * either end of the document.
     */
    fun skip(sentences: List<Sentence>, fromIndex: Int, seconds: Float, speed: Float): Int {
        if (sentences.isEmpty()) return 0
        val from = fromIndex.coerceIn(0, sentences.lastIndex)
        if (seconds == 0f) return from

        val durations = sentences.map { secondsFor(it.text, speed) }
        var position = seconds
        for (i in 0 until from) position += durations[i] // time at the start of `from`
        if (position <= 0f) return 0

        var elapsed = 0f
        for (i in sentences.indices) {
            elapsed += durations[i]
            if (position < elapsed || i == sentences.lastIndex) return i
        }
        return sentences.lastIndex
    }

    private fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }
}
