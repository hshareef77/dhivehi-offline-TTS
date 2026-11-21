package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test

/** Regression coverage for the new *direct phoneme* path, not Latin spelling. */
class DhivehiPhoneticEngineTest {
    @Test fun divehiUsesEnginePhonemes() {
        val result = DhivehiPhoneticEngine.analyze("ދިވެހި")
        assertEquals("`[.1Di.0vE.0hi]", result.annotated)
        assertEquals(1, result.words)
        assertTrue(result.unresolved.isEmpty())
    }

    @Test fun longVowelHasDifferentRepresentation() {
        assertEquals("`[.1ha]", DhivehiPhoneticEngine.analyze("ހަ").annotated)
        assertEquals("`[.1haa]", DhivehiPhoneticEngine.analyze("ހާ").annotated)
        assertNotEquals(
            DhivehiPhoneticEngine.analyze("ހި").annotated,
            DhivehiPhoneticEngine.analyze("ހީ").annotated
        )
    }

    @Test fun alifuSukunCreatesExperimentalGemination() {
        assertEquals("`[.1raa.0JJE]", DhivehiPhoneticEngine.analyze("ރާއްޖެ").annotated)
    }

    @Test fun sourceEnglishDoesNotGetPhonemeMarkup() {
        assertEquals("Hello 123", DhivehiPhoneticEngine.analyze("Hello 123").annotated)
        assertEquals("English `[.1haa]!", DhivehiPhoneticEngine.analyze("English ހާ!").annotated)
    }

    @Test fun doesNotAllowUntrustedBacktickCommands() {
        val phrase = "ހާ `vs999"
        val response = DhivehiPhoneticEngine.analyze(phrase).annotated
        assertFalse(response.contains("`vs999"))
    }

    @Test fun phonemeWordOverridesAreStrictlyFiltered() {
        val base = "ދިވެހި"
        assertEquals("`[.1Da.0mi]",
            DhivehiPhoneticEngine.analyze(base, mapOf(base to "SPR:.1Da.0mi")).annotated)
        assertEquals("`[.1Di.0vE.0hi]",
            DhivehiPhoneticEngine.analyze(base, mapOf(base to "SPR:`vs300")).annotated)
    }

    @Test fun retroflexAndArabicLettersProduceWarnings() {
        val response = DhivehiPhoneticEngine.analyze("ޅާ")
        assertTrue(response.approximate.contains('ޅ'))
    }
}
