package mv.dhivehi.tts.engine

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded RIFF/WAV reader for PCM16 and IEEE float, mono/stereo; resamples to 24 kHz. */
object ReferenceWav {
    fun read(input: InputStream): FloatArray {
        val out = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (out.size() <= 8 * 1024 * 1024) {
            val n = input.read(block)
            if (n < 0) break
            out.write(block, 0, n)
        }
        val bytes = out.toByteArray()
        require(bytes.size <= 8 * 1024 * 1024) { "Reference WAV is too large. Use a 3 to 10 second clip." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "Choose a WAV recording (PCM 16-bit or float). MP3 and other formats must be converted to WAV first."
        }
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var data = -1; var size = 0; var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4); val length = b.getInt(offset + 4)
            require(length >= 0 && length <= bytes.size - offset - 8) { "Damaged WAV file." }
            if (name == "fmt " && length >= 16) {
                format = b.getShort(offset + 8).toInt(); channels = b.getShort(offset + 10).toInt()
                rate = b.getInt(offset + 12); bits = b.getShort(offset + 22).toInt()
            }
            if (name == "data") { data = offset + 8; size = length }
            offset += 8 + length + (length and 1)
        }
        require(data >= 0 && channels in 1..2 && rate in 8000..96000 &&
            ((format == 1 && bits == 16) || (format == 3 && bits == 32))) { "Unsupported WAV. Use PCM 16-bit or float, mono or stereo." }
        val frames = size / (channels * (bits / 8))
        require(frames.toDouble() / rate in 3.0..10.0) { "Use a reference recording between 3 and 10 seconds long." }
        b.position(data)
        val mono = FloatArray(frames) {
            var sum = 0f
            repeat(channels) { sum += if (format == 1) b.short / 32768f else b.float }
            sum / channels
        }
        require(mono.all { it.isFinite() }) { "Invalid audio samples." }
        val count = (frames.toLong() * 24000 / rate).toInt()
        val aligned = ((count + 959) / 960) * 960
        return FloatArray(aligned) { i ->
            if (i >= count) return@FloatArray 0f
            val pos = i.toDouble() * rate / 24000; val lo = pos.toInt().coerceAtMost(frames - 1)
            val hi = (lo + 1).coerceAtMost(frames - 1)
            mono[lo] + (mono[hi] - mono[lo]) * (pos - lo).toFloat()
        }
    }
}
