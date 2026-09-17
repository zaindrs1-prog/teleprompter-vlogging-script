package com.teleprompterpro.app.script

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptTest {
    @Test
    fun `word count ignores whitespace runs and line breaks`() {
        assertEquals(0, Script.countWords(""))
        assertEquals(0, Script.countWords("   \n\n  "))
        assertEquals(5, Script.countWords("one two\n\nthree   four\tfive"))
    }

    @Test
    fun `very long scripts are simply counted, never truncated`() {
        val text = (1..50_000).joinToString(" ") { "w$it" }
        assertEquals(50_000, Script.countWords(text))
        assertEquals(text, Script(1, "t", text, 0, 0).body)
    }
}
