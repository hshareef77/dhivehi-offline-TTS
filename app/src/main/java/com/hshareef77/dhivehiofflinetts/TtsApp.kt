package com.hshareef77.dhivehiofflinetts

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hussainshareef.dhivehiofflinetts.BuildConfig
import mv.dhivehi.tts.engine.OmniVoiceEngine
import mv.dhivehi.tts.engine.GenerationListener
import mv.dhivehi.tts.engine.TextSegmenter
import mv.dhivehi.tts.engine.DhivehiNumbers
import mv.dhivehi.tts.engine.WavWriter
import mv.dhivehi.tts.engine.ReferenceWav
import android.net.Uri
import mv.dhivehi.tts.engine.GenerationCancelledException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the long-running work (model download and speech generation) so it survives
 * the activity being recreated. Work runs on one background thread; while it runs a
 * foreground service keeps the process alive.
 */
class TtsApp : Application() {
    enum class Job { NONE, DOWNLOAD, LOAD, GENERATE }

    interface Listener {
        /** [message] is shown on screen; [announce] means it should also be spoken by the screen reader. */
        fun onStatus(message: String, announce: Boolean)
        fun onJobChanged(job: Job)
        fun onRecordingReady(file: File) {}
        fun onModelChanged() {}
    }

    lateinit var models: ModelRepository
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancel = AtomicBoolean(false)
    @Volatile var job = Job.NONE
        private set
    @Volatile var lastStatus = ""
        private set
    var listener: Listener? = null
    private var engine: OmniVoiceEngine? = null

    val recordingsDir: File by lazy { File(filesDir, "recordings").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        models = ModelRepository(this, BuildConfig.MODEL_BASE_URL)
    }

    private fun status(message: String, announce: Boolean) {
        lastStatus = message
        main.post { listener?.onStatus(message, announce) }
        if (job != Job.NONE) KeepAliveService.update(this, message)
    }

    private fun begin(j: Job): Boolean {
        if (job != Job.NONE) return false
        cancel.set(false)
        job = j
        main.post { listener?.onJobChanged(j) }
        KeepAliveService.start(this, lastStatus)
        return true
    }

    private fun end() {
        job = Job.NONE
        main.post { listener?.onJobChanged(Job.NONE) }
        KeepAliveService.stop(this)
    }

    fun cancelJob() {
        if (job != Job.NONE) cancel.set(true)
    }

    // ---------------------------------------------------------------- model management

    fun startDownload() {
        if (!begin(Job.DOWNLOAD)) return
        worker.execute {
            try {
                status("Checking the voice model on the download server…", true)
                val m = models.fetchManifest()
                val installed = models.installedManifest()
                if (installed != null && installed.version >= m.version) {
                    status("The latest voice model (version ${installed.version}) is already installed. Nothing to download.", true)
                    return@execute
                }
                val remaining = models.remainingBytes(m)
                status("Downloading the voice model: ${ModelRepository.gb(remaining)} to download, " +
                    "${ModelRepository.gb(m.totalSize)} in total.", true)
                closeEngine()
                var lastAnnounced = -1
                models.download(m, object : ModelRepository.Progress {
                    override fun onProgress(doneBytes: Long, totalBytes: Long, stage: String) {
                        val pct = (doneBytes * 100 / totalBytes.coerceAtLeast(1)).toInt()
                        val tens = pct / 10
                        val announce = tens != lastAnnounced
                        if (announce) lastAnnounced = tens
                        val text = if (stage.startsWith("Checking")) "Checking downloaded files, $pct percent"
                            else "Downloading voice model, $pct percent, ${ModelRepository.gb(doneBytes)} of ${ModelRepository.gb(totalBytes)}"
                        status(text, announce)
                    }
                    override fun isCancelled() = cancel.get()
                })
                status("Voice model version ${m.version} downloaded, checked and installed. You can now generate speech offline.", true)
                main.post { listener?.onModelChanged() }
            } catch (e: ModelRepository.CancelledException) {
                status("Download cancelled. Choose Download voice model to continue where it stopped.", true)
            } catch (e: ModelRepository.UserFacingException) {
                status(e.message ?: "Download failed.", true)
            } catch (e: Exception) {
                recordError("download", e)
                status("Download failed: ${e.message ?: e.javaClass.simpleName}. Choose Download voice model to try again.", true)
            } finally {
                end()
            }
        }
    }

