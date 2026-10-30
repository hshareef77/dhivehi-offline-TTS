package mv.dhivehi.tts.engine

/**
 * Splits long Dhivehi text into segments the model can speak in one pass.
 *
 * CSM-1B was fine-tuned on single sentences (about 10 seconds of audio by default), so
 * long input is split at sentence punctuation, then at clause punctuation, then at spaces,
 * keeping each segment at most [maxChars] characters.
 */
object TextSegmenter {
    private val sentenceEnd = Regex("(?<=[.!?؟\\n])\\s*")
    private val clauseEnd = Regex("(?<=[،,;:؛])\\s+")

    fun split(text: String, maxChars: Int = 160): List<String> {
        val normalized = text.replace("\r\n", "\n").replace(' ', ' ')
        val out = ArrayList<String>()
        for (sentence in normalized.split(sentenceEnd)) {
            val s = sentence.replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty()) continue
            if (s.length <= maxChars) { out.add(s); continue }
            val buf = StringBuilder()
            for (clause in s.split(clauseEnd)) {
                for (piece in splitWords(clause, maxChars)) {
                    if (buf.isNotEmpty() && buf.length + 1 + piece.length > maxChars) {
                        out.add(buf.toString()); buf.setLength(0)
                    }
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(piece)
                }
            }
            if (buf.isNotEmpty()) out.add(buf.toString())
        }
        return out.filter { seg -> seg.any { it.isLetterOrDigit() } }
    }

    private fun splitWords(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (word in text.split(' ')) {
            if (buf.isNotEmpty() && buf.length + 1 + word.length > maxChars) { out.add(buf.toString()); buf.setLength(0) }
            if (word.length > maxChars) {
                word.chunked(maxChars).forEach { out.add(it) }
                continue
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(word)
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }
}
