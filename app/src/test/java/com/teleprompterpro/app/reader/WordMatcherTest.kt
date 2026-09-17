package com.teleprompterpro.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMatcherTest {

    private val script = "Hello everyone, welcome back to the channel. Today we are talking about cameras and lenses."

    @Test
    fun `tokenizer strips punctuation and lowercases`() {
        assertEquals(listOf("hello", "everyone", "welcome"), WordMatcher.tokenize("Hello, everyone! Welcome"))
        assertEquals(listOf("dont"), WordMatcher.tokenize("don't"))
    }

    @Test
    fun `tokenizer handles urdu`() {
        val urdu = "آج ہم کیمرے کے بارے میں بات کریں گے۔"
        val t = WordMatcher.tokenize(urdu)
        assertEquals(9, t.size)
        assertEquals("آج", t.first())
    }

    @Test
    fun `cursor advances only on matching words`() {
        val m = WordMatcher(script)
        assertTrue(m.feed("hello everyone"))
        assertEquals(2, m.cursor)
        assertTrue(m.feed("hello everyone welcome back"))
        assertEquals(4, m.cursor)
    }

    @Test
    fun `noise or another language does not scroll`() {
        val m = WordMatcher(script)
        assertFalse(m.feed("hmm uh la la la"))
        assertFalse(m.feed("bonjour tout le monde"))
        assertEquals(0, m.cursor)
    }

    @Test
    fun `skipped words are tolerated within window`() {
        val m = WordMatcher(script)
        assertTrue(m.feed("talking about cameras"))
        assertEquals(13, m.cursor) // talking(10) about(11) cameras(12) -> next = 13
    }

    @Test
    fun `cursor never moves backwards`() {
        val m = WordMatcher(script)
        m.feed("today we are")
        val c = m.cursor
        assertFalse(m.feed("hello everyone"))
        assertEquals(c, m.cursor)
    }

    @Test
    fun `fuzzy match tolerates one-letter recognizer slips`() {
        val m = WordMatcher(script)
        assertTrue(m.feed("welcom back"))
        assertEquals(4, m.cursor)
    }

    @Test
    fun `common word far ahead does not jump beyond window`() {
        val long = (1..200).joinToString(" ") { "word$it" } + " the end"
        val m = WordMatcher(long)
        assertFalse(m.feed("the", window = 40))
        assertEquals(0, m.cursor)
    }
}
