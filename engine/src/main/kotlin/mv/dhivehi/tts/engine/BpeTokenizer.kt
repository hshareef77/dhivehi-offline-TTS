package mv.dhivehi.tts.engine

import java.io.File
import java.util.regex.Pattern

/**
 * Byte-level BPE tokenizer compatible with the Llama 3 `tokenizer.json` used by CSM-1B
 * (Split pre-tokenizer with the Llama 3 regex, ByteLevel mapping, `ignore_merges`).
 *
 * Reads the compact files produced by conversion/export_csm_onnx.py:
 * vocab.txt (one byte-level token per line, in id order), merges.txt (rank order)
 * and special_tokens.txt ("id<TAB>content").
 */
class BpeTokenizer(
    vocabTokens: List<String>,
    merges: List<Pair<String, String>>,
    special: Map<String, Int>,
    private val ignoreMerges: Boolean = true,
) {
    private val vocab = HashMap<String, Int>(vocabTokens.size * 2)
    private val mergeRanks = HashMap<Long, Long>(merges.size * 2)
    val specialTokens: Map<String, Int> = special

    init {
        vocabTokens.forEachIndexed { i, t -> vocab[t] = i }
        merges.forEachIndexed { rank, (a, b) ->
            val ia = vocab[a] ?: return@forEachIndexed
            val ib = vocab[b] ?: return@forEachIndexed
            val merged = vocab[a + b] ?: return@forEachIndexed
            mergeRanks[pairKey(ia, ib)] = (rank.toLong() shl 32) or merged.toLong()
        }
    }

    fun tokenId(token: String): Int = specialTokens[token] ?: vocab[token]
        ?: throw IllegalArgumentException("Unknown token $token")

    /** Encodes plain text (no special-token parsing, no BOS/EOS). */
    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        val m = PRE_TOKENIZER.matcher(text)
        var last = 0
        while (m.find()) {
            if (m.start() > last) encodePiece(text.substring(last, m.start()), out)
            encodePiece(m.group(), out)
            last = m.end()
        }
        if (last < text.length) encodePiece(text.substring(last), out)
        return out
    }

    private fun encodePiece(piece: String, out: MutableList<Int>) {
        if (piece.isEmpty()) return
        val bytes = piece.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) sb.append(BYTE_TO_CHAR[b.toInt() and 0xff])
        val mapped = sb.toString()
        if (ignoreMerges) vocab[mapped]?.let { out.add(it); return }
        val ids = IntArray(mapped.length) { i ->
            vocab[mapped[i].toString()] ?: throw IllegalStateException("Byte symbol missing from vocabulary")
        }
        var n = ids.size
        while (n > 1) {
            var bestRank = Long.MAX_VALUE
            var bestPos = -1
            var bestId = 0
            for (i in 0 until n - 1) {
                val v = mergeRanks[pairKey(ids[i], ids[i + 1])] ?: continue
                val rank = v ushr 32
                if (rank < bestRank) {
                    bestRank = rank; bestPos = i; bestId = (v and 0xffffffffL).toInt()
                }
            }
            if (bestPos < 0) break
            // merge every occurrence of the best pair, left to right (same as HF BPE)
            val a = ids[bestPos]
            val b = ids[bestPos + 1]
            var w = 0
            var i = 0
            while (i < n) {
                if (i < n - 1 && ids[i] == a && ids[i + 1] == b) {
                    ids[w++] = bestId; i += 2
                } else {
                    ids[w++] = ids[i++]
                }
            }
            n = w
        }
        for (i in 0 until n) out.add(ids[i])
    }

    companion object {
        /** Llama 3 pre-tokenization pattern; \s must be Unicode-aware like the Rust implementation. */
        val PRE_TOKENIZER: Pattern = Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS,
        )

        private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        /** GPT-2 byte to unicode mapping. */
        val BYTE_TO_CHAR: CharArray = CharArray(256).also { table ->
            val printable = (('!'.code)..('~'.code)) + (('¡'.code)..('¬'.code)) + (('®'.code)..('ÿ'.code))
            val set = printable.toHashSet()
            var extra = 0
            for (b in 0 until 256) {
                table[b] = if (b in set) b.toChar() else (256 + extra++).toChar()
            }
        }

        fun load(dir: File, ignoreMerges: Boolean = true): BpeTokenizer {
            val vocab = File(dir, "vocab.txt").readLines(Charsets.UTF_8).let { lines ->
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
            val merges = File(dir, "merges.txt").readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map {
                val sp = it.indexOf(' ')
                it.substring(0, sp) to it.substring(sp + 1)
            }
            val special = HashMap<String, Int>()
            val sf = File(dir, "special_tokens.txt")
            if (sf.exists()) sf.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.forEach {
                val tab = it.indexOf('\t')
                special[it.substring(tab + 1)] = it.substring(0, tab).toInt()
            }
            return BpeTokenizer(vocab, merges, special, ignoreMerges)
        }
    }
}
