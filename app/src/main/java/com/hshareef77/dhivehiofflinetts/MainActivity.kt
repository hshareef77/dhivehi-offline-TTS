package com.hshareef77.dhivehiofflinetts

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputType
import android.widget.*
import java.io.File

/**
 * Accessible development prototype. Speech synthesis is deliberately disabled until an
 * on-device CSM backend (including Mimi decoding) is verified on Android.
 */
class MainActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var voices: Spinner
    private lateinit var speed: SeekBar
    private var player: MediaPlayer? = null
    private var audioUri: Uri? = null
    private val importTxtCode = 101
    private val importAudioCode = 102
    private val exportAudioCode = 103

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun caption(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 18f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        fun button(label: String, onPress: () -> Unit): Button = Button(this).apply {
            text = label
            contentDescription = label
            setOnClickListener { onPress() }
        }
        root.addView(caption("Dhivehi Offline TTS — Development Prototype"))
        root.addView(caption("Enter or import Dhivehi text"))
        editor = EditText(this).apply {
            hint = "މިތާ ލިޔުއްވާ"
            contentDescription = "Dhivehi text editor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
        }
        root.addView(editor)
        root.addView(button("Import UTF-8 TXT") { pickFile("text/plain", importTxtCode) })
        root.addView(caption("Speaker"))
        voices = Spinner(this).apply {
            contentDescription = "Dhivehi speaker choice"
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Female speaker 1", "Male speaker 1", "Female speaker 2",
                    "Male speaker 2", "Male speaker 3")
            )
        }
        root.addView(voices)
        status = caption("The offline speech engine is not installed or verified. Audio generation is unavailable.")
        status.isFocusable = true
        root.addView(status)
        root.addView(button("Generate speech — not available yet") {
            report("The 6.6 GB CSM model has not been converted and tested for local Android speech generation.")
        })
        root.addView(caption("Audio playback testing (existing WAV or other Android-supported audio files)"))
        root.addView(button("Open audio file") { pickFile("audio/*", importAudioCode) })
        root.addView(button("Play") { play() })
        root.addView(button("Pause") {
            player?.let { if (it.isPlaying) it.pause() }
            report("Playback paused")
        })
        root.addView(button("Stop") {
            player?.let { if (it.isPlaying) it.pause(); it.seekTo(0) }
            report("Playback stopped")
        })
        root.addView(caption("Playback speed, 0.5 to 2.0 times"))
        speed = SeekBar(this).apply {
            max = 15
            progress = 5
            contentDescription = "Playback speed, 1.0 times"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = (progress + 5) / 10f
                    contentDescription = "Playback speed, ${value} times"
                    try { player?.let { it.playbackParams = it.playbackParams.setSpeed(value) } } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(speed)
        root.addView(button("Export selected audio file") {
            if (audioUri == null) report("Open an audio file first.")
            else startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, "dhivehi-audio.wav")
            }, exportAudioCode)
        })
        root.addView(caption("This prototype does not generate speech or download the model. Playback can be tested offline using existing audio files."))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun report(message: String) {
        status.text = message
        status.announceForAccessibility(message)
    }

    private fun pickFile(type: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
        }, code)
    }

    private fun play() {
        val uri = audioUri ?: return report("Open an audio file first.")
        try {
            if (player == null) {
                player = MediaPlayer().apply {
                    setDataSource(this@MainActivity, uri)
                    prepare()
                    setOnCompletionListener { report("Playback complete") }
                }
            }
            player?.start()
            player?.let { it.playbackParams = it.playbackParams.setSpeed((speed.progress + 5) / 10f) }
            report("Playing audio")
        } catch (e: Exception) {
            player?.release()
            player = null
            report("Unable to play this audio file.")
        }
    }

    @Deprecated("Minimal prototype uses Activity result callbacks without external dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                importTxtCode -> {
                    val txt = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    } ?: error("Cannot read text")
                    require(txt.length <= 200_000) { "Text file too large" }
                    editor.setText(txt)
                    report("Dhivehi text imported. Speech generation is not yet available.")
                }
                importAudioCode -> {
                    player?.release()
                    player = null
                    audioUri = uri
                    report("Audio selected. Activate Play to listen offline.")
                }
                exportAudioCode -> {
                    val source = audioUri ?: error("No selected audio file")
                    contentResolver.openInputStream(source).use { input ->
                        contentResolver.openOutputStream(uri).use { output ->
                            requireNotNull(input) { "Cannot read source" }
                            requireNotNull(output) { "Cannot write destination" }
                            input.copyTo(output)
                        }
                    }
                    report("Audio copied to your selected destination in its original format.")
                }
            }
        } catch (e: Exception) {
            report("File operation failed: ${e.message ?: "Unknown error"}")
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
