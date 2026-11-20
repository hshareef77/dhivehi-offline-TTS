package mv.dhivehi.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/** Native ONNX inference. No Python, web service, ASR download or network during synthesis. */
class OmniVoiceEngine(private val dir: File, private val threads: Int = 3) : Closeable {
    val sampleRate = 24000
    private val env = OrtEnvironment.getEnvironment()
    val tokenizer = OmniTokenizer(File(dir, "tokenizer.json"))
    private val lm = session("lm.onnx")
    private val decoder = try { session("decoder.onnx") } catch (e: Throwable) { lm.close(); throw e }

    private fun session(name: String): OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Dynamic sentence lengths must not retain large activation arenas between calls.
        opts.setMemoryPatternOptimization(false)
        opts.setCPUArenaAllocator(false)
        env.createSession(File(dir, name).path, opts)
    }
    data class Voice(val text: String, val codes: Array<LongArray>, val rms: Float) {
        val frames get() = codes[0].size
        fun save(file: File) {
            val j = JSONObject().put("format", 1).put("text", text).put("rms", rms)
            j.put("codes", JSONArray(codes.map { JSONArray(it.toList()) }))
            val temp = File(file.path + ".tmp"); temp.writeText(j.toString())
            check(temp.renameTo(file)) { "Cannot save reference voice" }
        }
        companion object {
            fun load(file: File): Voice {
                val j = JSONObject(file.readText()); require(j.getInt("format") == 1)
                val a = j.getJSONArray("codes")
                require(a.length() == 8)
                val c = Array(8) { i -> a.getJSONArray(i).let { r -> LongArray(r.length()) { r.getLong(it) } } }
                require(c[0].size in 50..275 && c.all { row -> row.size == c[0].size && row.all { it in 0L..1023L } })
                return Voice(j.getString("text"), c, j.getDouble("rms").toFloat())
            }
        }
    }
    /** PCM must be mono, 24 kHz. Reference encoder is unloaded as soon as cloning finishes. */
    fun createVoice(audio: FloatArray, text: String, listener: GenerationListener? = null): Voice {
        require(audio.size in sampleRate * 3..sampleRate * 10) { "Choose a 3 to 10 second reference recording." }
        require(text.isNotBlank()) { "Enter the exact words spoken in the reference recording." }
        require(audio.all { it.isFinite() })
        if (listener?.isCancelled() == true) throw GenerationCancelledException()
        val rms = sqrt(audio.sumOf { it.toDouble() * it } / audio.size).toFloat()
        require(rms > 0.001f) { "The reference recording is silent or too quiet." }
        val scaled = FloatArray(audio.size) { if (rms < 0.1f) audio[it] * 0.1f / rms else audio[it] }
        session("encoder.onnx").use { encoder ->
            val input = encoder.inputNames.single()
            val info = encoder.inputInfo.getValue(input).info as ai.onnxruntime.TensorInfo
            val shape = if (info.shape.size == 3) longArrayOf(1, 1, scaled.size.toLong()) else longArrayOf(1, scaled.size.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(scaled), shape).use { x ->
                encoder.run(mapOf(input to x)).use { result ->
                    val tensor = result[0] as OnnxTensor
                    val dims = tensor.info.shape
                    require(dims.size == 3 && dims[1] == 8L)
                    val n = dims.last().toInt(); val b = tensor.longBuffer
                    val codes = Array(8) { LongArray(n).also { b.get(it) } }
                    return Voice(text.trim(), codes, rms)
                }
            }
        }
    }

    fun estimateFrames(text: String, voice: Voice?): Int {
        val reference = voice?.text ?: "Nice to meet you."
        val frames = voice?.frames ?: 25
        val raw = weight(text) * frames / weight(reference).coerceAtLeast(0.1)
        val estimated = if (raw < 50.0) 50.0 * (raw / 50.0).pow(1.0 / 3.0) else raw
        return estimated.toInt().coerceAtLeast(1)
    }
    private fun weight(s: String): Double = s.codePoints().toArray().sumOf { c ->
        when {
            c == 0x0640 -> 0.0
            Character.getType(c) in listOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) -> 0.0
            Character.isWhitespace(c) || Character.isSpaceChar(c) -> 0.2
            Character.isDigit(c) -> 3.5
            !Character.isLetterOrDigit(c) -> 0.5
            c in 0x590..0x8ff -> 1.5
            else -> 1.0
        }
    }

    fun generateCodes(text: String, voice: Voice? = null, steps: Int = 32,
                      seed: Long = System.nanoTime(), positionTemperature: Double = 5.0,
                      listener: GenerationListener? = null): Array<LongArray> {
        require(steps in 1..64)
        val target = estimateFrames(text, voice)
        require(target <= 375) { "This section is too long; split it into shorter sentences." }
        val style = (if (voice != null) "<|denoise|>" else "") +
            "<|lang_start|>dv<|lang_end|><|instruct_start|>None<|instruct_end|>"
        val fullText = listOfNotNull(voice?.text, text.trim()).joinToString(" ")
            .replace(Regex("[\\r\\n]+"), "").replace(Regex("[ \\t]+"), " ")
        val prompt = tokenizer.encode(style) + tokenizer.encode("<|text_start|>$fullText<|text_end|>")
        val reference = voice?.frames ?: 0
        val start = prompt.size + reference
        val length = start + target
        require(length <= 1400) { "The text and reference are too long." }
        val ids = LongArray(2 * 8 * length) { 1024L }
        for (c in 0 until 8) {
            for (i in prompt.indices) ids[c * length + i] = prompt[i].toLong()
            if (voice != null) for (i in 0 until reference) ids[c * length + prompt.size + i] = voice.codes[c][i]
        }
        val audioMask = Array(2) { BooleanArray(length) }
        for (i in prompt.size until length) audioMask[0][i] = true
        for (i in 0 until target) audioMask[1][i] = true
        val attention = LongArray(2 * length) { if (it < length || it - length < target) 1L else 0L }
        val positions = LongArray(2 * length) { (it % length).toLong() }
        val result = Array(8) { LongArray(target) { 1024L } }
        val random = Random(seed)
        var remaining = 8 * target
        OnnxTensor.createTensor(env, audioMask).use { am ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attention), longArrayOf(2, length.toLong())).use { att ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(positions), longArrayOf(2, length.toLong())).use { pos ->
                    for (step in 0 until steps) {
                        if (listener?.isCancelled() == true) throw GenerationCancelledException()
                        val t0 = shifted(step.toDouble() / steps)
                        val t1 = shifted((step + 1.0) / steps)
                        val count = if (step == steps - 1) remaining else min(remaining, ceil(8 * target * (t1 - t0)).toInt())
                        if (count == 0) continue
                        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(2, 8, length.toLong())).use { x ->
                            lm.run(mapOf("input_ids" to x, "audio_mask" to am, "attention_mask" to att, "position_ids" to pos)).use { output ->
                                val logits = (output[0] as OnnxTensor).floatBuffer
                                val candidates = ArrayList<Triple<Double, Int, Int>>(remaining)
                                for (c in 0 until 8) for (f in 0 until target) {
                                    if (result[c][f] != 1024L) continue
                                    val ci = (c * length + start + f) * 1025
                                    val ui = ((8 + c) * length + f) * 1025
                                    var best = Double.NEGATIVE_INFINITY; var bestId = 0
                                    val guided = DoubleArray(1025)
                                    var maximum = Double.NEGATIVE_INFINITY
                                    for (v in 0..1024) {
                                        val value = 3.0 * logits.get(ci + v) - 2.0 * logits.get(ui + v)
                                        guided[v] = value; maximum = max(maximum, value)
                                        if (v < 1024 && value > best) { best = value; bestId = v }
                                    }
                                    val logZ = maximum + ln(guided.sumOf { exp(it - maximum) })
                                    var score = best - logZ - c * 5.0
                                    if (positionTemperature > 0) {
                                        val u = random.nextDouble().coerceIn(1e-10, 1.0 - 1e-10)
                                        score = score / positionTemperature - ln(-ln(u))
                                    }
                                    candidates.add(Triple(score, c * target + f, bestId))
                                }
                                candidates.sortWith(compareByDescending<Triple<Double, Int, Int>> { it.first }.thenBy { it.second })
                                for (entry in candidates.take(count)) {
                                    val c = entry.second / target; val f = entry.second % target
                                    val token = entry.third.toLong(); result[c][f] = token
                                    ids[c * length + start + f] = token
                                    ids[(8 + c) * length + f] = token
                                }
                            }
                        }
                        remaining -= count
                        listener?.onFrame(step + 1, steps)
                    }
                }
            }
        }
        check(remaining == 0 && result.all { row -> row.all { it in 0L..1023L } }) { "Audio generation did not finish" }
        return result
    }
    private fun shifted(t: Double) = 0.1 * t / (1.0 - 0.9 * t)

    fun decode(codes: Array<LongArray>): FloatArray {
        val frames = codes[0].size
        val input = decoder.inputNames.single()
        val flat = LongArray(8 * frames) { codes[it / frames][it % frames] }
        OnnxTensor.createTensor(env, LongBuffer.wrap(flat), longArrayOf(1, 8, frames.toLong())).use { x ->
            decoder.run(mapOf(input to x)).use { output ->
                val b = (output[0] as OnnxTensor).floatBuffer
                return FloatArray(b.remaining()).also { b.get(it) }
            }
        }
    }
    fun synthesize(text: String, voice: Voice?, steps: Int, listener: GenerationListener?): FloatArray {
        val audio = decode(generateCodes(text, voice, steps, listener = listener))
        require(audio.isNotEmpty() && audio.all { it.isFinite() } && audio.any { abs(it) > 1e-6 }) { "Generated audio is empty or silent." }
        val peak = audio.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val gain = if (voice == null) 0.5f / peak else if (voice.rms < 0.1f) voice.rms / 0.1f else 1f
        val fade = min(sampleRate / 10, audio.size / 2)
        for (i in audio.indices) {
            val ramp = minOf(1f, i.toFloat() / fade.coerceAtLeast(1), (audio.size - 1 - i).toFloat() / fade.coerceAtLeast(1))
            audio[i] = (audio[i] * gain * ramp).coerceIn(-1f, 1f)
        }
        return audio
    }
    override fun close() { decoder.close(); lm.close() }
}
