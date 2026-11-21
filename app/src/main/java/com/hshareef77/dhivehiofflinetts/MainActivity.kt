package com.hshareef77.dhivehiofflinetts

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.speech.tts.Voice
import android.widget.SeekBar
import android.widget.TextView
import com.hussainshareef.dhivehiofflinetts.dhivehi.DhivehiPronunciation
import com.hussainshareef.dhivehiofflinetts.dhivehi.SpeechChunks
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Accessible experiment using an existing installed Android TTS engine. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var editor: EditText
    private lateinit var nativePreview: NativePreviewPlayer
    private val nativeExporter = NativeWaveExporter()
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var speed: SeekBar
    private lateinit var voiceList: Spinner
    private lateinit var engineList: Spinner
    private var availableEngines = listOf<TextToSpeech.EngineInfo>()
    private var availableVoices = listOf<Voice>()
    private var activeSpeechSerial = 0
    private var audioRequestId: String? = null
    private var savePending = false
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generated: File? = null
    private var requestSerial = 0
    private val corrections = linkedMapOf<String, String>()
    private val saveRequest = 202
    private val importTextRequest = 203

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativePreview = NativePreviewPlayer(this) { message -> report(message) }
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
            textDirection = android.view.View.TEXT_DIRECTION_RTL
            gravity = android.view.Gravity.END or android.view.Gravity.TOP
            setText("އައްސަލާމު ޢަލައިކުމް")
        }
        root.addView(editor)
        root.addView(action("Import text") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }, importTextRequest)
        })
        root.addView(action("Speak") {
            if (nativePreview.isInstalled) {
                val text = convert()
                if (text.isNotBlank()) nativePreview.play(text, (speed.progress + 5) / 15f)
            } else speak(false)
        })
        root.addView(action("Test native Eloquence voice") {
            val text = convert()
            if (text.isNotBlank()) nativePreview.play(text, (speed.progress + 5) / 15f)
        }
        root.addView(action("Stop") { nativePreview.stop(); nativeExporter.cancel(); tts?.stop(); audioRequestId = null; report("Stopped") })
        root.addView(action("Generate audio") {
            if (nativePreview.isInstalled) {
                val converted = convert()
                if (converted.isNotBlank()) generateNativeWav(converted)
            } else speak(true)
        }
        root.addView(action("Pronunciation preview") { convert() })
        root.addView(label("Speech engine"))
        engineList = Spinner(this).apply {
            contentDescription = "Select underlying speech engine"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Loading speech engines"))
        }
        root.addView(engineList)
        root.addView(action("Use selected engine") { selectEngine() })
        root.addView(label("Voice"))
        voiceList = Spinner(this).apply {
            contentDescription = "Select voice"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Loading voices"))
        }
        root.addView(voiceList)
        preview = label("Converted pronunciation will appear here.")
        preview.textDirection = android.view.View.TEXT_DIRECTION_LTR
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
        root.addView(action("Native engine status") {
            report(if (nativePreview.isInstalled) "Native Eloquence library loaded. Test the native voice." else
                "This APK does not contain the native Eloquence library. Use a native-enabled build.")
        })
        root.addView(action("Refresh voices") { populateVoices() })
        root.addView(action("Reload selected voice engine") { reloadEngine() })
        root.addView(action("Enable experimental system TTS") { configureSystemSpeech(true) })
        root.addView(action("Disable experimental system TTS") { configureSystemSpeech(false) })
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
        initializeTts()
    }

    private fun initializeTts() {
        ready = false
        val configuredPackage = getSharedPreferences("tts_config", MODE_PRIVATE)
            .getString("delegate_package", null)?.takeUnless { it == packageName }
        tts = TextToSpeech(applicationContext, { result ->
            ui.post {
                ready = result == TextToSpeech.SUCCESS
                if (ready) {
                    val languageStatus = tts?.setLanguage(Locale.US)
                    // Preserve the default provider before this wrapper is selected as default.
                    val provider = tts?.defaultEngine
                    val config = getSharedPreferences("tts_config", MODE_PRIVATE)
                    if (config.getString("delegate_package", null).isNullOrBlank() &&
                        !provider.isNullOrBlank() && provider != packageName) {
                        config.edit().putString("delegate_package", provider).apply()
                    }
                    populateEngines()
                    populateVoices()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null && utteranceId == audioRequestId) ui.post {
                                audioRequestId = null
                                if (!isFinishing && !isDestroyed && generated?.exists() == true && !savePending) {
                                    val candidate = generated!!
                                    if (!isWave(candidate)) {
                                        report("The installed TTS engine did not produce a valid WAV audio file.")
                                        return@post
                                    }
                                    savePending = true
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
                            ui.post {
                                if (!isFinishing) {
                                    if (utteranceId == audioRequestId) audioRequestId = null
                                    report("Speech engine error. Try another voice.")
                                }
                            }
                        }
                    })
                    if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
                    ) report("Installed TTS engine does not support an English phonetic voice.")
                    else report("Ready. Uses your currently selected Android TTS voice.")
                } else report("No functioning Android TTS voice. Check your device's TTS settings.")
            }
        }, configuredPackage)
    }

    private fun populateEngines() {
        val installed = tts?.engines.orEmpty().filter { it.name != packageName }
        availableEngines = installed.sortedBy { it.label?.toString() ?: it.name }
        engineList.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (availableEngines.isEmpty()) listOf("No compatible installed speech engine")
            else availableEngines.map { "${it.label} (${it.name})" })
        val configured = getSharedPreferences("tts_config", MODE_PRIVATE).getString("delegate_package", null)
        val selected = availableEngines.indexOfFirst { it.name == configured }
        if (selected >= 0) engineList.setSelection(selected)
    }

    private fun selectEngine() {
        val engine = availableEngines.getOrNull(engineList.selectedItemPosition)
            ?: return report("Install an offline speech engine first.")
        getSharedPreferences("tts_config", MODE_PRIVATE).edit()
            .putString("delegate_package", engine.name).apply()
        reloadEngine()
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

    private fun configureSystemSpeech(enable: Boolean) {
        if (enable) {
            val delegatePackage = getSharedPreferences("tts_config", MODE_PRIVATE)
                .getString("delegate_package", null)
            if (!nativePreview.isInstalled &&
                (!ready || delegatePackage.isNullOrBlank() || delegatePackage == packageName)) {
                report("Select and test a different installed offline speech engine first.")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Experimental system speech")
                .setMessage(
                    "This is an unverified pronunciation adapter, not a real Dhivehi voice. " +
                    "It may respond slowly or fail in screen readers. Keep your usual voice " +
                    "available and do not set this as TalkBack's default before testing."
                )
                .setPositiveButton("Enable") { _, _ -> setSystemEngineEnabled(true) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            setSystemEngineEnabled(false)
        }
    }

    private fun setSystemEngineEnabled(enabled: Boolean) {
        try {
            val component = ComponentName(this, DhivehiTtsService::class.java)
            packageManager.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            report(if (enabled)
                "Experimental system TTS enabled. Test cautiously in Android TTS settings."
                else "Experimental system TTS disabled.")
        } catch (_: Exception) {
            report("Unable to change experimental system speech setting.")
        }
    }

    private fun reloadEngine() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        availableVoices = emptyList()
        ready = false
        report("Reloading selected TTS engine.")
        initializeTts()
    }

    private fun isWave(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(12)
            val count = stream.read(header)
            count == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    } catch (_: Exception) { false }

    private fun generateNativeWav(converted: String) {
        nativeExporter.cancel()
        val path = File(cacheDir, "native-eloquence-${System.currentTimeMillis()}.wav")
        generated = path
        report("Generating native Eloquence audio.")
        nativeExporter.generate(converted, (speed.progress + 5) / 15f, path) { success, result ->
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (success && generated === path) {
                    savePending = true
                    report("Native WAV ready. Select where to save it.")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/wav"
                        putExtra(Intent.EXTRA_TITLE, "dhivehi-native-eloquence.wav")
                    }, saveRequest)
                } else report(result)
            }
        }
    }

    private fun populateVoices() {
        val engine = tts ?: return
        try {
            val list = engine.voices.orEmpty()
                .filter { !it.isNetworkConnectionRequired }
                .sortedWith(compareBy({ it.locale.language != "en" }, { it.name }))
            availableVoices = list
            voiceList.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (list.isEmpty()) listOf("No offline voice found")
                else list.map { "${it.name} (${it.locale.displayName})" })
            val remembered = getSharedPreferences("tts_config", MODE_PRIVATE).getString("voice", null)
            val selected = list.indexOfFirst { it.name == remembered }
            if (selected >= 0) voiceList.setSelection(selected)
        } catch (_: Exception) {
            availableVoices = emptyList()
            report("Unable to enumerate installed voices.")
        }
    }

    private fun applySelectedVoice(engine: TextToSpeech) {
        val voice = availableVoices.getOrNull(voiceList.selectedItemPosition) ?: return
        if (engine.setVoice(voice) == TextToSpeech.SUCCESS) {
            getSharedPreferences("tts_config", MODE_PRIVATE).edit().putString("voice", voice.name).apply()
        }
    }

    private fun speak(toFile: Boolean) {
        if (!ready) return report("Speech engine not ready.")
        val converted = convert()
        if (converted.isEmpty()) return
        val engine = tts ?: return
        engine.stop()
        applySelectedVoice(engine)
        engine.setSpeechRate((speed.progress + 5) / 15f)
        requestSerial++
        activeSpeechSerial = requestSerial
        audioRequestId = null
        if (toFile) {
            savePending = false
            if (converted.length >= TextToSpeech.getMaxSpeechInputLength()) {
                return report("Audio generation currently supports shorter passages. Use fewer than 4000 characters.")
            }
            generated = File(cacheDir, "dhivehi-$requestSerial.wav")
            val id = "audio_$requestSerial"
            audioRequestId = id
            val rc = engine.synthesizeToFile(converted, Bundle(), generated!!, id)
            report(if (rc == TextToSpeech.SUCCESS) "Generating audio." else "Audio generation unavailable with this engine.")
        } else {
            val maxLength = minOf(1800, TextToSpeech.getMaxSpeechInputLength() - 16)
            val chunks = SpeechChunks.split(converted, maxLength)
            var failed = false
            for ((index, chunk) in chunks.withIndex()) {
                val rc = engine.speak(
                    chunk,
                    if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    Bundle(),
                    "preview_${requestSerial}_$index"
                )
                if (rc != TextToSpeech.SUCCESS) { failed = true; break }
            }
            if (failed) report("Unable to queue speech in the selected engine.")
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
        val store = getSharedPreferences("pronunciations", MODE_PRIVATE)
        val old = getPreferences(MODE_PRIVATE).getString("pronunciations", "{}") ?: "{}"
        val raw = store.getString("pronunciations", old) ?: "{}"
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
        getSharedPreferences("pronunciations", MODE_PRIVATE).edit()
            .putString("pronunciations", obj.toString()).apply()
    }

    private fun report(message: String) {
        status.text = message
        status.announceForAccessibility(message)
    }

    @Deprecated("ActivityResult callbacks used for document picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == saveRequest) savePending = false
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
        nativePreview.stop()
        nativeExporter.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
