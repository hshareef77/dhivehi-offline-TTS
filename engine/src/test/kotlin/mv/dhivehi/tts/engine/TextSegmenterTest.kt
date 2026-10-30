package mv.dhivehi.tts.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextSegmenterTest {
    @Test
    fun splitsSentencesAndRespectsLimit() {
        val text = "ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ. މާލެ އަކީ ވެރިރަށެވެ؟\n\nއައްސަލާމު ޢަލައިކުމް!"
        assertEquals(
            listOf("ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ.", "މާލެ އަކީ ވެރިރަށެވެ؟", "އައްސަލާމު ޢަލައިކުމް!"),
            TextSegmenter.split(text),
        )
        val long = (1..80).joinToString(" ") { "ބަސް$it" } + "، " + (1..40).joinToString(" ") { "ލަފްޒު$it" } + "."
        val parts = TextSegmenter.split(long, 160)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 160 }, parts.toString())
        assertEquals(long.replace("،", "").replace(" ", ""), parts.joinToString("").replace("،", "").replace(" ", ""))
    }

    @Test
    fun dropsPunctuationOnlySegments() {
        assertEquals(listOf("ދިވެހި."), TextSegmenter.split("... ! ދިވެހި. ؟"))
    }

    @Test
    fun wavHeaderIsValid() {
        val f = File.createTempFile("wavtest", ".wav")
        WavWriter(f, 24000).use { it.write(FloatArray(2400) { 0.5f }); it.writeSilence(0.1f) }
        val b = f.readBytes()
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals(44 + 4800 * 2, b.size)
        f.delete()
    }

    @Test
    fun samplerHonoursTopKAndValidRange() {
        val logits = FloatArray(2051) { if (it >= 2048) 100f else it.toFloat() / 100f }
        val s = Sampler(0.9f, 5, 1L)
        repeat(200) { val t = s.sample(logits); assertTrue(t in 2043..2047, "token $t") }
        assertEquals(2047, Sampler(0.9f, 50, 1L, greedy = true).sample(logits))
    }
}
