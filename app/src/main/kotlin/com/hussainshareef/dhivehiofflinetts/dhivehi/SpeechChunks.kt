package com.hussainshareef.dhivehiofflinetts.dhivehi

/**
 * Bounded segments for TextToSpeech.speak(). Never drops characters.
 * Prefer sentence/word boundaries, but split very long unspaced passages.
 */
object SpeechChunks {
    fun split(text: String, max: Int = 1800): List<String> {
        require(max >= 16)
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var from = 0
        while (from < text.length) {
            val upper = (from + max).coerceAtMost(text.length)
            var end = upper
            if (upper != text.length) {
                val window = text.substring(from, upper)
                val sentence = window.indexOfLast { it == '.' || it == '?' || it == '!' || it == '\n' }
                val whitespace = window.indexOfLast { it.isWhitespace() }
                val candidate = if (sentence >= max / 3) sentence + 1
                    else if (whitespace >= max / 3) whitespace + 1
                    else -1
                if (candidate > 0) end = from + candidate
            }
            // Avoid splitting a UTF-16 surrogate pair.
            if (end < text.length && end > from && Character.isHighSurrogate(text[end - 1])
                && Character.isLowSurrogate(text[end])) end--
            if (end <= from) end = upper
            chunks.add(text.substring(from, end))
            from = end
        }
        return chunks
    }
}
