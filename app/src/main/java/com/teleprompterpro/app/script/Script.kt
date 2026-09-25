package com.teleprompterpro.app.script

import com.teleprompterpro.app.settings.ScrollMode

/**
 * A saved teleprompter script plus its per-script reader settings.
 *
 * [body] is stored byte-for-byte as typed/imported: no trimming, no
 * whitespace collapsing, no length limit.
 */
data class Script(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val settings: ReaderSettings = ReaderSettings(),
) {
    val wordCount: Int get() = countWords(body)

    companion object {
        fun countWords(text: String): Int {
            var count = 0
            var inWord = false
            for (ch in text) {
                if (ch.isWhitespace()) {
                    inWord = false
                } else if (!inWord) {
                    inWord = true
                    count++
                }
            }
            return count
        }
    }
}

/** Per-script reader preferences (persisted with the script). */
data class ReaderSettings(
    val fontSp: Float = 30f,
    /** Reading pace for timed mode. Duration = words / wpm. */
    val wpm: Int = 140,
    /** Opacity of the dark box behind the text, 0 = transparent, 1 = solid. */
    val backgroundOpacity: Float = 0.55f,
    val scrollMode: ScrollMode = ScrollMode.TIMED,
    val mirrorText: Boolean = false,
)
