package com.hussainshareef.dhivehiofflinetts.dhivehi

import java.text.Normalizer

/**
 * Experimental THAANA -> PHONEMES -> OpenEVV SPR.
 *
 * Unlike Latin transcription, the synthesizer receives explicit phoneme
 * annotations (`[.1da.0mi]) in OpenEVV's documented alphabet. The symbol
 * inventory was checked on the host OpenEVV engine. Dhivehi-specific vowel
 * quantity and retroflexion are not accurately established yet.
 *
 * IMPORTANT: this is deliberately opt-in. Phoneme annotations are an
 * engine-specific language; never feed them to Android's generic TTS API.
 */
object DhivehiPhoneticEngine {
    data class Analysis(
        val annotated: String,
        val unresolved: Set<Char>,
        val approximate: Set<Char>,
        val words: Int
    )

    private val consonants = mapOf(
        'ހ' to "h", 'ށ' to "S", 'ނ' to "n", 'ރ' to "r",
        'ބ' to "b", 'ޅ' to "l", 'ކ' to "k", 'އ' to "",
        'ވ' to "v", 'މ' to "m", 'ފ' to "f", 'ދ' to "d",
        'ތ' to "t", 'ލ' to "l", 'ގ' to "g", 'ޏ' to "ny",
        'ސ' to "s", 'ޑ' to "d", 'ޒ' to "z", 'ޓ' to "t",
        'ޔ' to "y", 'ޕ' to "p", 'ޖ' to "J", 'ޗ' to "C",
        'ޘ' to "T", 'ޙ' to "h", 'ޚ' to "k",
        'ޛ' to "D", 'ޜ' to "Z", 'ޝ' to "S", 'ޞ' to "s",
        'ޟ' to "D", 'ޠ' to "T", 'ޡ' to "z", 'ޢ' to "",
        'ޣ' to "g", 'ޤ' to "k", 'ޥ' to "w",
        'ޱ' to "n"
    )
    private val vowels = mapOf(
        'ަ' to "a", 'ާ' to "aa", 'ި' to "i", 'ީ' to "ii",
        'ު' to "u", 'ޫ' to "uu", 'ެ' to "E", 'ޭ' to "ee",
        'ޮ' to "o", 'ޯ' to "oo"
    )
    // English 'th' is a fricative and would be WRONG for Dhivehi dental
    // stops ތ /t̪/ and ދ /d̪/. We use the nearest STOP 't'/'d' and warn.
    // English voice phonemes do not maintain the dental/retroflex contrasts.
    private val approximateLetters = setOf(
        'ށ', 'ޅ', 'ޏ', 'ދ', 'ތ', 'ޑ', 'ޓ', 'ޘ', 'ޙ', 'ޚ', 'ޛ', 'ޞ',
        'ޟ', 'ޠ', 'ޡ', 'ޢ', 'ޣ', 'ޤ', 'ޱ'
    )
    private const val SUKUN = 'ް'
    private fun isThaana(c: Char) = c in '\u0780'..'\u07BF'
    private val safeSpr = Regex("^[.0123456789a-zA-Z]+$")
    private val safeText = Regex("[\\r\\n\\t]")
    private val punctuation = setOf('.', ',', ';', ':', '?', '!', '؟', '،')

    /**
     * Words are processed as phonemes, not Latin English spellings.
     *
     * corrections maps Thaana words to either:
     * - a safe explicit SPR sequence prefixed "SPR:" (experienced testers);
     * - ordinary Latin text, left for the engine's English rules;
     *   this is a fallback and not phonemic processing.
     */
    fun analyze(text: String, corrections: Map<String, String> = emptyMap()): Analysis {
        val source = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = StringBuilder(source.length * 3)
        val unsupported = linkedSetOf<Char>()
        val approximate = linkedSetOf<Char>()
        var count = 0
        var i = 0
        while (i < source.length) {
            if (isThaana(source[i])) {
                val begin = i
                while (i < source.length && isThaana(source[i])) i++
                val word = source.substring(begin, i)
                count++
                val custom = corrections[word]?.trim()?.takeIf { it.length <= 200 }
                if (custom?.startsWith("SPR:", ignoreCase = true) == true) {
                    val code = custom.substring(4).trim()
                    if (safeSpr.matches(code) && code.length < 180 && code.contains('.')) {
                        out.append("`[").append(code).append(']')
                        continue
                    }
                }
                if (custom != null && !custom.startsWith("SPR:", true) &&
                    custom.isNotBlank()) {
                    // Explicit Latin correction has separate semantics.
                    out.append(custom.replace(safeText, " "))
                } else {
                    out.append("`[").append(wordToSpr(word, unsupported, approximate)).append(']')
                }
            } else {
                val ch = source[i++]
                // A literal backtick in engine annotation mode is dangerous.
                // Deliberately omit unsafe backtick rather than interpret it.
                out.append(if (ch == '`') '\'' else ch)
            }
        }
        return Analysis(out.toString(), unsupported, approximate, count)
    }

    private fun wordToSpr(
        word: String,
        unsupported: MutableSet<Char>,
        approximate: MutableSet<Char>
    ): String {
        val out = StringBuilder(word.length * 3)
        var i = 0
        var firstVowel = true
        var onset = StringBuilder()
        fun emitVowel(v: String) {
            out.append(if (firstVowel) ".1" else ".0")
            out.append(onset)
            out.append(v)
            onset = StringBuilder()
            firstVowel = false
        }
        while (i < word.length) {
            val ch = word[i]
            val sound = consonants[ch]
            if (sound != null) {
                if (ch in approximateLetters) approximate.add(ch)
                val mark = word.getOrNull(i + 1)
                if (mark == SUKUN) {
                    val next = word.getOrNull(i + 2)
                    val after = next?.let { consonants[it] }
                    if ((ch == 'އ' || ch == 'ށ' || ch == 'ނ') && !after.isNullOrEmpty()) {
                        // Gemination represented by a repeated onset segment;
                        // actual consonant timing needs audio verification.
                        onset.append(after)
                    } else if (ch != 'އ') {
                        onset.append(sound)
                    } else {
                        // Word-final glottal closure: English voice lacks a
                        // verified glottal-stop symbol; retain diagnostic.
                        approximate.add(ch)
                    }
                    i += 2
                } else {
                    onset.append(sound)
                    i++
                }
            } else if (ch in vowels) {
                emitVowel(vowels.getValue(ch))
                i++
            } else if (ch == SUKUN) {
                i++
            } else {
                unsupported.add(ch)
                i++
            }
        }
        if (onset.isNotEmpty()) {
            if (firstVowel) out.append(".1")
            out.append(onset)
        }
        if (out.isEmpty()) out.append(".1a")
        return out.toString()
    }
}
