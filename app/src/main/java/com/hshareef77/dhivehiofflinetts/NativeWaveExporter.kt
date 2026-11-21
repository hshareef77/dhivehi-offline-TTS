package com.hshareef77.dhivehiofflinetts

import com.blindtechnexus.freeeloquencetts.EvvNative
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

/** Saves real native Eloquence PCM speech as standard mono 16-bit WAVE. */
internal class NativeWaveExporter {
    private val generation = AtomicInteger(0)
    @Volatile private var active = 0L

    fun cancel() {
        generation.incrementAndGet()
        val h = active
        if (h != 0L) try { EvvNative.stop(h) } catch (_: Throwable) {}
    }

    fun generate(text: String, rate: Float, destination: File, preset: Int = 0, directPhonemes: Boolean = false, completed: (Boolean, String) -> Unit) {
        cancel()
        val token = generation.get()
        if (!EvvNative.loaded) return completed(false, "Native Eloquence not installed.")
        val encoded = text.toByteArray(Charset.forName("windows-1252"))
        if (encoded.isEmpty() || encoded.size > 3800) {
            return completed(false, "Use a shorter text for native audio generation.")
        }
        Thread({
            var handle = 0L
            var bytesWritten = 0L
            var ok = false
            var description = "Unable to generate audio."
            try {
                handle = EvvNative.create(0x00010000)
                if (handle == 0L) error("Native English voice data could not be loaded.")
                active = handle
                EvvNative.setParam(handle, 5, 1)
                EvvNative.setParam(handle, 1, if (directPhonemes) 1 else 0)
                NativeVoices.apply(handle, preset, rate)
                if (!EvvNative.speak(handle, encoded)) error("Native voice refused this text.")
                RandomAccessFile(destination, "rw").use { wave ->
                    wave.setLength(0)
                    wave.write(ByteArray(44))
                    val buffer = ByteArray(4096)
                    var timeouts = 0
                    while (generation.get() == token) {
                        when (val count = EvvNative.read(handle, buffer, 200)) {
                            in 1..4096 -> {
                                wave.write(buffer, 0, count)
                                bytesWritten += count
                                timeouts = 0
                                if (bytesWritten > 32L * 1024 * 1024)
                                    error("Audio output exceeds preview limit.")
                            }
                            0 -> { ok = bytesWritten > 0L; break }
                            -1 -> break
                            -2 -> if (++timeouts > 150) error("Native voice timed out.")
                            else -> error("Unexpected native audio error.")
                        }
                    }
                    if (ok && generation.get() == token) {
                        wave.seek(0)
                        fun ascii(s: String) { wave.write(s.toByteArray(Charsets.US_ASCII)) }
                        fun little16(v: Int) {
                            wave.write(v and 255); wave.write((v ushr 8) and 255)
                        }
                        fun little32(v: Long) {
                            for (shift in 0..24 step 8)
                                wave.write(((v ushr shift) and 255L).toInt())
                        }
                        ascii("RIFF"); little32(36L + bytesWritten)
                        ascii("WAVE"); ascii("fmt "); little32(16)
                        little16(1); little16(1)
                        little32(11025); little32(22050)
                        little16(2); little16(16)
                        ascii("data"); little32(bytesWritten)
                        description = "Native WAV generated."
                    } else ok = false
                }
            } catch (e: Throwable) {
                ok = false
                description = e.message ?: "Native audio generation failed."
            } finally {
                if (handle != 0L) {
                    try { EvvNative.stop(handle); EvvNative.destroy(handle) } catch (_: Throwable) {}
                    if (active == handle) active = 0L
                }
                if (!ok) destination.delete()
                completed(ok, description)
            }
        }, "DhivehiNativeWav").start()
    }
}
