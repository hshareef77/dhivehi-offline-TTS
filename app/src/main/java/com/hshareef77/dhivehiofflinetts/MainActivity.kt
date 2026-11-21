package com.hshareef77.dhivehiofflinetts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.hussainshareef.dhivehiofflinetts.dhivehi.DhivehiPronunciation
import java.io.File
import java.util.Locale

/**
 * Phonetic Dhivehi experiment for any installed Android TTS voice.
 * For Eloquence trials, install/select Eloquence as the phone's preferred TTS engine.
 * It does not bundle an Eloquence voice or claim accurate Dhivehi pronunciation.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var speed: SeekBar
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generated: File? = null
    private val saveRequest = 202
    private val importTextRequest = 203

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(value: String): TextView = TextView(this).apply {
            text = value
            textSize = 17f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        fun action(name: String, handler: () -> Unit): Button = Button(this).apply {
            text = name
            setOnClickListener { handler() }
            minHeight = (56 * resources.displayMetrics.density).toInt()
        }
        root.addView(label("Dhivehi Pronunciation Lab"))
        root.addView(label("Type Thaana text. Uses your installed Android TTS voice, not a bundled Dhivehi voice."))
        editor = EditText(this).apply {
            hint = "މިތާ ލިޔުއްވާ"
            contentDescription = "Dhivehi text"
            minLines = 4
            setText("އައްސަލާމު ޢަލައިކުމް")
        }
        root.addView(editor)
        root.addView(action("Import text") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }, importTextRequest)
        })
        root.addView(action("Show pronunciation") { convert() })
        preview = label("Pronunciation preview appears here")
        preview.isFocusable = true
        root.addView(preview)
        root.addView(label("Voice speed"))
        speed = SeekBar(this).apply {
            max = 30
            progress = 10
            contentDescription = "Voice speed: 1.0"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    contentDescription = "Voice speed: ${(value + 5) / 15.0}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        root.addView(speed)
        root.addView(action("Speak") { speak(false) })
        root.addView(action("Stop") { tts?.stop(); report("Stopped") })
        root.addView(action("Generate audio") { speak(true) })
        root.addView(action("Open TTS settings") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) { report("Open Android Settings, Text-to-speech output, and select your preferred voice.") }
        })
        status = label("Initializing installed speech engine")
        status.isFocusable = true
        root.addView(status)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        tts = TextToSpeech(applicationContext) { result ->
            ui.post {
                ready = result == TextToSpeech.SUCCESS
                if (ready) {
                    // Latin phonetic approximations need a voice that can read Latin.
                    tts?.language = Locale.US
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "audio") ui.post {
                                if (!isFinishing && generated?.exists() == true) {
                                    report("Audio ready. Choose where to save.")
                                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_TITLE, "dhivehi-pronunciation.wav")
                                    }, saveRequest)
                                }
                            }
                        }
                        @Deprecated("Android TTS overload")
                        override fun onError(utteranceId: String?) {
                            ui.post { report("Speech failed. Check your installed TTS engine.") }
                        }
                    })
                    report("Voice ready. Select Eloquence in Android TTS settings to test its voice.")
                } else report("No working Android TTS engine found.")
            }
        }
    }

    private fun convert(): String {
        val input = editor.text.toString().trim()
        if (input.isEmpty()) {
            report("Enter some Dhivehi text.")
            return ""
        }
        val result = DhivehiPronunciation.transliterate(input)
        preview.text = result
        return result
    }

    private fun speak(toFile: Boolean) {
        if (!ready) return report("Speech engine not ready. Check TTS settings.")
        val phonetics = convert()
        if (phonetics.isEmpty()) return
        val engine = tts ?: return
        engine.setSpeechRate((speed.progress + 5) / 15f)
        if (toFile) {
            generated = File(cacheDir, "dhivehi-pronunciation.wav")
            val result = engine.synthesizeToFile(phonetics, Bundle(), generated!!, "audio")
            if (result != TextToSpeech.SUCCESS) report("Unable to start audio generation.")
            else report("Generating audio using installed voice.")
        } else {
            val result = engine.speak(phonetics, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
            if (result != TextToSpeech.SUCCESS) report("Unable to start speech.")
        }
    }

    private fun report(message: String) {
        status.text = message
        status.announceForAccessibility(message)
    }

    @Deprecated("ActivityResult callback for simple document actions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                importTextRequest -> {
                    val input = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    } ?: error("Cannot read file")
                    require(input.length <= 200_000) { "Text too long" }
                    editor.setText(input)
                    report("Text imported")
                }
                saveRequest -> {
                    val file = generated ?: error("No generated speech")
                    contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: error("Cannot save audio")
                    report("Audio saved")
                }
            }
        } catch (_: Exception) { report("Unable to open or save the selected file.") }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
