package mv.dhivehi.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Thrown when the caller cancels an ongoing generation. */
class GenerationCancelledException : Exception("Generation cancelled")

interface GenerationListener {
    /** Called after every generated audio frame (80 ms of speech). */
    fun onFrame(frames: Int, maxFrames: Int) {}
    fun isCancelled(): Boolean = false
}

data class GenerationOptions(
    val maxFrames: Int? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val seed: Long = System.nanoTime(),
    val greedy: Boolean = false,
)

/**
 * Offline CSM-1B speech generation on ONNX Runtime.
 *
 * Algorithm (identical to conversion/csm_pipeline.py and to Hugging Face
 * CsmForConditionalGeneration.generate):
 *  1. prompt = <|begin_of_text|>[speaker]text<|end_of_text|>, embedded with the text table.
 *  2. backbone step -> hidden state + codebook-0 logits -> sample codebook 0.
 *  3. depth decoder: [hidden, emb(c0)] then emb(c_k) one at a time -> sample codebooks 1..31.
 *  4. stop when codebooks 0..30 are all the EOS code (0); otherwise feed the summed
 *     embeddings of the 32 codes back into the backbone and repeat.
 *  5. Mimi decoder turns the 32 x F code matrix into 24 kHz audio.
 */
class CsmEngine(val modelDir: File, threads: Int = 4) : Closeable {
    val config: ModelConfig = ModelConfig.load(File(modelDir, "model_config.json"))
    val tokenizer: BpeTokenizer = BpeTokenizer.load(File(modelDir, "tokenizer"))
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val textTable = Q8Table(File(modelDir, "text_embeddings.bin"))
    private val audioTable = Q8Table(File(modelDir, "audio_embeddings.bin"))
    private val depthTable = File(modelDir, "depth_embeddings.bin").let { if (it.exists()) Q8Table(it) else audioTable }
    private val backbone: OrtSession
    private val depth: OrtSession
    private val mimi: OrtSession

