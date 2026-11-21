package com.hussainshareef.dhivehiofflinetts.dhivehi

import java.text.Normalizer

/**
 * Dhivehi/Thaana *linguistic* phoneme inventory, independent of Eloquence.
 *
 * The IPA column represents intended Dhivehi contrasts rather than English
 * spellings. Actual sound realizations, dialects and glottal/sukun sandhi
 * need speaker review. This stage is never passed verbatim to the synthesizer.
 */
object DhivehiPhonology {
    data class Phone(val ipa: String, val long: Boolean = false, val source: String)
    data class Word(val phones: List<Phone>, val warnings: List<String>) {
        val ipa: String get() = phones.joinToString("") { it.ipa }
    }

    val consonantIPA: Map<Char, String> = mapOf(
        'ހ' to "h", 'ށ' to "ʂ", 'ނ' to "n", 'ރ' to "r",
        'ބ' to "b", 'ޅ' to "ɭ", 'ކ' to "k", 'އ' to "",
        'ވ' to "ʋ", 'މ' to "m", 'ފ' to "f", 'ދ' to "d̪",
        'ތ' to "t̪", 'ލ' to "l", 'ގ' to "ɡ", 'ޏ' to "ɲ",
        'ސ' to "s", 'ޑ' to "ɖ", 'ޒ' to "z", 'ޓ' to "ʈ",
        'ޔ' to "j", 'ޕ' to "p", 'ޖ' to "dʒ", 'ޗ' to "tʃ",
        'ޘ' to "θ", 'ޙ' to "ħ", 'ޚ' to "x", 'ޛ' to "ð",
        'ޜ' to "ʒ", 'ޝ' to "ʃ", 'ޞ' to "sˤ", 'ޟ' to "dˤ",
        'ޠ' to "tˤ", 'ޡ' to "zˤ", 'ޢ' to "ʕ", 'ޣ' to "ɣ",
        'ޤ' to "q", 'ޥ' to "w", 'ޱ' to "ɳ"
    )
    private val vowelIPA = mapOf(
        'ަ' to "a", 'ާ' to "aː",
        'ި' to "i", 'ީ' to "iː",
        'ު' to "u", 'ޫ' to "uː",
        'ެ' to "e", 'ޭ' to "eː",
        'ޮ' to "o", 'ޯ' to "oː"
    )
    private const val SUKUN = 'ް'

    fun analyzeWord(text: String): Word {
        val word = Normalizer.normalize(text, Normalizer.Form.NFC)
        val phones = mutableListOf<Phone>()
        val warnings = mutableListOf<String>()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            // A bare noonu before a stop can indicate a single prenasalized
            // consonant, as distinct from a full /n/ + stop sequence.
            if (c == 'ނ' && word.getOrNull(i + 1) in setOf('ބ','ޕ','ތ','ދ','ޓ','ޑ','ކ','ގ','ޗ','ޖ')) {
                phones.add(Phone("ⁿ", false, "ނ"))
                warnings.add("Prenasalization timing needs a Dhivehi speaker review.")
                i++
                continue
            }
            if (c == 'އ' && i + 1 < word.length && word[i + 1] in vowelIPA) {
                // Alifu is a carrier for a vowel, not a consonant.
                i++
                continue
            }
            if (c in vowelIPA) {
                val sound = vowelIPA.getValue(c)
                phones.add(Phone(sound, sound.endsWith('ː'), c.toString()))
                i++
                continue
            }
            if (c in consonantIPA) {
                if (i + 1 < word.length && word[i + 1] == SUKUN) {
                    if (c == 'އ' || c == 'ށ' || c == 'ނ' || c == 'ތ') {
                        val following = word.getOrNull(i + 2)
                        val next = following?.let { consonantIPA[it] }.orEmpty()
                        if (next.isNotEmpty()) {
                            // Alifu and shaviyani sukun regularly geminate
                            // an immediately following consonant. Noonu sukun
                            // is used for geminate nasals. Thaa sukun can add
                            // a palatal glide before consonant lengthening.
                            if (c == 'ތ') phones.add(Phone("j", false, "ތް"))
                            if (c == 'ނ' && next !in setOf("n", "m", "ɲ", "ɳ")) {
                                phones.add(Phone("n", false, "ން"))
                                warnings.add("Non-nasal following noonu-sukun: check assimilation.")
                            } else {
                                phones.add(Phone(next, true, "$cް"))
                            }
                        } else if (c == 'އ' || c == 'ށ' || c == 'ތ') {
                            if (c == 'ތ') phones.add(Phone("j", false, "ތް"))
                            phones.add(Phone("ʔ", false, "$cް"))
                            warnings.add("Word-final closure is approximate and context-dependent.")
                        } else {
                            phones.add(Phone("n", false, "$cް"))
                        }
                    } else {
                        phones.add(Phone(consonantIPA.getValue(c), false, "$cް"))
                    }
                    i += 2
                } else {
                    val sound = consonantIPA.getValue(c)
                    if (sound.isNotEmpty()) phones.add(Phone(sound, false, c.toString()))
                    i++
                }
            } else {
                warnings.add("Unknown Thaana character U+${c.code.toString(16)}")
                i++
            }
        }
        return Word(phones, warnings)
    }
}
