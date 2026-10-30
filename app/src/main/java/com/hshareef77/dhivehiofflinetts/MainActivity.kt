package com.hshareef77.dhivehiofflinetts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.InputType

/**
 * Accessible first-stage UI. No remote speech service, no fake offline generation.
 * A real tested offline CSM-compatible runtime is required before enabling Generate.
 */
class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var voice: Spinner

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(value: String): TextView = TextView(this).apply {
            text = value
            textSize = 18f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).apply {
            text = value
            contentDescription = value
            setOnClickListener { action() }
        }
        column.addView(label("Dhivehi Offline Text to Speech"))
        column.addView(label("Enter or import Dhivehi text"))
        input = EditText(this).apply {
            hint = "މިތާ ލިޔުއްވާ"
            contentDescription = "Dhivehi text input"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            setHorizontallyScrolling(false)
        }
        column.addView(input)
        column.addView(button("Import TXT file") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(intent, 101)
        })
        column.addView(label("Choose voice"))
        voice = Spinner(this).apply {
            contentDescription = "Choose one of five Dhivehi voices"
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Speaker 0 — Female", "Speaker 1 — Male",
                       "Speaker 2 — Female", "Speaker 3 — Male", "Speaker 4 — Male (verify quality)")
            )
        }
        column.addView(voice)
        status = label("Voice model not installed. Offline synthesis is not yet implemented.")
        status.setFocusable(true)
        column.addView(status)
        column.addView(button("Generate speech (not yet available)") {
            status.text = "Speech generation unavailable: the CSM model has not been converted and tested for offline Android."
            status.announceForAccessibility(status.text)
        })
        column.addView(label("This prototype does not download a multi-gigabyte model or send your text to a server."))
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    @Deprecated("Legacy activity result for minimal Android starter without dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 101 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Unable to read document")
            if (text.length > 200_000) throw IllegalArgumentException("Text file too large for the first-stage editor")
            input.setText(text)
            status.text = "Text imported. Speech engine is not yet installed."
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot import TXT: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
