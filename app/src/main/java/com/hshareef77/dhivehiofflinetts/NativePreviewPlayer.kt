package com.hshareef77.dhivehiofflinetts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.blindtechnexus.freeeloquencetts.EvvNative
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-app low-latency preview of the *actual* native Eloquence engine.
 * Does not require the system-wide TTS adapter or a second installed voice.
 * The native library is optional: report unavailable rather than simulating it.
 */
internal class NativePreviewPlayer(
    private val context: Context,
    private val report: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val encoding = Charset.forName("windows-1252")
    @Volatile private var nativeHandle = 0L
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    val isInstalled: Boolean get() = EvvNative.loaded

    @Synchronized
    fun play(phoneticText: String, speed: Float) {
        stop()
        if (!EvvNative.loaded) {
            report("Native Eloquence engine is not installed in this APK.")
            return
        }
        if (phoneticText.isBlank()) {
            report("Enter Dhivehi text first.")
            return
        }
        val encoded = phoneticText.toByteArray(encoding)
        if (encoded.size > 3800) {
            report("Native preview supports short passages. Select up to 3800 characters.")
            return
        }
        val myGeneration = generation.get()
        fun cancelled(): Boolean = generation.get() != myGeneration
        val worker = Thread({
            var handle = 0L
            var output: AudioTrack? = null
            try {
                handle = EvvNative.create(0x00010000)
                if (handle == 0L) error("Native voice data is missing or could not be initialized.")
                nativeHandle = handle
                EvvNative.setParam(handle, 5, 1) // 11025 Hz mono
                EvvNative.setVoiceParam(handle, 0, 6, (50 * speed).toInt().coerceIn(15, 180))
                if (!EvvNative.speak(handle, encoded)) error("Native engine refused this text.")
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(11025)
                    .build()
                val minBuffer = AudioTrack.getMinBufferSize(
                    11025, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer < 1) error("Audio device does not support native preview.")
                output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuffer, 8192))
                    .build()
                track = output
                output.play()
                val bytes = ByteArray(4096)
                var timeouts = 0
                while (!cancelled()) {
                    when (val count = EvvNative.read(handle, bytes, 200)) {
                        in 1..4096 -> {
                            var offset = 0
                            timeouts = 0
                            while (offset < count && !cancelled()) {
                                val written = output.write(bytes, offset, count - offset)
                                if (written <= 0) error("Audio playback failed.")
                                offset += written
                            }
                        }
                        0 -> break
                        -1 -> break
                        -2 -> if (++timeouts > 150) error("Native engine timed out.")
                        else -> error("Native engine audio error.")
                    }
                }
                if (!cancelled()) ui.post { report("Native preview complete.") }
            } catch (e: Throwable) {
                if (!cancelled.get()) ui.post {
                    report(e.message ?: "Unable to play native Eloquence speech.")
                }
            } finally {
                try { output?.pause(); output?.flush(); output?.release() } catch (_: Throwable) {}
                if (track === output) track = null
                if (handle != 0L) {
                    try { EvvNative.stop(handle); EvvNative.destroy(handle) } catch (_: Throwable) {}
                }
                if (nativeHandle == handle) nativeHandle = 0L
            }
        }, "DhivehiNativePreview")
        thread = worker
        worker.start()
    }

    fun stop() {
        generation.incrementAndGet()
        val h = nativeHandle
        if (h != 0L) try { EvvNative.stop(h) } catch (_: Throwable) {}
        try { track?.pause(); track?.flush() } catch (_: Throwable) {}
        // No main-thread join: let the native worker exit without blocking TalkBack.
    }
}
