package com.hshareef77.dhivehiofflinetts

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.hussainshareef.dhivehiofflinetts.dhivehi.DhivehiPronunciation
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * EXPERIMENTAL system TTS adapter.
 *
 * IMPORTANT: This is a phonetic adapter to a DIFFERENT installed voice engine,
 * not a native Dhivehi voice. It synthesizes to WAV before handing audio to
 * Android, so first-audio latency is too high for production screen-reader use.
 * Refuses to delegate to its own package to avoid infinite recursion.
 */
class DhivehiTtsService : TextToSpeechService() {
    private val stopRequested = AtomicBoolean(false)
    private val engineReady = CountDownLatch(1)
    @Volatile private var healthy = false
    @Volatile private var delegate: TextToSpeech? = null
    @Volatile private var pending: CountDownLatch? = null

    private val experimentalVoice = Voice(
        "dhivehi-phonetic-preview",
        Locale("dv", "MV"),
        Voice.QUALITY_LOW,
        Voice.LATENCY_HIGH,
        false,
        emptySet()
    )

    override fun onCreate() {
        super.onCreate()
        val selected = getSharedPreferences("tts_config", MODE_PRIVATE)
            .getString("delegate_package", null)
        if (selected.isNullOrBlank() || selected == packageName) {
            healthy = false
            engineReady.countDown()
            return
        }
        try {
            delegate = TextToSpeech(applicationContext, { code ->
                healthy = code == TextToSpeech.SUCCESS
                if (healthy) {
                    val rc = delegate?.setLanguage(Locale.US)
                    if (rc == TextToSpeech.LANG_NOT_SUPPORTED ||
                        rc == TextToSpeech.LANG_MISSING_DATA) healthy = false
                }
                engineReady.countDown()
            }, selected)
        } catch (_: Exception) {
            healthy = false
            engineReady.countDown()
        }
    }

    private fun isDhivehi(language: String): Boolean =
        language.equals("dv", true) || language.equals("div", true)

    override fun onGetLanguage(): Array<String> = arrayOf("div", "MDV", "")

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int =
        if (isDhivehi(lang)) TextToSpeech.LANG_COUNTRY_AVAILABLE
        else TextToSpeech.LANG_NOT_SUPPORTED

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): List<Voice> = listOf(experimentalVoice)

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (voiceName == experimentalVoice.name) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String): Int = onIsValidVoiceName(voiceName)

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested.set(false)
        val raw = request.charSequenceText?.toString().orEmpty()
        if (raw.isBlank() || raw.length > TextToSpeech.getMaxSpeechInputLength()) {
            callback.error()
            return
        }
        val tts = delegate
        if (!engineReady.await(8, TimeUnit.SECONDS) || !healthy || tts == null) {
            callback.error()
            return
        }
        val corrections = linkedMapOf<String, String>()
        try {
            val json = org.json.JSONObject(
                getSharedPreferences("pronunciations", MODE_PRIVATE)
                    .getString("pronunciations", "{}") ?: "{}"
            )
            val keys = json.keys()
            while (keys.hasNext()) {
                val word = keys.next()
                if (word.length <= 100) corrections[word] = json.optString(word)
            }
        } catch (_: Exception) { /* speak without user corrections */ }
        val text = DhivehiPronunciation.transliterate(raw, corrections)
        if (text.isBlank() || text.length >= TextToSpeech.getMaxSpeechInputLength()) {
            callback.error()
            return
        }
        val file = File.createTempFile("phonetic_", ".wav", cacheDir)
        val finished = CountDownLatch(1)
        pending = finished
        val success = AtomicInteger(TextToSpeech.ERROR)
        val id = "dhivehi_" + System.nanoTime()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) {
                    success.set(TextToSpeech.SUCCESS)
                    finished.countDown()
                }
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) finished.countDown()
            }
        }
        try {
            tts.setOnUtteranceProgressListener(listener)
            tts.setSpeechRate((request.speechRate / 100f).coerceIn(0.3f, 3f))
            tts.setPitch((request.pitch / 100f).coerceIn(0.5f, 2f))
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                callback.error()
                return
            }
            if (!finished.await(30, TimeUnit.SECONDS) ||
                stopRequested.get() || success.get() != TextToSpeech.SUCCESS) {
                callback.error()
                return
            }
            if (!writeWav(file, callback)) {
                callback.error()
                return
            }
            callback.done()
        } catch (_: Exception) {
            callback.error()
        } finally {
            pending = null
            file.delete()
        }
    }

    /**
     * Reads standard little-endian RIFF WAVE (uncompressed PCM 16-bit only).
     * Skips arbitrary metadata chunks and enforces strict format bounds.
     */
    private fun writeWav(file: File, callback: SynthesisCallback): Boolean {
        if (!file.isFile || file.length() < 44L || file.length() > 48L * 1024 * 1024) return false
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
            if (four() != "RIFF") return false
            uint()
            if (four() != "WAVE") return false
            var format = -1
            var channels = 0
            var rate = 0
            var bits = 0
            var dataAt = -1L
            var dataSize = 0L
            while (wav.filePointer + 8 <= wav.length()) {
                val type = four()
                val len = uint()
                val at = wav.filePointer
                if (len > wav.length() - at) return false
                when (type) {
                    "fmt " -> {
                        if (len < 16) return false
                        format = ushort()
                        channels = ushort()
                        rate = uint().toInt()
                        uint() // byte rate
                        ushort() // block align
                        bits = ushort()
                    }
                    "data" -> {
                        dataAt = at
                        dataSize = len
                    }
                }
                val next = at + len + (len and 1L)
                if (next > wav.length()) break
                wav.seek(next)
                if (dataAt >= 0 && format >= 0) break
            }
            if (format != 1 || bits != 16 || channels !in 1..2 ||
                rate !in 8000..48000 || dataAt < 0 || dataSize <= 0) return false
            if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, channels) != TextToSpeech.SUCCESS) return false
            wav.seek(dataAt)
            val buffer = ByteArray(min(4096, callback.maxBufferSize.coerceAtLeast(1)))
            var left = dataSize
            while (left > 0 && !stopRequested.get()) {
                val count = wav.read(buffer, 0, min(left, buffer.size.toLong()).toInt())
                if (count <= 0) return false
                if (callback.audioAvailable(buffer, 0, count) != TextToSpeech.SUCCESS) return false
                left -= count
            }
            return left == 0L && !stopRequested.get()
        }
    }

    override fun onStop() {
        stopRequested.set(true)
        pending?.countDown()
        delegate?.stop()
    }

    override fun onDestroy() {
        onStop()
        delegate?.shutdown()
        delegate = null
        super.onDestroy()
    }
}
