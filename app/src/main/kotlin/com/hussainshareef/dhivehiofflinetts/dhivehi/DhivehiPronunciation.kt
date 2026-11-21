package com.hussainshareef.dhivehiofflinetts.dhivehi

import java.text.Normalizer

/**
 * Experimental Thaana-to-Latin phonetic approximation for non-Dhivehi TTS voices.
 * This is NOT a complete Dhivehi phonology or verified Eloquence phoneme mapping.
 * Keep unrecognised text unchanged so mixed-language reading is not silently lost.
 */
object DhivehiPronunciation {
    private val consonants = mapOf(
        'ހ' to "h", 'ށ' to "sh", 'ނ' to "n", 'ރ' to "r",
        'ބ' to "b", 'ޅ' to "lh", 'ކ' to "k", 'އ' to "",
        'ވ' to "v", 'މ' to "m", 'ފ' to "f", 'ދ' to "dh",
        'ތ' to "th", 'ލ' to "l", 'ގ' to "g", 'ޏ' to "gn",
        'ސ' to "s", 'ޑ' to "d", 'ޒ' to "z", 'ޓ' to "t",
        'ޔ' to "y", 'ޕ' to "p", 'ޖ' to "j", 'ޗ' to "ch",
        'ޘ' to "th", 'ޙ' to "h", 'ޚ' to "kh", 'ޛ' to "dh",
        'ޜ' to "z", 'ޝ' to "sh", 'ޞ' to "s", 'ޟ' to "dh",
        'ޠ' to "th", 'ޡ' to "z", 'ޢ' to "", 'ޣ' to "gh",
        'ޤ' to "q", 'ޥ' to "w"
    )
    private val vowels = mapOf(
        'ަ' to "a", 'ާ' to "aa", 'ި' to "i", 'ީ' to "ee",
        'ު' to "u", 'ޫ' to "oo", 'ެ' to "e", 'ޭ' to "ey",
        'ޮ' to "o", 'ޯ' to "oa"
    )
    private const val SUKUN = 'ް'

    fun containsThaana(text: String): Boolean =
        text.any { it in '\u0780'..'\u07BF' }

    fun transliterate(text: String): String {
        val input = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = StringBuilder(input.length * 2)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            val consonant = consonants[ch]
            if (consonant != null) {
                val next = input.getOrNull(i + 1)
                when {
                    next == SUKUN -> {
                        // This basic version only suppresses the vowel.
                        // Sukun on alifu has additional Dhivehi rules.
                        out.append(consonant)
                        i += 2
                    }
                    next != null && vowels.containsKey(next) -> {
                        out.append(consonant).append(vowels.getValue(next))
                        i += 2
                    }
                    else -> {
                        out.append(consonant)
                        i++
                    }
                }
            } else if (vowels.containsKey(ch)) {
                out.append(vowels.getValue(ch))
                i++
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
