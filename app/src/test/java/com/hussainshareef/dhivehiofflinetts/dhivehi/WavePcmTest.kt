package com.hussainshareef.dhivehiofflinetts.dhivehi

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WavePcmTest {
    private fun wave(channels: Int = 1, rate: Int = 22050, valid: Boolean = true): File {
        val file = File.createTempFile("test-dhivehi-", ".wav")
        val buffer = ByteArray(44 + 16)
        fun put16(offset: Int, number: Int) {
            buffer[offset] = (number and 0xff).toByte()
            buffer[offset+1] = ((number shr 8) and 0xff).toByte()
        }
        fun put32(offset: Int, number: Int) {
            for (i in 0..3) buffer[offset+i] = ((number shr (i * 8)) and 0xff).toByte()
        }
        fun label(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(buffer, offset)
        }
        label(0, if (valid) "RIFF" else "NOPE")
        put32(4, buffer.size-8)
        label(8, "WAVE")
        label(12, "fmt ")
        put32(16, 16)
        put16(20, 1)
        put16(22, channels)
        put32(24, rate)
        put32(28, rate * channels * 2)
        put16(32, channels * 2)
        put16(34, 16)
        label(36, "data")
        put32(40, 16)
        file.writeBytes(buffer)
        return file
    }
    @Test fun validMonoPcm() {
        val f = wave()
        try {
            val hdr = WavePcm.readHeader(f)
            assertNotNull(hdr)
            assertEquals(22050, hdr!!.sampleRate)
            assertEquals(1, hdr.channels)
            assertEquals(44L, hdr.dataOffset)
            assertEquals(16L, hdr.dataLength)
        } finally { f.delete() }
    }
    @Test fun validStereoPcm() {
        val f = wave(channels=2, rate=16000)
        try {
            assertEquals(2, WavePcm.readHeader(f)!!.channels)
        } finally { f.delete() }
    }
    @Test fun rejectsInvalidHeaders() {
        val f = wave(valid=false)
        try { assertNull(WavePcm.readHeader(f)) } finally { f.delete() }
    }
    @Test fun rejectsInvalidSampleRate() {
        val f = wave(rate=0)
        try { assertNull(WavePcm.readHeader(f)) } finally { f.delete() }
    }
}
