package com.hshareef77.dhivehiofflinetts

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import mv.dhivehi.tts.engine.CsmEngine
import mv.dhivehi.tts.engine.GenerationCancelledException
import mv.dhivehi.tts.engine.LongTextListener
import mv.dhivehi.tts.engine.LongTextSynthesizer
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
    private var engine: CsmEngine? = null

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

    private fun ensureEngine(): CsmEngine {
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
        return CsmEngine(dir, threads).also { engine = it }
    }

    fun generate(text: String, speaker: Int, speakerName: String) {
        if (text.isBlank()) { status("Please enter some Dhivehi text first.", true); return }
        if (!begin(Job.GENERATE)) return
        worker.execute {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val out = File(recordingsDir, "${stamp}_voice${speaker + 1}.wav")
            try {
                val eng = ensureEngine()
                val started = System.currentTimeMillis()
                var lastAnnounceAt = 0L
                val result = LongTextSynthesizer(eng).synthesizeToWav(text, speaker, out, object : LongTextListener {
                    override fun onSegmentStart(index: Int, count: Int, text: String) {
                        status(if (count == 1) "Generating speech with $speakerName…"
                            else "Generating part ${index + 1} of $count with $speakerName…", true)
                        lastAnnounceAt = System.currentTimeMillis()
                    }
                    override fun onFrame(index: Int, count: Int, frames: Int, maxFrames: Int) {
                        val now = System.currentTimeMillis()
                        val seconds = frames * 0.08
                        val msg = (if (count > 1) "Part ${index + 1} of $count: " else "") +
                            "%.1f seconds of speech generated".format(seconds)
                        // speak progress at most every 15 seconds to avoid flooding the screen reader
                        val announce = now - lastAnnounceAt > 15000
                        if (announce) lastAnnounceAt = now
                        status(msg, announce)
                    }
                    override fun isCancelled() = cancel.get()
                })
                val took = (System.currentTimeMillis() - started) / 1000.0
                status("Speech ready: %.0f seconds of audio, generated in %.0f seconds. Choose Play to listen.".format(result.seconds, took), true)
                main.post { listener?.onRecordingReady(out) }
            } catch (e: GenerationCancelledException) {
                out.delete()
                status("Generation cancelled.", true)
            } catch (e: ModelRepository.UserFacingException) {
                out.delete()
                status(e.message ?: "Generation failed.", true)
            } catch (e: OutOfMemoryError) {
                out.delete(); closeEngine()
                status("The phone ran out of memory while generating. Close other apps and try a shorter text.", true)
            } catch (e: Throwable) {
                out.delete()
                if (e is ai.onnxruntime.OrtException) closeEngine()
                status("Speech generation failed: ${e.message ?: e.javaClass.simpleName}", true)
            } finally {
                end()
            }
        }
    }
}
