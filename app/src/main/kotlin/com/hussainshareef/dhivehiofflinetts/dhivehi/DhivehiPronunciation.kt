package com.hussainshareef.dhivehiofflinetts.dhivehi

import java.text.Normalizer
import java.util.Locale

/**
 * Offline, deterministic experimental Thaana pronunciation frontend.
 *
 * NOT a phonemic inventory for Dhivehi and NOT a validated Eloquence phoneme mapping.
 * Replacements are intended to be spoken by an English-compatible TTS engine.
 * Immutable tables and bounded processing make this usable from rapid TTS callbacks.
 */
object DhivehiPronunciation {
    private val letters = mapOf(
        'ހ' to "h", 'ށ' to "sh", 'ނ' to "n", 'ރ' to "r", 'ބ' to "b",
        'ޅ' to "lh", 'ކ' to "k", 'އ' to "", 'ވ' to "v", 'މ' to "m",
        'ފ' to "f", 'ދ' to "dh", 'ތ' to "th", 'ލ' to "l", 'ގ' to "g",
        'ޏ' to "ny", 'ސ' to "s", 'ޑ' to "d", 'ޒ' to "z", 'ޓ' to "t",
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
    private val wordNumber = mapOf(
        "0" to "zero", "1" to "one", "2" to "two", "3" to "three",
        "4" to "four", "5" to "five", "6" to "six", "7" to "seven",
        "8" to "eight", "9" to "nine"
    )
    private const val SUKUN = 'ް'
    private fun isThaana(ch: Char) = ch in '\u0780'..'\u07BF'
    fun containsThaana(text: String) = text.any(::isThaana)

    /**
     * Exact, user-reviewed overrides. A caller supplies only explicitly entered
     * corrections, keyed by the full Thaana token. No hidden guesses.
     */
    fun transliterate(text: String, overrides: Map<String, String> = emptyMap()): String {
        if (text.isEmpty()) return text
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = StringBuilder(normalized.length * 2)
        var i = 0
        while (i < normalized.length) {
            if (isThaana(normalized[i])) {
                val begin = i
                while (i < normalized.length && isThaana(normalized[i])) i++
                val word = normalized.substring(begin, i)
                val explicit = overrides[word]?.takeIf { it.isNotBlank() && it.length <= 200 }
                out.append(explicit ?: convertWord(word))
            } else {
                // Preserve punctuation, whitespace and English in mixed-script text.
                out.append(normalized[i])
                i++
            }
        }
        return out.toString()
    }

    fun phoneticForWord(word: String): String = convertWord(Normalizer.normalize(word, Normalizer.Form.NFC))

    private fun convertWord(word: String): String {
        val result = StringBuilder(word.length * 2)
        var i = 0
        while (i < word.length) {
            val ch = word[i]
            val sound = letters[ch]
            if (sound != null) {
                val mark = word.getOrNull(i + 1)
                when {
                    mark == SUKUN -> {
                        // In many common Thaana spellings, alifu+sukun before a
                        // consonant marks gemination of that following consonant.
                        // This is an approximation: glottal and dialect-specific
                        // contexts still require reviewed pronunciation rules.
                        if (ch == 'އ' && i + 2 < word.length) {
                            val following = letters[word[i + 2]]
                            if (following != null && following.isNotEmpty()) {
                                result.append(following)
                            }
                        } else {
                            result.append(sound)
                        }
                        i += 2
                    }
                    mark != null && vowels.containsKey(mark) -> {
                        result.append(sound).append(vowels.getValue(mark))
                        i += 2
                    }
                    else -> {
                        result.append(sound)
                        i++
                    }
                }
            } else if (vowels.containsKey(ch)) {
                result.append(vowels.getValue(ch))
                i++
            } else {
                result.append(ch)
                i++
            }
        }
        return result.toString()
    }

    /**
     * Optional readable preview of digits (NOT a grammatical Dhivehi number reader).
     * Remains opt-in: replacing actual dates/prices with English digits would be wrong.
     */
    fun spellDigitsEnglish(text: String): String =
        text.map { c -> wordNumber[c.toString()] ?: c.toString() }
            .joinToString(" ").replace(Regex(" +"), " ").trim()
}
