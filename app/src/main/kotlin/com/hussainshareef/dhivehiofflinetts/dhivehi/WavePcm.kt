package com.hussainshareef.dhivehiofflinetts.dhivehi

import java.io.File
import java.io.RandomAccessFile

/** Minimal defensive WAVE PCM parser, independent of Android audio APIs. */
object WavePcm {
    data class Header(
        val sampleRate: Int,
        val channels: Int,
        val dataOffset: Long,
        val dataLength: Long
    )

    fun readHeader(file: File): Header? {
        if (!file.isFile || file.length() < 44L || file.length() > 48L * 1024 * 1024) return null
        try {
            RandomAccessFile(file, "r").use { wav ->
                fun four(): String {
                    val bytes = ByteArray(4)
                    wav.readFully(bytes)
                    return String(bytes, Charsets.US_ASCII)
                }
                fun uint(): Long {
                    val a = wav.readUnsignedByte()
                    val b = wav.readUnsignedByte()
                    val c = wav.readUnsignedByte()
                    val d = wav.readUnsignedByte()
                    return (a or (b shl 8) or (c shl 16) or (d shl 24)).toLong() and 0xffffffffL
                }
                fun ushort(): Int {
                    val a = wav.readUnsignedByte()
                    val b = wav.readUnsignedByte()
                    return a or (b shl 8)
                }
                if (four() != "RIFF") return null
                val riffSize = uint()
                if (four() != "WAVE") return null
                val limit = minOf(wav.length(), riffSize + 8)
                var pcmType = -1
                var channels = -1
                var rate = -1
                var bits = -1
                var at = -1L
                var size = 0L
                while (wav.filePointer + 8 <= limit) {
                    val chunk = four()
                    val len = uint()
                    val start = wav.filePointer
                    if (len > limit - start) return null
                    when (chunk) {
                        "fmt " -> {
                            if (len < 16) return null
                            pcmType = ushort()
                            channels = ushort()
                            rate = uint().toInt()
                            uint()
                            ushort()
                            bits = ushort()
                        }
                        "data" -> {
                            at = start
                            size = len
                        }
                    }
                    val after = start + len + (len and 1L)
                    if (after > limit) {
                        if (after == limit + 1 && (len and 1L) == 1L) break
                        return null
                    }
                    wav.seek(after)
                    if (pcmType >= 0 && at >= 0) break
                }
                if (pcmType != 1 || channels !in 1..2 ||
                    rate !in 8000..48000 || bits != 16 || at < 0 || size <= 0L
                    || size % (channels * 2) != 0L) return null
                return Header(rate, channels, at, size)
            }
        } catch (_: Exception) {
            return null
        }
    }
}
