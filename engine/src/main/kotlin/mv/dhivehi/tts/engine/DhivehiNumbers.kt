package mv.dhivehi.tts.engine

/**
 * Writes numbers out as Dhivehi words before speech generation.
 *
 * The voice model was trained on text with numbers spelled out, so digits such as "11"
 * are otherwise skipped or misread. Rules (modern decimal Dhivehi counting):
 *  - 0–29 have their own words (އެގާރަ, ވިހި, ފަންސަވީސް …); other tens are combined with
 *    the unit: 31 = ތިރީސް އެކެއް.
 *  - hundreds ސަތޭކަ / ދުއިސައްތަ / ތިންސަތޭކަ …, thousands ހާސް / ދެހާސް …, then މިލިއަން, ބިލިއަން.
 *  - 2025 = ދެހާސް ފަންސަވީސް, 3.5 = ތިނެއް ޕޮއިންޓް ފަހެއް, 50% = ފަންސާސް އިންސައްތަ.
 *  - very long digit strings and numbers with a leading zero (phone numbers, codes) are read
 *    digit by digit.
 * The word lists are kept in one place so a native speaker can correct them easily.
 */
object DhivehiNumbers {
    /** Stand-alone forms 0–29. */
    private val small = arrayOf(
        "ސުމެއް", "އެކެއް", "ދޭއް", "ތިނެއް", "ހަތަރެއް", "ފަހެއް", "ހައެއް", "ހަތެއް", "އަށެއް", "ނުވައެއް",
        "ދިހައެއް", "އެގާރަ", "ބާރަ", "ތޭރަ", "ސާދަ", "ފަނަރަ", "ސޯޅަ", "ސަތާރަ", "އަށާރަ", "ނަވާރަ",
        "ވިހި", "އެކާވީސް", "ބާވީސް", "ތޭވީސް", "ސައުވީސް", "ފަންސަވީސް", "ސައްބީސް", "ހަތާވީސް", "އަށާވީސް", "ނަވާވީސް",
    )

    /** Forms used before a multiplier (ހާސް, ސަތޭކަ, މިލިއަން): 1–10. */
    private val combining = arrayOf("", "އެއް", "ދެ", "ތިން", "ހަތަރު", "ފަސް", "ހަ", "ހަތް", "އަށް", "ނުވަ", "ދިހަ")

    /** 30, 40, … 90. */
    private val tens = mapOf(
        3 to "ތިރީސް", 4 to "ސާޅީސް", 5 to "ފަންސާސް", 6 to "ފަސްދޮޅަސް",
        7 to "ހައްދިހަ", 8 to "އައްޑިހަ", 9 to "ނުވަދިހަ",
    )

    private val hundreds = arrayOf(
        "", "ސަތޭކަ", "ދުއިސައްތަ", "ތިންސަތޭކަ", "ހަތަރުސަތޭކަ", "ފަސްސަތޭކަ",
        "ހަސަތޭކަ", "ހަތްސަތޭކަ", "އަށްސަތޭކަ", "ނުވަސަތޭކަ",
    )

    private const val THOUSAND = "ހާސް"
    private const val MILLION = "މިލިއަން"
    private const val BILLION = "ބިލިއަން"
    private const val POINT = "ޕޮއިންޓް"
    private const val PERCENT = "އިންސައްތަ"

    /** Number 1..999 as words; [beforeMultiplier] uses combining forms (އެއް, ދެ …) for a final 1–10. */
    private fun below1000(n: Int, beforeMultiplier: Boolean): String {
        val parts = ArrayList<String>()
        val h = n / 100
        val r = n % 100
        if (h > 0) parts.add(hundreds[h])
        if (r > 0) {
            if (beforeMultiplier && r <= 10) parts.add(combining[r])
            else if (r < 30) parts.add(small[r])
            else {
                parts.add(tens.getValue(r / 10))
                val u = r % 10
                if (u > 0) parts.add(if (beforeMultiplier) combining[u] else small[u])
            }
        }
        return parts.joinToString(" ")
    }

    /** Cardinal number as Dhivehi words (0 ≤ n < 10^12). */
    fun words(n: Long): String {
        require(n >= 0 && n < 1_000_000_000_000L)
        if (n == 0L) return small[0]
        val parts = ArrayList<String>()
        val billions = (n / 1_000_000_000L).toInt()
        val millions = (n / 1_000_000L % 1000).toInt()
        val thousands = (n / 1000 % 1000).toInt()
        val rest = (n % 1000).toInt()
        if (billions > 0) parts.add(below1000(billions, true) + " " + BILLION)
        if (millions > 0) parts.add(below1000(millions, true) + " " + MILLION)
        if (thousands > 0) {
            parts.add(when {
                thousands == 1 -> THOUSAND
                thousands <= 10 -> combining[thousands] + THOUSAND // ދެހާސް, ތިންހާސް …
                else -> below1000(thousands, true) + " " + THOUSAND
            })
        }
        if (rest > 0) parts.add(below1000(rest, false))
        return parts.joinToString(" ")
    }

    private fun digitByDigit(s: String) = s.map { small[it - '0'] }.joinToString(" ")

    private val arabicIndic = Regex("[٠-٩۰-۹]")
    private val grouped = Regex("(?<![\\d.])\\d{1,3}(?:,\\d{3})+(?![\\d,])")
    private val number = Regex("(\\d+)(?:\\.(\\d+))?(\\s?%)?")

    /** Replaces every number in [text] with Dhivehi words. */
    fun normalize(text: String): String {
        var t = arabicIndic.replace(text) { m ->
            val c = m.value[0]
            (if (c in '٠'..'٩') c - '٠' else c - '۰').toString()
        }
        t = grouped.replace(t) { it.value.replace(",", "") }
        return number.replace(t) { m ->
            val int = m.groupValues[1]
            val frac = m.groupValues[2]
            val pct = m.groupValues[3].isNotEmpty()
            val spoken = when {
                int.length > 1 && int.startsWith("0") -> digitByDigit(int)
                int.length > 12 -> digitByDigit(int)
                else -> words(int.toLong())
            }
            val withFraction = if (frac.isNotEmpty()) "$spoken $POINT ${digitByDigit(frac)}" else spoken
            val result = if (pct) "$withFraction $PERCENT" else withFraction
            // keep numbers separated from neighbouring letters
            " $result "
        }.replace(Regex(" {2,}"), " ").trim()
    }
}
