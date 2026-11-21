package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test

class SpeechChunksTest {
    @Test fun longTextKeepsAllCharacters() {
        val phrase = "ދިވެހި English. ".repeat(180)
        val chunks = SpeechChunks.split(phrase, 120)
        assertTrue(chunks.size > 1)
        assertEquals(phrase, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 120 })
    }
    @Test fun blankAndShort() {
        assertEquals(emptyList<String>(), SpeechChunks.split(""))
        assertEquals(listOf("hello"), SpeechChunks.split("hello"))
    }
    @Test fun neverCutsSurrogatePairs() {
        val text = ("test🌴".repeat(80))
        val chunks = SpeechChunks.split(text, 17)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 17 })
        assertTrue(chunks.all { !it.last().isHighSurrogate() })
    }
}
