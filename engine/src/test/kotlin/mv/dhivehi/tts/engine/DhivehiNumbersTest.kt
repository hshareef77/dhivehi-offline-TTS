package mv.dhivehi.tts.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DhivehiNumbersTest {
    private fun w(n: Long) = DhivehiNumbers.words(n)

    @Test
    fun smallNumbers() {
        assertEquals("ސުމެއް", w(0))
        assertEquals("އެކެއް", w(1))
        assertEquals("ދިހައެއް", w(10))
        assertEquals("އެގާރަ", w(11))
        assertEquals("ވިހި", w(20))
        assertEquals("ފަންސަވީސް", w(25))
        assertEquals("ތިރީސް", w(30))
        assertEquals("ތިރީސް އެކެއް", w(31))
        assertEquals("ނުވަދިހަ ނުވައެއް", w(99))
    }

    @Test
    fun largerNumbers() {
        assertEquals("ސަތޭކަ", w(100))
        assertEquals("ސަތޭކަ އެކެއް", w(101))
        assertEquals("ދުއިސައްތަ ފަންސާސް", w(250))
        assertEquals("ހާސް", w(1000))
        assertEquals("ދެހާސް ފަންސަވީސް", w(2025))
        assertEquals("އެގާރަ ހާސް", w(11000))
        assertEquals("ސަތޭކަ ފަންސާސް ހާސް", w(150000))
        assertEquals("އެއް މިލިއަން", w(1_000_000))
        assertEquals("ތިރީސް އެއް ހާސް ފަހެއް", w(31005))
    }

    @Test
    fun normalizeText() {
        assertEquals("ކުދިން އެގާރަ މީހުން", DhivehiNumbers.normalize("ކުދިން 11 މީހުން"))
        assertEquals("ދެހާސް ފަންސަވީސް ވަނަ އަހަރު", DhivehiNumbers.normalize("2025 ވަނަ އަހަރު"))
        assertEquals("ތިނެއް ޕޮއިންޓް ފަހެއް", DhivehiNumbers.normalize("3.5"))
        assertEquals("ފަންސާސް އިންސައްތަ", DhivehiNumbers.normalize("50%"))
        assertEquals("ހާސް ފަސްސަތޭކަ ރުފިޔާ", DhivehiNumbers.normalize("1,500 ރުފިޔާ"))
        assertEquals("ސުމެއް ހަތެއް ހަތެއް", DhivehiNumbers.normalize("077"))
        assertEquals("އެގާރަ", DhivehiNumbers.normalize("١١"))
        assertFalse(DhivehiNumbers.normalize("COVID-19 ބަލި").any { it.isDigit() })
    }

    @Test
    fun decimalIsNotASentenceEnd() {
        val parts = TextSegmenter.split(DhivehiNumbers.normalize("އަގު 3.5 ރުފިޔާ."))
        assertEquals(1, parts.size)
    }
}
