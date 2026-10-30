package mv.dhivehi.tts.engine

import org.json.JSONArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-checks the Kotlin engine against the Python reference (conversion/make_reference.py).
 *
 * Run with: gradle test -Dcsm.model.dir=<package> -Dcsm.reference.dir=<reference dir>
 * Tests are skipped when the properties are not set.
 */
class CsmEngineTest {
    private val modelDir = System.getProperty("csm.model.dir")?.let(::File)
    private val refDir = System.getProperty("csm.reference.dir")?.let(::File)
    private val outDir = System.getProperty("csm.output.dir")?.let(::File)

    @Test
    fun tokenizerMatchesHuggingFace() {
        if (modelDir == null || refDir == null) return println("SKIPPED: csm.model.dir / csm.reference.dir not set")
        val tok = BpeTokenizer.load(File(modelDir, "tokenizer"))
        val cases = JSONArray(File(refDir, "tokenizer_cases.json").readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = c.getJSONArray("ids").let { a -> List(a.length()) { a.getInt(it) } }
            assertEquals(expected, tok.encode(c.getString("text")), "tokenization of: ${c.getString("text")}")
        }
        println("tokenizer: ${cases.length()} cases identical to Hugging Face tokenizers")
    }

    @Test
    fun greedyGenerationMatchesPythonPipeline() {
        if (modelDir == null || refDir == null) return println("SKIPPED: csm.model.dir / csm.reference.dir not set")
        CsmEngine(modelDir).use { engine ->
            val refs = JSONArray(File(refDir, "reference.json").readText())
            for (i in 0 until refs.length()) {
                val r = refs.getJSONObject(i)
                val text = r.getString("text")
                val speaker = r.getInt("speaker")
                val ids = r.getJSONArray("prompt_ids").let { a -> List(a.length()) { a.getInt(it) } }
                assertEquals(ids, engine.promptTokens(text, speaker), "prompt ids")
                val refFrames = r.getJSONArray("frames")
                val t0 = System.nanoTime()
                val frames = engine.generateFrames(text, speaker, GenerationOptions(maxFrames = refFrames.length(), greedy = true))
                val secs = (System.nanoTime() - t0) / 1e9
                assertEquals(refFrames.length(), frames.size, "frame count")
                var same = 0
                var total = 0
                for (f in frames.indices) {
                    val rf = refFrames.getJSONArray(f)
                    for (c in 0 until rf.length()) { total++; if (rf.getInt(c) == frames[f][c]) same++ }
                }
                val match = same.toDouble() / total
                println("case $i: ${frames.size} frames in %.2fs, token match %.4f".format(secs, match))
                // Exact float equality is not guaranteed across ORT builds; near-ties may flip.
                assertTrue(match >= 0.98, "greedy token match $match")
                val audio = engine.decode(frames)
                assertEquals(r.getInt("samples"), audio.size, "decoded sample count")
                assertTrue(audio.all { it.isFinite() }, "audio must be finite")
            }
        }
    }

    @Test
    fun longTextToWavWithAllSpeakers() {
        if (modelDir == null) return println("SKIPPED: csm.model.dir not set")
        val out = (outDir ?: File(System.getProperty("java.io.tmpdir"), "csm-test")).also { it.mkdirs() }
        CsmEngine(modelDir).use { engine ->
            val synth = LongTextSynthesizer(engine)
            val text = "ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ. މާލެ އަކީ ވެރިރަށެވެ."
            for (sp in engine.speakers) {
                val file = File(out, "kotlin_speaker_${sp.id}.wav")
                val t0 = System.nanoTime()
                val res = synth.synthesizeToWav(text, sp.id, file, seed = 42)
                val secs = (System.nanoTime() - t0) / 1e9
                println("speaker ${sp.id} (${sp.name}): ${res.segments} segments, %.2f s audio, generated in %.1f s -> ${file.path}"
                    .format(res.seconds, secs))
                assertEquals(2, res.segments)
                assertTrue(file.length() > 44)
            }
        }
    }

    @Test
    fun cancellationStopsGeneration() {
        if (modelDir == null) return println("SKIPPED: csm.model.dir not set")
        CsmEngine(modelDir).use { engine ->
            var seen = 0
            try {
                engine.generateFrames("ދިވެހި", 0, GenerationOptions(maxFrames = 50), object : GenerationListener {
                    override fun onFrame(frames: Int, maxFrames: Int) { seen = frames }
                    override fun isCancelled() = seen >= 2
                })
                error("expected cancellation")
            } catch (e: GenerationCancelledException) {
                assertEquals(2, seen)
            }
        }
    }
}
