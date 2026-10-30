package mv.dhivehi.tts.engine

import java.io.File

interface LongTextListener {
    fun onSegmentStart(index: Int, count: Int, text: String) {}
    fun onFrame(index: Int, count: Int, frames: Int, maxFrames: Int) {}
    /** Called with each finished segment's audio, e.g. for playback while generating. */
    fun onSegmentAudio(index: Int, count: Int, audio: FloatArray) {}
    fun isCancelled(): Boolean = false
}

/** Speaks text of any length by generating it segment by segment into one WAV file. */
class LongTextSynthesizer(private val engine: CsmEngine) {
    data class Result(val file: File, val seconds: Double, val segments: Int)

    fun synthesizeToWav(
        text: String,
        speaker: Int,
        output: File,
        listener: LongTextListener? = null,
        seed: Long = System.nanoTime(),
        pauseSeconds: Float = 0.25f,
        codebooks: Int? = null,
    ): Result {
        val segments = TextSegmenter.split(DhivehiNumbers.normalize(text))
        require(segments.isNotEmpty()) { "There is no text to speak." }
        val sr = engine.config.sampleRate
        WavWriter(output, sr).use { wav ->
            segments.forEachIndexed { i, seg ->
                listener?.onSegmentStart(i, segments.size, seg)
                val audio = engine.synthesize(seg, speaker, GenerationOptions(seed = seed + i, codebooks = codebooks), object : GenerationListener {
                    override fun onFrame(frames: Int, maxFrames: Int) { listener?.onFrame(i, segments.size, frames, maxFrames) }
                    override fun isCancelled() = listener?.isCancelled() == true
                })
                wav.write(audio)
                if (i < segments.size - 1) wav.writeSilence(pauseSeconds)
                listener?.onSegmentAudio(i, segments.size, audio)
            }
            return Result(output, wav.samplesWritten.toDouble() / sr, segments.size)
        }
    }
}
