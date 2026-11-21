package com.hussainshareef.dhivehiofflinetts.dhivehi

/**
 * Stage 2: Dhivehi IPA phone inventory -> native OpenEVV phoneme alphabet.
 *
 * Never uses English orthographic pronunciation. The English *acoustic*
 * inventory still approximates a number of real Dhivehi contrasts.
 * Each spelling is interpreted ONCE in DhivehiPhonology.
 */
object DhivehiEloquenceMapper {
    data class Result(val spr: String, val approximate: Set<String>)

    private val available = mapOf(
        "h" to "h", "ʂ" to "S", "n" to "n", "r" to "r",
        "b" to "b", "ɭ" to "l", "k" to "k", "ʋ" to "v",
        "m" to "m", "f" to "f", "d̪" to "d", "t̪" to "t",
        "l" to "l", "ɡ" to "g", "ɲ" to "ny",
        "s" to "s", "ɖ" to "d", "z" to "z", "ʈ" to "t",
        "j" to "y", "p" to "p", "dʒ" to "J", "tʃ" to "C",
        "θ" to "T", "ħ" to "h", "x" to "k", "ð" to "D",
        "ʒ" to "Z", "ʃ" to "S", "sˤ" to "s", "dˤ" to "d",
        "tˤ" to "t", "zˤ" to "z", "ʕ" to "", "ɣ" to "g",
        "q" to "k", "w" to "w", "ɳ" to "n",
        "ⁿ" to "n", "ʔ" to "",
        "a" to "a", "aː" to "aa",
        "i" to "i", "iː" to "ii",
        "u" to "u", "uː" to "uu",
        "e" to "E", "eː" to "ee",
        "o" to "o", "oː" to "oo"
    )
    private val vowels = setOf(
        "a", "aː", "i", "iː", "u", "uː",
        "e", "eː", "o", "oː"
    )
    private val approximations = setOf(
        "ʂ", "ɭ", "ʋ", "d̪", "t̪", "ɲ", "ɖ", "ʈ",
        "θ", "ħ", "x", "ð", "sˤ", "dˤ", "tˤ", "zˤ",
        "ʕ", "ɣ", "q", "ɳ", "ⁿ", "ʔ", "aː", "iː",
        "uː", "eː", "oː"
    )

    fun map(word: DhivehiPhonology.Word): Result {
        val output = StringBuilder()
        val approximated = linkedSetOf<String>()
        val onset = StringBuilder()
        val phones = word.phones
        val vowelPositions = phones.indices.filter { phones[it].ipa in vowels }
        // Dhivehi stress is often described as leftmost heavy syllable,
        // otherwise the first. A long vowel or a following geminate consonant
        // makes a syllable potentially heavy. This is an approximation, and
        // lexical/dialect-specific exceptions need future corpus review.
        val stressSyllable = vowelPositions.indexOfFirst { vowelAt ->
            val isLongVowel = phones[vowelAt].long
            val nextVowel = vowelPositions.firstOrNull { it > vowelAt } ?: phones.size
            val followingGeminate = (vowelAt + 1 until nextVowel).any { phones[it].long }
            isLongVowel || followingGeminate
        }.let { if (it < 0) 0 else it }
        var syllable = 0
        var firstSyllable = true
        for (phone in word.phones) {
            if (phone.ipa in approximations) approximated.add(phone.ipa)
            val code = available[phone.ipa]
            if (code == null) {
                approximated.add(phone.ipa)
                continue
            }
            if (phone.ipa in vowels) {
                output.append(if (syllable == stressSyllable) ".1" else ".0")
                syllable++
                output.append(onset)
                output.append(code)
                onset.setLength(0)
                firstSyllable = false
            } else {
                onset.append(code)
            }
        }
        if (onset.isNotEmpty()) {
            if (firstSyllable) output.append(".1")
            output.append(onset)
        }
        if (output.isEmpty()) output.append(".1a")
        return Result(output.toString(), approximated)
    }
}
