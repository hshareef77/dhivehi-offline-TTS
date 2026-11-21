package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test

class DhivehiProsodyTest {
    @Test fun doesNotRewritePhonemeSyllableDots() {
        val input = "`[.1di.0vE.0hi] `[.1maa.0lE]"
        assertEquals(input, DhivehiProsody.render(input, DhivehiProsody.Style.CONNECTED))
    }
    @Test fun addsPauseAtSentenceAndComma() {
        val input = "`[.1haa], `[.1baa]."
        val connected = DhivehiProsody.render(input, DhivehiProsody.Style.CONNECTED)
        assertTrue(connected.contains("`p85"))
        assertTrue(connected.contains("`p160"))
        val clear = DhivehiProsody.render(input, DhivehiProsody.Style.CLEAR)
        assertTrue(clear.contains("`p165"))
        assertTrue(clear.contains("`p280"))
        assertFalse(connected.contains(","))
    }
    @Test fun preservesMixedEnglishAndUnmodifiedSymbols() {
        assertEquals("Hello `[.1haa] 123",
            DhivehiProsody.render("Hello `[.1haa] 123", DhivehiProsody.Style.CONNECTED))
    }
    @Test fun doesNotDuplicateSentenceMarkers() {
        val converted = DhivehiProsody.render("`[.1haa]!!!", DhivehiProsody.Style.CONNECTED)
        assertEquals(1, Regex("`p160").findAll(converted).count())
    }
}
