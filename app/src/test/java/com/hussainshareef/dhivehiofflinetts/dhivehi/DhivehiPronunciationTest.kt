package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test

class DhivehiPronunciationTest {
    @Test fun detectsThaana() {
        assertTrue(DhivehiPronunciation.containsThaana("Hello ދިވެހި"))
        assertFalse(DhivehiPronunciation.containsThaana("Hello 123"))
    }

    @Test fun basicVowels() {
        assertEquals("haa", DhivehiPronunciation.transliterate("ހާ"))
        assertEquals("hee", DhivehiPronunciation.transliterate("ހީ"))
        assertEquals("hoo", DhivehiPronunciation.transliterate("ހޫ"))
        assertEquals("hey", DhivehiPronunciation.transliterate("ހޭ"))
    }

    @Test fun spacesAndPunctuationSurvive() {
        assertEquals("haa, bee! Hi", DhivehiPronunciation.transliterate("ހާ, ބީ! Hi"))
    }

    @Test fun sukunDoesNotBecomeText() {
        assertEquals("han", DhivehiPronunciation.transliterate("ހަން"))
    }

    @Test fun explicitCorrectionOverridesOnlyTargetWord() {
        assertEquals("hello bee hello", DhivehiPronunciation.transliterate(
            "ހާ ބީ ހާ",
            mapOf("ހާ" to "hello")
        ))
    }

    @Test fun emptyAndNonThaanaSafe() {
        assertEquals("", DhivehiPronunciation.transliterate(""))
        assertEquals("ABC 42", DhivehiPronunciation.transliterate("ABC 42"))
    }

    @Test fun punctuationSeparatedCorrection() {
        assertEquals("Hello!", DhivehiPronunciation.transliterate("ހާ!", mapOf("ހާ" to "Hello")))
    }

    @Test fun commonSampleTransliterations() {
        assertEquals("dhivehi", DhivehiPronunciation.transliterate("ދިވެހި"))
        assertEquals("maale", DhivehiPronunciation.transliterate("މާލެ"))
        assertEquals("raajje", DhivehiPronunciation.transliterate("ރާއްޖެ"))
        assertEquals("assalaamu alaikum",
            DhivehiPronunciation.transliterate("އައްސަލާމު ޢަލައިކުމް"))
    }

    @Test fun overrideDoesNotChangeOtherWords() {
        assertEquals("custom maale",
            DhivehiPronunciation.transliterate("ދިވެހި މާލެ", mapOf("ދިވެހި" to "custom")))
    }

    @Test fun samplePhraseIsDeterministic() {
        val sample = "އައްސަލާމު ޢަލައިކުމް"
        val first = DhivehiPronunciation.transliterate(sample)
        assertNotEquals(sample, first)
        assertEquals(first, DhivehiPronunciation.transliterate(sample))
    }
}
