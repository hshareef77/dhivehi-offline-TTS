package com.hussainshareef.dhivehiofflinetts.dhivehi

/**
 * Phrase-level timing for native OpenEVV phoneme input.
 *
 * The input comes ONLY from the app's validated annotation frontend. It is
 * not safe to apply this to arbitrary raw text with backtick control codes.
 * No audio post-processing, fake phonemes or time stretching is performed.
 */
object DhivehiProsody {
    enum class Style { CONNECTED, CLEAR }

    fun render(annotated: String, style: Style): String {
        if (annotated.isEmpty()) return annotated
        // English word text may occur between native phoneme annotations.
        // We only replace punctuation outside the bracketed annotations,
        // never phoneme dots such as .1 and .0.
        val sentencePause = if (style == Style.CONNECTED) 160 else 280
        val clausePause = if (style == Style.CONNECTED) 85 else 165
        val out = StringBuilder(annotated.length + 32)
        var inside = false
        var i = 0
        while (i < annotated.length) {
            val c = annotated[i]
            if (!inside && c == '`' && annotated.getOrNull(i + 1) == '[') {
                inside = true
                out.append("`[")
                i += 2
                continue
            }
            if (inside) {
                out.append(c)
                if (c == ']') inside = false
                i++
                continue
            }
            when (c) {
                '.', '?', '!', '؟', '۔' -> {
                    // Existing punctuation gets an explicit engine pause;
                    // suppress multiple punctuation marks.
                    if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                    out.append("`p").append(sentencePause).append(' ')
                    while (i + 1 < annotated.length &&
                        annotated[i + 1] in ".?!؟۔") i++
                }
                ',', ';', ':', '،', '؛' -> {
                    if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                    out.append("`p").append(clausePause).append(' ')
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString().trim()
    }
}
