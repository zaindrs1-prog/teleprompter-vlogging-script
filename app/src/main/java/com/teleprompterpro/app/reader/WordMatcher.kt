package com.teleprompterpro.app.reader

/**
 * Aligns recognized speech against the script by *words*, not volume.
 *
 * The script is tokenized once into normalized words (Unicode letters/digits
 * only, lower-cased, so English and Urdu both work). Each recognizer
 * hypothesis is matched inside a look-ahead window starting at the current
 * cursor; the cursor only moves when real words match, and never backwards
 * by more than a small tolerance. Speaking another language or making noise
 * produces no matches and therefore no scrolling.
 */
class WordMatcher(scriptText: String) {

    /** Normalized script tokens, in reading order. */
    val tokens: List<String> = tokenize(scriptText)

    /** Index of the next word expected to be spoken. */
    var cursor: Int = 0
        private set

    val total: Int get() = tokens.size
    val done: Boolean get() = cursor >= tokens.size

    fun reset(toWord: Int = 0) {
        cursor = toWord.coerceIn(0, tokens.size)
    }

    /**
     * Feed a (partial or final) hypothesis. Returns true if the cursor moved.
     * [window] caps how far ahead we search so a common word ("the") deep in
     * the script can't yank the cursor forward.
     */
    fun feed(hypothesis: String, window: Int = 40): Boolean {
        val spoken = tokenize(hypothesis)
        if (spoken.isEmpty() || tokens.isEmpty() || done) return false

        val start = cursor
        val end = (cursor + window).coerceAtMost(tokens.size)
        // Try to anchor on the longest trailing n-gram of what was spoken.
        val maxN = minOf(3, spoken.size)
        for (n in maxN downTo 1) {
            val tail = spoken.subList(spoken.size - n, spoken.size)
            val hit = findSequence(tail, start, end)
            if (hit >= 0) {
                val newCursor = hit + n
                if (newCursor > cursor) {
                    cursor = newCursor
                    return true
                }
                return false
            }
        }
        return false
    }

    /** First index in [from, to) where [seq] matches token-by-token (fuzzy per word). */
    private fun findSequence(seq: List<String>, from: Int, to: Int): Int {
        var best = -1
        var i = from
        while (i + seq.size <= to) {
            var ok = true
            for (k in seq.indices) {
                if (!similar(tokens[i + k], seq[k])) { ok = false; break }
            }
            if (ok) { best = i; break }
            i++
        }
        return best
    }

    companion object {
        fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in text) {
                if (ch.isLetterOrDigit() || ch == '\'' || ch == '’') {
                    if (ch != '\'' && ch != '’') sb.append(ch.lowercaseChar())
                } else if (sb.isNotEmpty()) {
                    out.add(sb.toString()); sb.setLength(0)
                }
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }

        /** Exact match, or edit distance ≤1 for words of 5+ chars (recognizer slips). */
        fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length < 5 || b.length < 5) return false
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            return editDistanceAtMostOne(a, b)
        }

        private fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (a.length == b.length) {
                var diff = 0
                for (i in a.indices) if (a[i] != b[i] && ++diff > 1) return false
                return true
            }
            val (short, long) = if (a.length < b.length) a to b else b to a
            var i = 0; var j = 0; var skipped = false
            while (i < short.length && j < long.length) {
                if (short[i] == long[j]) { i++; j++ } else {
                    if (skipped) return false
                    skipped = true; j++
                }
            }
            return true
        }
    }
}
