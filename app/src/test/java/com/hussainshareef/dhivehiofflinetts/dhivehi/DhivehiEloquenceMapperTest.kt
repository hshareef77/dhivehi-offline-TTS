package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test

class DhivehiEloquenceMapperTest {
    @Test fun phonemePipelineNotEnglishOrthography() {
        val word = DhivehiPhonology.analyzeWord("ދިވެހި")
        assertEquals("d̪iʋehi", word.ipa)
        assertEquals(".1di.0vE.0hi", DhivehiEloquenceMapper.map(word).spr)
    }
    @Test fun longVowelIsMarkedAsApproximation() {
        val short = DhivehiEloquenceMapper.map(DhivehiPhonology.analyzeWord("މަ"))
        val long = DhivehiEloquenceMapper.map(DhivehiPhonology.analyzeWord("މާ"))
        assertEquals(".1ma", short.spr)
        assertEquals(".1maa", long.spr)
        assertTrue(long.approximate.contains("aː"))
    }
    @Test fun geminateIsPreservedInNativeMapping() {
        val word = DhivehiPhonology.analyzeWord("ރާއްޖެ")
        assertEquals(".1raa.0JJE", DhivehiEloquenceMapper.map(word).spr)
    }
    @Test fun prenasalStopIsNotSilentlyErased() {
        val word = DhivehiPhonology.analyzeWord("ކަނޑު")
        assertEquals(".1ka.0ndu", DhivehiEloquenceMapper.map(word).spr)
        assertTrue(DhivehiEloquenceMapper.map(word).approximate.contains("ⁿ"))
    }
    @Test fun leftmostHeavySyllableReceivesStress() {
        val raw = DhivehiPhonology.analyzeWord("އަލާ")
        assertEquals(".0a.1laa", DhivehiEloquenceMapper.map(raw).spr)
        val withGeminate = DhivehiPhonology.analyzeWord("އައްސަލާމު")
        assertEquals(".1a.0ssa.0laa.0mu", DhivehiEloquenceMapper.map(withGeminate).spr)
    }

    @Test fun glottalStopNeedsAcousticWork() {
        val word = DhivehiPhonology.analyzeWord("ރަށް")
        val mapped = DhivehiEloquenceMapper.map(word)
        assertEquals(".1ra", mapped.spr)
        assertTrue(mapped.approximate.contains("ʔ"))
    }
}
