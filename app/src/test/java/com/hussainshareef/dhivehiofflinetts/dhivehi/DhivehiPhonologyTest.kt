package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test

class DhivehiPhonologyTest {
    @Test fun vowelsRetainPhonemicQuantity() {
        assertEquals("ha", DhivehiPhonology.analyzeWord("ހަ").ipa)
        assertEquals("haː", DhivehiPhonology.analyzeWord("ހާ").ipa)
        assertEquals("iː", DhivehiPhonology.analyzeWord("އީ").ipa)
    }
    @Test fun dentalRetroflexDistinctionSurvivesBeforeVoiceMapping() {
        assertEquals("t̪a", DhivehiPhonology.analyzeWord("ތަ").ipa)
        assertEquals("ʈa", DhivehiPhonology.analyzeWord("ޓަ").ipa)
        assertEquals("d̪a", DhivehiPhonology.analyzeWord("ދަ").ipa)
        assertEquals("ɖa", DhivehiPhonology.analyzeWord("ޑަ").ipa)
    }
    @Test fun alifuGeminationCreatesPhonemicConsonantLength() {
        assertEquals("raːdʒdʒe", DhivehiPhonology.analyzeWord("ރާއްޖެ").ipa)
    }
    @Test fun alifuCarrierIsNotAConsonant() {
        assertEquals("a", DhivehiPhonology.analyzeWord("އަ").ipa)
    }
    @Test fun finalAlifuSukunIsFlaggedForReview() {
        val word = DhivehiPhonology.analyzeWord("ރައް")
        assertTrue(word.ipa.contains("ʔ"))
        assertTrue(word.warnings.isNotEmpty())
    }
    @Test fun sprAndIpaAvailableSeparately() {
        val result = DhivehiPhoneticEngine.analyze("ދިވެހި")
        assertEquals("d̪iʋehi", result.ipaPreview)
        assertEquals("`[.1di.0vE.0hi]", result.annotated)
    }
}
