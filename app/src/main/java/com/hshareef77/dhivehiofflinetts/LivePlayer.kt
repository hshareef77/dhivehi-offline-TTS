package com.hshareef77.dhivehiofflinetts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue

/**
 * Plays finished parts of a long text while later parts are still being generated.
 * Parts are queued and written to a streaming AudioTrack on its own thread.
 */
class LivePlayer(private val sampleRate: Int, private val speed: Float) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    @Volatile private var stopped = false
    private val end = FloatArray(0)
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT), sampleRate * 4 / 2))
        .build()

    private val thread = Thread {
        try {
            try { track.playbackParams = track.playbackParams.setSpeed(speed) } catch (e: Exception) {}
            track.play()
            while (!stopped) {
                val part = queue.take()
                if (part === end) break
                var off = 0
                while (off < part.size && !stopped) {
                    val n = track.write(part, off, part.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                }
            }
            if (!stopped) {
                // let the buffered tail play out before releasing
                track.write(FloatArray(sampleRate / 2), 0, sampleRate / 2, AudioTrack.WRITE_BLOCKING)
                track.stop()
            }
        } catch (e: Exception) {
        } finally {
            track.release()
        }
    }.apply { name = "live-player"; start() }

    fun enqueue(audio: FloatArray) { if (!stopped) queue.put(audio) }

    /** No more parts will come; finish playing what is queued. */
    fun finish() { queue.put(end) }

    /** Stop immediately. */
    fun stop() {
        stopped = true
        try { track.pause(); track.flush() } catch (e: Exception) {}
        queue.clear(); queue.put(end)
    }
}
