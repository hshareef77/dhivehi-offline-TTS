package mv.dhivehi.tts.engine

import java.util.Random
import kotlin.math.exp

/**
 * Top-k sampling with temperature, matching the model's generation_config (temperature 0.9,
 * top-k 50). Only the first [valid] logits are considered: Mimi has 2048 codes, the
 * remaining ids of the 2051-entry vocabulary are padding/special tokens.
 */
class Sampler(
    private val temperature: Float,
    private val topK: Int,
    seed: Long,
    private val greedy: Boolean = false,
    private val valid: Int = 2048,
) {
    private val random = Random(seed)
    private val idx = IntArray(topK)
    private val vals = FloatArray(topK)
    private val probs = DoubleArray(topK)

    fun sample(logits: FloatArray): Int {
        val n = minOf(valid, logits.size)
        if (greedy) {
            var best = 0
            for (i in 1 until n) if (logits[i] > logits[best]) best = i
            return best
        }
        // partial selection of the k largest logits
        val k = minOf(topK, n)
        var count = 0
        for (i in 0 until n) {
            val v = logits[i]
            if (count < k) {
                var j = count++
                while (j > 0 && vals[j - 1] < v) { vals[j] = vals[j - 1]; idx[j] = idx[j - 1]; j-- }
                vals[j] = v; idx[j] = i
            } else if (v > vals[k - 1]) {
                var j = k - 1
                while (j > 0 && vals[j - 1] < v) { vals[j] = vals[j - 1]; idx[j] = idx[j - 1]; j-- }
                vals[j] = v; idx[j] = i
            }
        }
        val max = vals[0].toDouble()
        var sum = 0.0
        for (j in 0 until k) { probs[j] = exp((vals[j] - max) / temperature); sum += probs[j] }
        var r = random.nextDouble() * sum
        for (j in 0 until k) {
            r -= probs[j]
            if (r <= 0) return idx[j]
        }
        return idx[k - 1]
    }
}
