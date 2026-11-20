package mv.dhivehi.tts.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class OmniVoiceTest {
    @Test fun referenceWavRejectsInvalidAndResamplesStereo() {
        assertFailsWith<IllegalArgumentException> { ReferenceWav.read(byteArrayOf(1, 2).inputStream()) }
        val file = File.createTempFile("reference", ".wav")
        try {
            WavWriter(file, 16000).use { it.write(FloatArray(48000) { i -> (kotlin.math.sin(i * .03) * .1).toFloat() }) }
            val audio = file.inputStream().use { ReferenceWav.read(it) }
            assertEquals(72000, audio.size)
            assertTrue(audio.all { it.isFinite() })
        } finally { file.delete() }
    }
    @Test fun referenceVoiceRoundTripsAndValidates() {
        val file = File.createTempFile("voice", ".json")
        try {
            val voice = OmniVoiceEngine.Voice("މިއަދު", Array(8) { LongArray(75) { 1L } }, .08f)
            voice.save(file)
            val loaded = OmniVoiceEngine.Voice.load(file)
            assertEquals(voice.text, loaded.text)
            assertEquals(75, loaded.frames)
            assertContentEquals(voice.codes[7], loaded.codes[7])
        } finally { file.delete() }
    }
    @Test fun realTokenizerAndGenerationMatchIndependentPython() {
        val model = System.getProperty("omni.model.dir")?.let(::File) ?: return
        val reference = File(System.getProperty("omni.reference.dir") ?: error("Reference dir missing"))
        val tokenizer = OmniTokenizer(File(model, "tokenizer.json"))
        val cases = JSONArray(File(reference, "tokenizer_cases.json").readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val a = c.getJSONArray("ids")
            assertEquals((0 until a.length()).map { a.getInt(it) }, tokenizer.encode(c.getString("text")))
        }
        val expected = JSONObject(File(reference, "expected_codes.json").readText())
        OmniVoiceEngine(model, 3).use { engine ->
            assertFailsWith<GenerationCancelledException> {
                engine.generateCodes(expected.getString("text"), listener = object : GenerationListener {
                    override fun isCancelled() = true
                })
            }
            // The published legacy encoder only accepted one second; check a 3.28 s clip.
            val referenceAudio = FloatArray(78720) { i -> (kotlin.math.sin(i * .04) * .1).toFloat() }
            val cloned = engine.createVoice(referenceAudio, "މިއަދު މޫސުން ރަނގަޅު.")
            assertEquals(82, cloned.frames)
            val codes = engine.generateCodes(expected.getString("text"), positionTemperature = 0.0)
            val a = expected.getJSONArray("codes")
            for (c in 0 until 8) {
                val row = a.getJSONArray(c)
                assertContentEquals(LongArray(row.length()) { row.getLong(it) }, codes[c], "Codebook $c")
            }
            val audio = engine.decode(codes)
            assertTrue(audio.isNotEmpty() && audio.all { it.isFinite() } && audio.any { kotlin.math.abs(it) > 1e-6 })
            val out = File(reference, "kotlin_dhivehi.wav")
            WavWriter(out, 24000).use { it.write(audio) }
            val cloneExpected = JSONObject(File(reference, "expected_clone_codes.json").readText())
            val testVoice = OmniVoiceEngine.Voice.load(File(reference, "test_voice.json"))
            val cloneCodes = engine.generateCodes(cloneExpected.getString("text"), testVoice, positionTemperature = 0.0)
            val cloneArray = cloneExpected.getJSONArray("codes")
            for (c in 0 until 8) {
                val row = cloneArray.getJSONArray(c)
                assertContentEquals(LongArray(row.length()) { row.getLong(it) }, cloneCodes[c], "Cloned codebook $c")
            }
            println("Kotlin OmniVoice exact-code parity passed; ${audio.size / 24000.0} seconds of speech.")
        }
    }
}