    fun checkForUpdate(onResult: (String) -> Unit) {
        worker.execute {
            val msg = try {
                val m = models.fetchManifest()
                val installed = models.installedManifest()
                when {
                    installed == null -> "A voice model is available: version ${m.version}, ${ModelRepository.gb(m.totalSize)}. Choose Download voice model to install it."
                    m.version > installed.version -> "An updated voice model is available: version ${m.version}, ${ModelRepository.gb(m.totalSize)}. Choose Download voice model to update."
                    else -> "Your voice model is up to date (version ${installed.version})."
                }
            } catch (e: ModelRepository.UserFacingException) {
                e.message ?: "Could not check for updates."
            } catch (e: Exception) {
                "Could not check for updates: ${e.message}"
            }
            main.post { onResult(msg) }
        }
    }

    fun deleteModel(onDone: () -> Unit) {
        if (job != Job.NONE) return
        worker.execute {
            closeEngine()
            models.deleteModel()
            main.post { listener?.onModelChanged(); onDone() }
        }
    }

    // ---------------------------------------------------------------- speech generation

    private fun closeEngine() {
        engine?.close()
        engine = null
    }

    private fun availableMemoryBytes(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }

    private fun ensureEngine(): OmniVoiceEngine {
        engine?.let { return it }
        val dir = models.installedDir() ?: throw ModelRepository.UserFacingException(
            "No voice model is installed. Choose Download voice model first.")
        val avail = availableMemoryBytes()
        if (avail < 1_200_000_000L) {
            throw ModelRepository.UserFacingException("Not enough free memory to load the voice model " +
                "(about ${avail / 1_000_000} MB free, about 1200 MB needed). Close other apps and try again.")
        }
        status("Loading the voice model. This takes a few seconds…", true)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return OmniVoiceEngine(dir, threads).also { engine = it }
    }

    private val errorFile: File by lazy { File(filesDir, "last_error.txt") }

