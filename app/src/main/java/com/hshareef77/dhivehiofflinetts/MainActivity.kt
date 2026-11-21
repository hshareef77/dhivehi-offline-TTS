package com.hshareef77.dhivehiofflinetts

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.hussainshareef.dhivehiofflinetts.dhivehi.DhivehiPronunciation
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Accessible experiment using an existing installed Android TTS engine. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var speed: SeekBar
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generated: File? = null
    private var requestSerial = 0
    private val corrections = linkedMapOf<String, String>()
    private val saveRequest = 202
    private val importTextRequest = 203

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCorrections()
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
            contentDescription = name
            minHeight = (56 * resources.displayMetrics.density).toInt()
            setOnClickListener { handler() }
        }
        root.addView(label("Dhivehi Pronunciation Lab"))
        editor = EditText(this).apply {
            hint = "މިތާ ލިޔުއްވާ"
            contentDescription = "Dhivehi text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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
        root.addView(action("Speak") { speak(false) })
        root.addView(action("Stop") { tts?.stop(); report("Stopped") })
        root.addView(action("Generate audio") { speak(true) })
        root.addView(action("Pronunciation preview") { convert() })
        preview = label("Converted pronunciation will appear here.")
        preview.isFocusable = true
        root.addView(preview)
        root.addView(action("Correct a word") { correctionDialog() })
        root.addView(label("Voice speed"))
        speed = SeekBar(this).apply {
            max = 30
            progress = 10
            contentDescription = "Voice speed 1.0"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    contentDescription = "Voice speed, ${"%.2f".format(Locale.US, (value + 5) / 15f)}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        root.addView(speed)
        root.addView(action("Android TTS settings") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) { report("Open Android Settings and search for Text-to-speech output.") }
        })
        status = label("Initializing selected TTS engine")
        status.isFocusable = true
        root.addView(status)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        tts = TextToSpeech(applicationContext) { result ->
            ui.post {
                ready = result == TextToSpeech.SUCCESS
                if (ready) {
                    val languageStatus = tts?.setLanguage(Locale.US)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("audio_") == true) ui.post {
                                if (!isFinishing && !isDestroyed && generated?.exists() == true) {
                                    report("Audio ready. Select a file location.")
                                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_TITLE, "dhivehi-phonetic.wav")
                                    }, saveRequest)
                                }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            ui.post { if (!isFinishing) report("Speech engine error. Try another voice.") }
                        }
                    })
                    if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
                    ) report("Installed TTS engine does not support an English phonetic voice.")
                    else report("Ready. Uses your currently selected Android TTS voice.")
                } else report("No functioning Android TTS voice. Check your device's TTS settings.")
            }
        }
    }

    private fun convert(): String {
        val input = editor.text.toString().trim()
        if (input.isEmpty()) {
            report("Enter Dhivehi text.")
            return ""
        }
        val result = DhivehiPronunciation.transliterate(input, corrections)
        preview.text = result
        return result
    }

    private fun speak(toFile: Boolean) {
        if (!ready) return report("Speech engine not ready.")
        val converted = convert()
        if (converted.isEmpty()) return
        // Some Android TTS implementations refuse inputs exceeding 4000 chars.
        if (converted.length >= TextToSpeech.getMaxSpeechInputLength()) {
            return report("Text too long. Please try a shorter passage.")
        }
        val engine = tts ?: return
        engine.stop()
        engine.setSpeechRate((speed.progress + 5) / 15f)
        requestSerial++
        val id = if (toFile) "audio_$requestSerial" else "preview_$requestSerial"
        if (toFile) {
            generated = File(cacheDir, "dhivehi-$requestSerial.wav")
            val rc = engine.synthesizeToFile(converted, Bundle(), generated!!, id)
            report(if (rc == TextToSpeech.SUCCESS) "Generating audio." else "Audio generation is unavailable with this engine.")
        } else {
            val rc = engine.speak(converted, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (rc != TextToSpeech.SUCCESS) report("Unable to start speech.")
        }
    }

    private fun correctionDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }
        val word = EditText(this).apply {
            hint = "Thaana word"
            contentDescription = "Dhivehi word to correct"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val sound = EditText(this).apply {
            hint = "How the English voice should say it"
            contentDescription = "Custom phonetic pronunciation"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        box.addView(word)
        box.addView(sound)
        AlertDialog.Builder(this)
            .setTitle("Correct pronunciation")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val key = word.text.toString().trim()
                val value = sound.text.toString().trim()
                if (!DhivehiPronunciation.containsThaana(key) || key.any { it.isWhitespace() } ||
                    key.length > 100 || value.isEmpty() || value.length > 200) {
                    report("Enter one Thaana word and its pronunciation.")
                } else {
                    corrections[key] = value
                    saveCorrections()
                    convert()
                    report("Pronunciation saved offline.")
                }
            }
            .setNeutralButton("Remove") { _, _ ->
                val key = word.text.toString().trim()
                corrections.remove(key)
                saveCorrections()
                report("Correction removed if present.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadCorrections() {
        val raw = getPreferences(MODE_PRIVATE).getString("pronunciations", "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.length <= 100) corrections[key] = obj.optString(key)
            }
        } catch (_: Exception) { corrections.clear() }
    }

    private fun saveCorrections() {
        val obj = JSONObject()
        for ((key, value) in corrections) obj.put(key, value)
        getPreferences(MODE_PRIVATE).edit().putString("pronunciations", obj.toString()).apply()
    }

    private fun report(message: String) {
        status.text = message
        status.announceForAccessibility(message)
    }

    @Deprecated("ActivityResult callbacks used for document picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                importTextRequest -> {
                    val value = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    } ?: error("Cannot read text")
                    require(value.length <= 200_000) { "Text too long" }
                    editor.setText(value)
                    report("Text imported.")
                }
                saveRequest -> {
                    val file = generated ?: error("No generated audio")
                    require(file.exists() && file.length() > 0) { "Generated file empty" }
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        file.inputStream().use { it.copyTo(stream) }
                    } ?: error("Cannot write file")
                    report("Audio saved.")
                }
            }
        } catch (_: Exception) { report("Unable to open or save that file.") }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
