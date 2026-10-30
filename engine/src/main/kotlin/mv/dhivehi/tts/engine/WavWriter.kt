package mv.dhivehi.tts.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams 16-bit mono PCM into a WAV file; the header is finalised on close. */
class WavWriter(val file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    var samplesWritten = 0L
        private set

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(audio: FloatArray) {
        val bb = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in audio) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        raf.write(bb.array())
        samplesWritten += audio.size
    }

    fun writeSilence(seconds: Float) = write(FloatArray((seconds * sampleRate).toInt()))

    private fun header(dataBytes: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt((36 + dataBytes).toInt()); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataBytes.toInt())
        return bb.array()
    }

    override fun close() {
        raf.seek(0)
        raf.write(header(samplesWritten * 2))
        raf.close()
    }
}