    init {
        require(textTable.dim == config.hiddenSize && audioTable.dim == config.hiddenSize) { "Embedding size mismatch" }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
        }
        backbone = env.createSession(File(modelDir, "backbone.onnx").path, opts)
        depth = env.createSession(File(modelDir, "depth_decoder.onnx").path, opts)
        mimi = env.createSession(File(modelDir, "mimi_decoder.onnx").path, opts)
    }

    val speakers: List<Speaker> get() = config.speakers

    fun promptTokens(text: String, speaker: Int): List<Int> =
        listOf(config.bosTokenId) + tokenizer.encode("[$speaker]$text") + listOf(config.eosTokenId)

    /** Upper bound on frames for a text segment: generous headroom over typical speaking rate. */
    fun estimateMaxFrames(text: String, promptLength: Int): Int {
        val estimate = 30 + (text.length * 1.6).toInt()
        val positionLimit = config.backboneMaxPositions - promptLength - 1
        return minOf(estimate, 450, positionLimit)
    }

    /** Generates the audio codes for one text segment. Returns frames x numCodebooks. */
    fun generateFrames(
        text: String,
        speaker: Int,
        options: GenerationOptions = GenerationOptions(),
        listener: GenerationListener? = null,
    ): List<IntArray> {
        val ids = promptTokens(text, speaker)
        val maxFrames = options.maxFrames ?: estimateMaxFrames(text, ids.size)
        val sampler = Sampler(options.temperature ?: config.temperature, options.topK ?: config.topK, options.seed, options.greedy)
        val depthSampler = Sampler(options.temperature ?: config.depthTemperature, options.topK ?: config.depthTopK,
            options.seed xor 0x5DEECE66DL, options.greedy)
        val h = config.hiddenSize
        val ncb = config.numCodebooks
        val vocab = config.audioVocabSize

        val frames = ArrayList<IntArray>()
        var embeds = FloatArray(ids.size * h)
        ids.forEachIndexed { i, t -> textTable.copyRow(t, embeds, i * h) }
        var seqLen = ids.size
        val backboneKv = KvCache(config.backboneLayers, config.backboneKvHeads, config.backboneHeadDim)
        val hidden = FloatArray(h)
        val depthIn = FloatArray(2 * h)
        try {
            while (frames.size < maxFrames) {
                if (listener?.isCancelled() == true) throw GenerationCancelledException()
                val res = runStep(backbone, embeds, seqLen, h, backboneKv)
                backboneKv.takePresent(res) // the cache now owns (and later frees) this result
                (res.get("hidden").get() as OnnxTensor).floatBuffer.get(hidden)
                val logits0 = floatsOf(res.get("logits").get() as OnnxTensor)
                val codes = IntArray(ncb)
                codes[0] = sampler.sample(logits0)

                val depthKv = KvCache(config.depthLayers, config.depthKvHeads, config.depthHeadDim)
                try {
                    System.arraycopy(hidden, 0, depthIn, 0, h)
                    depthTable.copyRow(codes[0], depthIn, h)
                    var inLen = 2
                    var input = depthIn
                    val single = FloatArray(h)
                    for (cb in 1 until ncb) {
                        val res = runStep(depth, input, inLen, h, depthKv)
                        depthKv.takePresent(res)
                        val logits = floatsOf(res.get("logits").get() as OnnxTensor)
                        codes[cb] = depthSampler.sample(logits)
                        depthTable.copyRow(cb * vocab + codes[cb], single, 0)
                        input = single
                        inLen = 1
                    }
                } finally {
                    depthKv.close()
                }

                var allEos = true
                for (c in 0 until ncb - 1) if (codes[c] != config.codebookEos) { allEos = false; break }
                if (allEos) break
                frames.add(codes)
                listener?.onFrame(frames.size, maxFrames)

                embeds = FloatArray(h)
                for (c in 0 until ncb) audioTable.addRow(c * vocab + codes[c], embeds, 0)
                seqLen = 1
            }
        } finally {
            backboneKv.close()
        }
        return frames
    }

    /** Decodes frames to 24 kHz mono float audio in [-1, 1]. */
    fun decode(frames: List<IntArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val ncb = config.numCodebooks
        val f = frames.size
        val buf = LongBuffer.allocate(ncb * f)
        for (c in 0 until ncb) for (t in 0 until f) buf.put(frames[t][c].coerceIn(0, 2047).toLong())
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, ncb.toLong(), f.toLong())).use { codes ->
            mimi.run(mapOf("codes" to codes)).use { res ->
                return floatsOf(res.get(0) as OnnxTensor)
            }
        }
    }

    /** Text segment -> audio. */
    fun synthesize(
        text: String,
        speaker: Int,
        options: GenerationOptions = GenerationOptions(),
        listener: GenerationListener? = null,
    ): FloatArray = decode(generateFrames(text, speaker, options, listener))

    private fun runStep(session: OrtSession, embeds: FloatArray, len: Int, h: Int, kv: KvCache): OrtSession.Result {
        val fb = FloatBuffer.wrap(embeds, 0, len * h)
        OnnxTensor.createTensor(env, fb, longArrayOf(1, len.toLong(), h.toLong())).use { x ->
            val inputs = HashMap<String, OnnxTensor>(kv.layers * 2 + 1)
            inputs["inputs_embeds"] = x
            kv.addInputs(inputs)
            return session.run(inputs)
        }
    }

    private fun floatsOf(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /**
     * Holds the KV cache between steps. The cache tensors are owned by the previous
     * session result (or are empty tensors created here) and are released once replaced.
     */
    private inner class KvCache(val layers: Int, kvHeads: Int, headDim: Int) : Closeable {
        private var tensors: List<OnnxTensor>
        private var owner: OrtSession.Result? = null
        private val empty: List<OnnxTensor>

        init {
            val shape = longArrayOf(1, kvHeads.toLong(), 0, headDim.toLong())
            empty = List(2 * layers) { OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape) }
            tensors = empty
        }

        fun addInputs(map: MutableMap<String, OnnxTensor>) {
            for (i in 0 until layers) {
                map["past_key_values.$i.key"] = tensors[2 * i]
                map["past_key_values.$i.value"] = tensors[2 * i + 1]
            }
        }

        /** Takes ownership of [res] (its present.* outputs become the cache); the caller must not close it. */
        fun takePresent(res: OrtSession.Result) {
            val next = ArrayList<OnnxTensor>(2 * layers)
            for (i in 0 until layers) {
                next.add(res.get("present.$i.key").get() as OnnxTensor)
                next.add(res.get("present.$i.value").get() as OnnxTensor)
            }
            owner?.close()
            owner = res
            tensors = next
        }

        override fun close() {
            owner?.close()
            owner = null
            empty.forEach { it.close() }
        }
    }

    override fun close() {
        backbone.close()
        depth.close()
        mimi.close()
    }
}
