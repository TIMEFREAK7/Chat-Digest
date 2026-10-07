package chatdigest.core

/**
 * Post-generation check: every number, name and quote in a summary must be traceable to what the
 * model was given. Anything that isn't is returned so the summary can be marked ⚠ unverified.
 *
 * Catches: changed numbers ("10 pax" → "1 pax"), garbled dates ("2191 Sept"), corrupted names
 * ("PEPSICO" → "PSICO"), invented quotes. Does NOT catch a wrong meaning built from real words
 * (a mistranslation, a real statement credited to the wrong person) — hand-scoring covers that.
 */
object Verifier {

    data class Result(val failed: List<String>) {
        val verified get() = failed.isEmpty()
        override fun toString() = if (verified) "VERIFIED" else "⚠ UNVERIFIED: " + failed.joinToString(", ")
    }

    private val NUMBER = Regex("""\d+(?:[.:/,]\d+)*""")
    private val WORD = Regex("""\p{L}+""")
    private val TOKEN = Regex("""\p{L}+|\d+(?:[.:/,]\d+)*""")
    private val QUOTE = Regex("""["“”]([^"“”]{3,})["“”]""")
    private val CAPITALIZED = Regex("""\b\p{Lu}\p{L}{2,}\b""")
    private val ACRONYM = Regex("""\b\p{Lu}{2,}\b""")
    // Starts a sentence/item, so a capital letter there says nothing about being a name.
    private val SENTENCE_START = Regex("""(^|[.!?:*•\-–>→(\[“"]\s*)$""")
    private val CONTRACT_WORDS = setOf("decisions", "asks", "action", "items", "questions", "directed", "key", "facts", "other", "media")

    /** [source] = every line the model saw (chat name, reader names, media note, rendered messages). */
    fun verify(summary: String, source: List<String>): Result {
        val lines = source.map(::clean)
        val vocab = lines.flatMap { words(it) }.toSet() + CONTRACT_WORDS
        val joined = lines.joinToString("\n").replace(Regex("\\s+"), " ")
        val failed = linkedSetOf<String>()

        for (line in summary.lines().map(::clean)) {
            for (m in NUMBER.findAll(line)) {
                val n = m.value.replace(",", "")
                // Dates, times, phone numbers and 4+ digit numbers are specific enough on their own.
                // Short bare numbers ("1", "10") also need the same neighbouring word or number in that
                // source line, otherwise "10 pax" → "1 pax" would pass because "Plant 1" exists.
                val specific = n.length >= 4 || n.any { !it.isDigit() }
                val left = TOKEN.findAll(line.substring(0, m.range.first)).lastOrNull()?.value?.lowercase()
                val right = TOKEN.find(line, m.range.last + 1)?.value?.lowercase()
                val ok = lines.any { src ->
                    NUMBER.findAll(src).any { it.value.replace(",", "") == n } &&
                        (specific || left == null && right == null || tokens(src).let { t -> left in t || right in t })
                }
                if (!ok) failed += m.value
            }
            for (m in QUOTE.findAll(line)) {
                val q = m.groupValues[1].trim().replace(Regex("\\s+"), " ")
                if (q.split(' ').size >= 2 && !joined.contains(q, ignoreCase = true)) failed += "\"$q\""
            }
            for (m in CAPITALIZED.findAll(line) + ACRONYM.findAll(line)) {
                val acronym = m.value.all { it.isUpperCase() }
                if (!acronym && SENTENCE_START.containsMatchIn(line.substring(0, m.range.first))) continue
                if (m.value.lowercase() !in vocab) failed += m.value
            }
        }
        return Result(failed.toList())
    }

    private fun tokens(s: String) = TOKEN.findAll(s).map { it.value.lowercase() }.toSet()

    private fun words(s: String) = WORD.findAll(s).map { it.value.lowercase() }.toSet()

    private fun clean(s: String) =
        TimeLexicon.devanagariDigitsToAscii(s).replace("⁨", "").replace("⁩", "")
}
