package com.hshareef77.dhivehiofflinetts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

/**
 * Accessible main screen (TalkBack / Jieshuo+). Built from plain Android views with
 * explicit labels, headings, a polite live region for status, and spoken announcements
 * for important events. All speech is generated on the phone from the installed model.
 */
class MainActivity : Activity(), TtsApp.Listener {
    private lateinit var app: TtsApp
    private lateinit var status: TextView
    private lateinit var modelInfo: TextView
    private lateinit var editor: EditText
    private lateinit var voices: RadioGroup
    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var updateButton: Button
    private lateinit var deleteModelButton: Button
    private lateinit var generateButton: Button
    private lateinit var speakLive: CheckBox
    private lateinit var referenceText: EditText
    private lateinit var importReferenceButton: Button
    private lateinit var playButton: Button
    private lateinit var position: SeekBar
    private lateinit var speed: SeekBar
    private lateinit var recordings: Spinner
    private lateinit var nowPlaying: TextView

    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var currentUri: Uri? = null
    private var recordingFiles: List<File> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updatePosition(); handler.postDelayed(this, 500) }
    }

    private val speakerNames = listOf("Automatic voice", "Saved reference voice")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TtsApp
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun heading(label: String) = TextView(this).apply {
            text = label
            textSize = 22f
            setPadding(0, pad, 0, pad / 2)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }.also { root.addView(it) }
        fun info(label: String) = TextView(this).apply {
            text = label; textSize = 17f; setPadding(0, pad / 4, 0, pad / 4)
        }.also { root.addView(it) }
        fun button(label: String, onPress: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onPress() }
        }.also { root.addView(it) }

        heading("Dhivehi Offline TTS")
        status = info("").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isFocusable = true
            contentDescription = null
        }

        heading("Voice model")
        modelInfo = info("")
        downloadButton = button("Download voice model") { confirmDownload() }
        cancelButton = button("Cancel") { app.cancelJob(); say("Cancelling…") }
        updateButton = button("Check for voice model update") {
            say("Checking for updates…")
            app.checkForUpdate { say(it) }
        }
        deleteModelButton = button("Delete voice model") { confirmDelete() }

        heading("Dhivehi text")
        val editorLabel = info("Text to speak")
        editor = EditText(this).apply {
            id = View.generateViewId()
            hint = "ދިވެހި ލިޔުން މިތަނުގައި ލިޔުއްވާ"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textSize = 20f
        }
        editorLabel.labelFor = editor.id
        root.addView(editor)
        button("Import text file") { pick("text/*", REQ_TXT) }
        button("Clear text") { editor.setText(""); say("Text cleared") }

        heading("Voice")
        voices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; contentDescription = "Choose a voice" }
        speakerNames.forEachIndexed { i, name ->
            voices.addView(RadioButton(this).apply { id = 1000 + i; text = name; textSize = 18f })
        }
        voices.check(1000 + getPreferences(MODE_PRIVATE).getInt("omni_voice", 0).coerceIn(0, 1))
        voices.setOnCheckedChangeListener { _, id -> getPreferences(MODE_PRIVATE).edit().putInt("omni_voice", id - 1000).apply() }
        root.addView(voices)

        heading("Clone a reference voice")
        info("Optional: choose a clear 3 to 10 second WAV recording and enter its exact spoken words. Voice preparation and generation both run offline. A Dhivehi reference helps keep pronunciation and voice consistent.")
        val refLabel = info("Exact words spoken in the reference recording")
        referenceText = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; textSize = 20f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        refLabel.labelFor = referenceText.id
        root.addView(referenceText)
        importReferenceButton = button("Import WAV and save reference voice") {
            if (referenceText.text.isBlank()) say("Enter the exact words spoken in the recording first.")
            else if (app.models.installedDir() == null) say("Download the OmniVoice model first.")
            else pick("audio/*", REQ_REFERENCE)
        }
        button("Delete saved reference voice") {
            AlertDialog.Builder(this).setTitle("Delete reference voice?")
                .setMessage("Delete the saved reference voice? Generated recordings are kept.")
                .setPositiveButton("Delete") { _, _ ->
                    app.deleteReferenceVoice(); voices.check(1000); say("Reference voice deleted.")
                }.setNegativeButton("Keep", null).show()
        }

        heading("Speech")
        speakLive = CheckBox(this).apply {
            text = "Speak each part as soon as it is ready"
            textSize = 18f
            isChecked = getPreferences(MODE_PRIVATE).getBoolean("live", true)
            setOnCheckedChangeListener { _, on -> getPreferences(MODE_PRIVATE).edit().putBoolean("live", on).apply() }
        }
        root.addView(speakLive)
        generateButton = button("Generate speech") { generate() }

        heading("Playback")
        nowPlaying = info("No recording selected.")
        playButton = button("Play") { togglePlay() }
        button("Stop") { stopPlayback(); app.stopLivePlayback(); say("Stopped") }
        button("Back 10 seconds") { skip(-10_000) }
        button("Forward 10 seconds") { skip(10_000) }
        info("Position in recording")
        position = SeekBar(this).apply {
            max = 0
            contentDescription = "Position in recording"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) player?.seekTo(value * 1000)
                    if (Build.VERSION.SDK_INT >= 30) stateDescription = "${fmt(value)} of ${fmt(max)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(position)
        info("Playback speed")
        speed = SeekBar(this).apply {
            max = 15 // 0.5x .. 2.0x in 0.1 steps
            progress = getPreferences(MODE_PRIVATE).getInt("speed", 5)
            contentDescription = "Playback speed"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    describeSpeed()
                    getPreferences(MODE_PRIVATE).edit().putInt("speed", value).apply()
                    player?.let { p -> if (p.isPlaying) applySpeed(p) }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(speed)
        describeSpeed()
        button("Export recording as WAV file") { exportCurrent() }

        heading("Saved recordings")
        recordings = Spinner(this).apply { contentDescription = "Saved recordings" }
        root.addView(recordings)
        button("Open selected recording") { openSelected() }
        button("Delete selected recording") { deleteSelected() }
        button("Open another audio file") { pick("audio/*", REQ_AUDIO) }

        heading("Help")
        button("Share error details") {
            val report = app.lastErrorReport() ?: return@button say("No errors have been recorded.")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Dhivehi Offline TTS error details")
                putExtra(Intent.EXTRA_TEXT, report)
            }, "Share error details"))
        }

        setContentView(ScrollView(this).apply { addView(root) })
        refreshRecordings(null)
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Text shared from another app (Share > Dhivehi Offline TTS) goes into the editor. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        editor.setText(shared.take(200_000))
        say("Shared text received: ${shared.length} characters. Choose Generate speech.")
    }

    override fun onResume() {
        super.onResume()
        app.listener = this
        refreshModel()
        onJobChanged(app.job)
        status.text = app.lastStatus.ifEmpty { status.text }
    }

    override fun onPause() {
        if (app.listener === this) app.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.release(); player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ status

    private fun say(message: String) {
        status.text = message
        status.announceForAccessibility(message)
    }

    override fun onStatus(message: String, announce: Boolean) {
        status.text = message
        if (announce) status.announceForAccessibility(message)
    }

    override fun onJobChanged(job: TtsApp.Job) {
        val busy = job != TtsApp.Job.NONE
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        cancelButton.text = when (job) {
            TtsApp.Job.DOWNLOAD -> "Cancel download"
            TtsApp.Job.GENERATE, TtsApp.Job.LOAD -> "Cancel speech generation"
            TtsApp.Job.NONE -> "Cancel"
        }
        downloadButton.isEnabled = !busy
        deleteModelButton.isEnabled = !busy
        generateButton.isEnabled = !busy
        importReferenceButton.isEnabled = !busy
        voices.isEnabled = !busy
        updateButton.isEnabled = !busy
        if (!busy) refreshModel()
    }

    override fun onModelChanged() = refreshModel()

    override fun onRecordingReady(file: File) {
        refreshRecordings(file)
        load(file)
    }


    private fun refreshModel() {
        val m = app.models.installedManifest()
        val installed = app.models.installedDir() != null
        modelInfo.text = when {
            installed && m != null -> "OmniVoice installed: version ${m.version}, ${ModelRepository.gb(app.models.installedSizeBytes())} on this phone. Speech is generated offline."
            installed -> "Voice model installed."
            app.models.hasPartialDownload() -> "The voice model download is incomplete. Choose Download voice model to continue."
            else -> "OmniVoice requires a one-time download of about 1.37 GB, including voice cloning. Your previous CSM package will be replaced after the download passes verification. Saved recordings are kept."
        }
        deleteModelButton.visibility = if (installed || app.models.hasPartialDownload()) View.VISIBLE else View.GONE
        updateButton.visibility = if (installed) View.VISIBLE else View.GONE
        downloadButton.text = if (installed) "Download voice model update" else "Download voice model"
        generateButton.text = if (installed) "Generate speech" else "Generate speech (download the voice model first)"
    }

    // ------------------------------------------------------------------ model

    private fun confirmDownload() {
        say("Checking the download size…")
        Thread {
            val result = try { Result.success(app.models.fetchManifest()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                result.onFailure { say(it.message ?: "Could not reach the download server.") }
                result.onSuccess { m ->
                    val remaining = app.models.remainingBytes(m)
                    val free = app.models.freeBytes()
                    AlertDialog.Builder(this)
                        .setTitle("Download voice model")
                        .setMessage("Voice model version ${m.version}, ${ModelRepository.gb(m.totalSize)} in total, " +
                            "${ModelRepository.gb(remaining)} still to download. Free storage on this phone: ${ModelRepository.gb(free)}. " +
                            "Use Wi-Fi if possible. The download continues if you leave the app.")
                        .setPositiveButton("Download") { _, _ -> app.startDownload() }
                        .setNegativeButton("Not now", null)
                        .show()
                }
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete voice model")
            .setMessage("Delete the voice model from this phone? You will need to download it again to generate speech. Saved recordings are kept.")
            .setPositiveButton("Delete") { _, _ -> app.deleteModel { say("Voice model deleted.") } }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ------------------------------------------------------------------ generation

    private fun generate() {
        if (app.models.installedDir() == null) {
            say("No voice model is installed. Choose Download voice model first.")
            return
        }
        stopPlayback()
        val reference = voices.checkedRadioButtonId == 1001
        if (reference && !app.hasReferenceVoice()) { say("Import and save a reference voice first, or choose Automatic voice."); return }
        app.generate(editor.text.toString(), reference, speakLive.isChecked, (speed.progress + 5) / 10f)
    }

    // ------------------------------------------------------------------ playback

    private fun load(file: File) {
        stopPlayback()
        player?.release(); player = null
        currentFile = file; currentUri = null
        nowPlaying.text = "Selected recording: ${file.nameWithoutExtension.replace('_', ' ')}"
        preparePlayer { p -> p.setDataSource(file.path) }
    }

    private fun preparePlayer(source: (MediaPlayer) -> Unit) {
        try {
            player = MediaPlayer().apply {
                source(this)
                prepare()
                setOnCompletionListener { playButton.text = "Play"; handler.removeCallbacks(tick); say("Playback finished") }
            }
            position.max = (player!!.duration / 1000).coerceAtLeast(1)
            position.progress = 0
        } catch (e: Exception) {
            player?.release(); player = null
            say("This recording cannot be played.")
        }
    }

    private fun togglePlay() {
        val p = player ?: return say("No recording selected. Generate speech or open a saved recording first.")
        if (p.isPlaying) {
            p.pause(); playButton.text = "Play"; handler.removeCallbacks(tick); say("Paused")
        } else {
            applySpeed(p)
            p.start(); playButton.text = "Pause"; handler.post(tick)
        }
    }

    private fun applySpeed(p: MediaPlayer) {
        try { p.playbackParams = p.playbackParams.setSpeed((speed.progress + 5) / 10f) } catch (e: Exception) {}
    }

    private fun describeSpeed() {
        val v = (speed.progress + 5) / 10f
        val text = "$v times"
        if (Build.VERSION.SDK_INT >= 30) speed.stateDescription = text else speed.contentDescription = "Playback speed, $text"
    }

    private fun stopPlayback() {
        player?.let { if (it.isPlaying) it.pause(); it.seekTo(0) }
        playButton.text = "Play"
        handler.removeCallbacks(tick)
        position.progress = 0
    }

    private fun skip(ms: Int) {
        val p = player ?: return say("No recording selected.")
        val target = (p.currentPosition + ms).coerceIn(0, p.duration)
        p.seekTo(target)
        updatePosition()
        say("Position ${fmt(target / 1000)} of ${fmt(p.duration / 1000)}")
    }

    private fun updatePosition() {
        player?.let { position.progress = it.currentPosition / 1000 }
    }

    private fun fmt(seconds: Int) = if (seconds >= 60) "${seconds / 60} minutes ${seconds % 60} seconds" else "$seconds seconds"

    // ------------------------------------------------------------------ recordings

    private fun refreshRecordings(select: File?) {
        recordingFiles = app.recordingsDir.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val labels = if (recordingFiles.isEmpty()) listOf("No saved recordings")
            else recordingFiles.map { f ->
                val secs = ((f.length() - 44) / 48000).toInt()
                f.nameWithoutExtension.replace('_', ' ') + ", " + fmt(secs)
            }
        recordings.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        select?.let { s -> recordingFiles.indexOf(s).takeIf { it >= 0 }?.let { recordings.setSelection(it) } }
    }

    private fun selected(): File? = recordingFiles.getOrNull(recordings.selectedItemPosition)

    private fun openSelected() {
        val f = selected() ?: return say("There are no saved recordings.")
        load(f)
        say("Recording opened. Choose Play to listen.")
    }

    private fun deleteSelected() {
        val f = selected() ?: return say("There are no saved recordings.")
        AlertDialog.Builder(this)
            .setTitle("Delete recording")
            .setMessage("Delete ${f.nameWithoutExtension.replace('_', ' ')}?")
            .setPositiveButton("Delete") { _, _ ->
                if (currentFile == f) { player?.release(); player = null; currentFile = null; nowPlaying.text = "No recording selected." }
                f.delete(); refreshRecordings(null); say("Recording deleted.")
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun exportCurrent() {
        val f = currentFile ?: selected()
        if (f == null && currentUri == null) return say("No recording to export. Generate speech first.")
        if (f == null) return say("Only recordings generated by this app can be exported as WAV.")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, f.name)
        }, REQ_EXPORT)
        pendingExport = f
    }

    private var pendingExport: File? = null

    private fun pick(type: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); this.type = type
        }, code)
    }

    @Deprecated("Uses the platform activity result callback to avoid extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQ_REFERENCE -> app.importReference(uri, referenceText.text.toString())
                REQ_TXT -> {
                    val txt = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Cannot read the file")
                    require(txt.length <= 200_000) { "The text file is too large (more than 200,000 characters)." }
                    editor.setText(txt.removePrefix("﻿"))
                    say("Text imported: ${txt.length} characters.")
                }
                REQ_AUDIO -> {
                    stopPlayback(); player?.release(); player = null
                    currentFile = null; currentUri = uri
                    nowPlaying.text = "Selected: other audio file"
                    preparePlayer { p -> p.setDataSource(this, uri) }
                    say("Audio file opened. Choose Play to listen.")
                }
                REQ_EXPORT -> {
                    val src = pendingExport ?: return
                    contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                        ?: error("Cannot write to that location")
                    say("Recording exported as a WAV file.")
                }
            }
        } catch (e: Exception) {
            say("File operation failed: ${e.message ?: "unknown error"}")
        }
    }

    companion object {
        private const val REQ_TXT = 101
        private const val REQ_AUDIO = 102
        private const val REQ_EXPORT = 103
        private const val REQ_REFERENCE = 104
    }
}
