package com.teleprompterpro.app.reader

/**
 * Pure arithmetic for timed scrolling and the ETA display. Everything the UI
 * shows is derived from three numbers — word count, WPM and progress — so the
 * estimate can never drift or "reset to nonsense".
 */
object ScrollMath {

    const val MIN_WPM = 40
    const val MAX_WPM = 400

    /** Total read time for [words] at [wpm], in milliseconds. */
    fun totalDurationMs(words: Int, wpm: Int): Long {
        if (words <= 0) return 0L
        val safeWpm = wpm.coerceIn(MIN_WPM, MAX_WPM)
        return (words.toDouble() / safeWpm * 60_000.0).toLong()
    }

    /** WPM that reads [words] in exactly [durationMs]. */
    fun wpmForDuration(words: Int, durationMs: Long): Int {
        if (words <= 0 || durationMs <= 0) return MIN_WPM
        return (words.toDouble() / (durationMs / 60_000.0)).toInt().coerceIn(MIN_WPM, MAX_WPM)
    }

    /** Remaining time from [progress] (0..1) at the current pace. */
    fun remainingMs(words: Int, wpm: Int, progress: Float): Long {
        val total = totalDurationMs(words, wpm)
        val p = progress.coerceIn(0f, 1f)
        return (total * (1.0 - p)).toLong()
    }

    /** Elapsed time implied by [progress]. */
    fun elapsedMs(words: Int, wpm: Int, progress: Float): Long =
        totalDurationMs(words, wpm) - remainingMs(words, wpm, progress)

    /** New progress after [deltaMs] of playback at [wpm]. */
    fun advance(progress: Float, deltaMs: Long, words: Int, wpm: Int): Float {
        val total = totalDurationMs(words, wpm)
        if (total <= 0L) return 1f
        return (progress + deltaMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Progress for a given word index in a script with [words] words. */
    fun progressForWord(wordIndex: Int, words: Int): Float {
        if (words <= 0) return 0f
        return (wordIndex.toFloat() / words.toFloat()).coerceIn(0f, 1f)
    }

    /** mm:ss (or h:mm:ss) for on-screen ETA. */
    fun clock(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 500) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}
