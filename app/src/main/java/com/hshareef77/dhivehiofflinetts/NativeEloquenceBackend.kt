package com.hshareef77.dhivehiofflinetts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import com.blindtechnexus.freeeloquencetts.EvvNative
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct JNI PCM streaming (no intermediate WAV, no Android TTS delegation).
 * Availability depends on the optional native .so being packaged at build time.
 */
internal class NativeEloquenceBackend {
    private val stop = AtomicBoolean(false)
    @Volatile private var handle = 0L
    private val cp1252 = Charset.forName("windows-1252")

    val available get() = EvvNative.loaded
    @Synchronized fun open(): Boolean {
        if (!available) return false
        if (handle != 0L) return true
        return try {
            handle = EvvNative.create(0x00010000) // U.S. English language module
            if (handle == 0L) false else {
                EvvNative.setParam(handle, 5, 1) // 11025 Hz
                true
            }
        } catch (_: Throwable) { false }
    }

    /**
     * Called only on Android's synthesis worker. onStop may race safely:
     * handle lifetime is held until service destruction after synthesis.
     */
    fun synthesize(phonetics: String, speed: Int, pitch: Int, callback: SynthesisCallback, preset: Int = 0, directPhonemes: Boolean = false): Boolean {
        stop.set(false)
        if (!open() || stop.get()) return false
        val h = handle
        if (h == 0L || phonetics.isBlank() || stop.get()) return false
        val data = phonetics.toByteArray(cp1252)
        if (data.isEmpty() || data.size > 3800) return false
        try {
            // Voice scratch/current parameters documented in upstream Eci.kt.
            // ECI rate is not linearly equivalent to Android's 100=1x; this is
            // an initial conservative calibration for listening experiments.
            EvvNative.setParam(h, 1, if (directPhonemes) 1 else 0)
            NativeVoices.apply(h, preset, speed / 100f, pitch / 100f)
            if (!EvvNative.speak(h, data)) return false
            if (callback.start(11025, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                EvvNative.stop(h)
                return false
            }
            val buffer = ByteArray(minOf(4096, callback.maxBufferSize.coerceAtLeast(2)))
            var timeouts = 0
            while (!stop.get()) {
                when (val n = EvvNative.read(h, buffer, 200)) {
                    in 1..buffer.size -> {
                        timeouts = 0
                        if (callback.audioAvailable(buffer, 0, n) != TextToSpeech.SUCCESS) {
                            EvvNative.stop(h)
                            return false
                        }
                    }
                    0 -> return !stop.get()
                    -1 -> return false
                    -2 -> if (++timeouts >= 150) { // bounded 30 sec no-progress
                        EvvNative.stop(h)
                        return false
                    }
                    else -> return false
                }
            }
            return false
        } catch (_: Throwable) {
            try { EvvNative.stop(h) } catch (_: Throwable) {}
            return false
        }
    }

    fun interrupt() {
        stop.set(true)
        val h = handle
        if (h != 0L) try { EvvNative.stop(h) } catch (_: Throwable) {}
    }

    @Synchronized fun close() {
        interrupt()
        val h = handle
        handle = 0L
        if (h != 0L) try { EvvNative.destroy(h) } catch (_: Throwable) {}
    }
}