    /** Keeps technical details of the last failure so the user can share them for support. */
    private fun recordError(what: String, e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            errorFile.writeText("Dhivehi Offline TTS ${BuildConfig.VERSION_NAME} (${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE})\n" +
                "Failed during: $what\nModel: ${models.installedManifest()?.let { "version ${it.version}" } ?: "none"}\n\n$sw")
        } catch (_: Exception) {}
    }

    fun lastErrorReport(): String? = errorFile.takeIf { it.exists() }?.readText()

    @Volatile private var live: LivePlayer? = null

    /** Stops speech that is playing while generating. */
    fun stopLivePlayback() { live?.stop(); live = null }

    private val referenceFile: File by lazy { File(filesDir, "omnivoice_reference.json") }
    fun hasReferenceVoice() = referenceFile.exists()
    fun deleteReferenceVoice() { if (job == Job.NONE) referenceFile.delete() }

    fun importReference(uri: Uri, transcript: String) {
        if (transcript.isBlank()) { status("Enter the exact words spoken in the reference recording first.", true); return }
        if (!begin(Job.LOAD)) return
        worker.execute {
            try {
                status("Preparing reference voice on this phone…", true)
                val audio = contentResolver.openInputStream(uri)?.use { ReferenceWav.read(it) }
                    ?: error("Cannot open reference audio")
                val voice = ensureEngine().createVoice(audio, transcript, object : GenerationListener {
                    override fun isCancelled() = cancel.get()
                })
                if (cancel.get()) throw GenerationCancelledException()
                voice.save(referenceFile)
                status("Reference voice saved. Select Saved reference voice to use it offline.", true)
                main.post { listener?.onModelChanged() }
            } catch (e: GenerationCancelledException) {
                status("Reference preparation cancelled.", true)
            } catch (e: Throwable) {
                recordError("reference voice", e)
                closeEngine()
                status("Could not prepare reference voice: ${e.message}", true)
            } finally { end() }
        }
    }

    fun generate(text: String, useReference: Boolean, speakWhileGenerating: Boolean = false, speed: Float = 1f) {
        if (text.isBlank()) { status("Please enter some Dhivehi text first.", true); return }
        if (!begin(Job.GENERATE)) return
        worker.execute {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val out = File(recordingsDir, "${stamp}_OmniVoice.wav")
            try {
                val eng = ensureEngine()
                val voice = if (useReference) {
                    require(referenceFile.exists()) { "Import a reference voice first, or select Automatic voice." }
                    OmniVoiceEngine.Voice.load(referenceFile)
                } else null
                val segments = ArrayList<String>()
                fun addSection(section: String) {
                    if (eng.estimateFrames(section, voice) <= 350) { segments.add(section); return }
                    val pieces = TextSegmenter.split(section, (section.length / 2).coerceAtLeast(1))
                    require(pieces.size > 1) { "A word is too long to speak safely." }
                    pieces.forEach { addSection(it) }
                }
                TextSegmenter.split(DhivehiNumbers.normalize(text), 120).forEach { addSection(it) }
                require(segments.isNotEmpty()) { "There is no text to speak." }
                if (speakWhileGenerating) live = LivePlayer(eng.sampleRate, speed)
                val started = System.currentTimeMillis()
                var lastAnnounceAt = 0L
                var samples = 0L
                WavWriter(out, eng.sampleRate).use { wav ->
                    segments.forEachIndexed { index, section ->
                        if (cancel.get()) throw GenerationCancelledException()
                        status("Generating part ${index + 1} of ${segments.size} with OmniVoice…", true)
                        lastAnnounceAt = System.currentTimeMillis()
                        val audio = eng.synthesize(section, voice, 32, object : GenerationListener {
                            override fun onFrame(frames: Int, maxFrames: Int) {
                                val now = System.currentTimeMillis()
                                val announce = now - lastAnnounceAt >= 15000
                                if (announce) lastAnnounceAt = now
                                status("Part ${index + 1} of ${segments.size}: generation step $frames of $maxFrames", announce)
                            }
                            override fun isCancelled() = cancel.get()
                        })
                        wav.write(audio)
                        live?.enqueue(audio)
                        if (index < segments.lastIndex) { wav.writeSilence(0.25f); live?.enqueue(FloatArray(eng.sampleRate / 4)) }
                    }
                    samples = wav.samplesWritten
                }
                live?.finish(); live = null
                val took = (System.currentTimeMillis() - started) / 1000.0
                status("Speech ready: %.0f seconds of audio, generated in %.0f seconds. Choose Play to listen.".format(Locale.US, samples.toDouble() / eng.sampleRate, took), true)
                main.post { listener?.onRecordingReady(out) }
            } catch (e: GenerationCancelledException) {
                if (out.length() > 44) {
                    status("Generation cancelled. Completed parts were saved as a recording.", true)
                    main.post { listener?.onRecordingReady(out) }
                } else { out.delete(); status("Generation cancelled.", true) }
            } catch (e: Throwable) {
                recordError("OmniVoice generation", e)
                closeEngine()
                if (out.length() > 44) main.post { listener?.onRecordingReady(out) } else out.delete()
                status(if (e is OutOfMemoryError) "The phone ran out of memory. Close other apps and try again."
                    else "Speech generation failed: ${e.message}. Completed parts, if any, were saved. Share error details in Help for support.", true)
            } finally {
                if (cancel.get()) stopLivePlayback() else { live?.finish(); live = null }
                end()
            }
        }
    }
}
