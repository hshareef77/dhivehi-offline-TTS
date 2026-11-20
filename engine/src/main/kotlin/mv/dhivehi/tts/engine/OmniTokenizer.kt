package mv.dhivehi.tts.engine

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/** Qwen byte BPE, using the exact vocabulary, merge order and regex from the model. */
class OmniTokenizer(file: File) {
    private val bpe: BpeTokenizer
    private val special: Map<String, Int>
    private val specialPattern: Pattern
    init {
        val json = JSONObject(file.readText())
        val model = json.getJSONObject("model")
        require(model.getString("type") == "BPE")
        val v = model.getJSONObject("vocab")
        val tokens = MutableList(v.length()) { "" }
        v.keys().forEach { key -> tokens[v.getInt(key)] = key }
        val m = model.getJSONArray("merges")
        val merges = (0 until m.length()).map { i ->
            val value = m.get(i)
            if (value is org.json.JSONArray) value.getString(0) to value.getString(1)
            else (value as String).let { it.substringBefore(' ') to it.substringAfter(' ') }
        }
        val added = json.getJSONArray("added_tokens")
        special = (0 until added.length()).associate { i ->
            added.getJSONObject(i).let { it.getString("content") to it.getInt("id") }
        }
        val pre = json.getJSONObject("pre_tokenizer").getJSONArray("pretokenizers")
        val regex = (0 until pre.length()).map { pre.getJSONObject(it) }
            .first { it.getString("type") == "Split" }.getJSONObject("pattern").getString("Regex")
        val ws = "\\t\\n\\x0B\\f\\r\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
        val pattern = Pattern.compile(regex.replace("\\s", "[$ws]").replace("\\S", "[^$ws]"))
        bpe = BpeTokenizer(tokens, merges, special, model.optBoolean("ignore_merges", false), pattern)
        specialPattern = Pattern.compile(special.keys.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) })
    }
    fun encode(text: String): List<Int> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val result = ArrayList<Int>()
        val matcher = specialPattern.matcher(normalized)
        var previous = 0
        while (matcher.find()) {
            result.addAll(bpe.encode(normalized.substring(previous, matcher.start())))
            result.add(special.getValue(matcher.group()))
            previous = matcher.end()
        }
        result.addAll(bpe.encode(normalized.substring(previous)))
        return result
    }
}
