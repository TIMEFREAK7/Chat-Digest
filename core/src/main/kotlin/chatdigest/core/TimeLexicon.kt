package chatdigest.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Deterministic resolver for Hinglish / romanized-Marathi date, time and amount phrases.
 * The model copies phrases verbatim; this turns them into "phrase → Tue 6 Oct, 10:00".
 *
 * Matching rule: a token matches only an explicit spelling of an entry, after normalization
 * (lowercase, doubled vowels collapsed, dh→d, w→v, z→j). No edit-distance matching: on 7 Oct
 * Tier 0 data it produced ~1,440 fuzzy hits of which ~88% were wrong (need→dedh, kiti→koti,
 * adhi→adhai, baat→raat, sadhya→sandhya…). New spellings are added from the lexicon report.
 */
object TimeLexicon {

    data class Resolution(
        val phrase: String,
        /** null when nothing in the phrase could be resolved without guessing. */
        val resolved: String?,
        /** Ambiguities the reader must see (⚠). Empty when fully resolved. */
        val warnings: List<String>,
        /** message token → lexicon entry, so every match is auditable in the lexicon report. */
        val matches: List<Pair<String, String>>,
    ) {
        override fun toString() =
            "$phrase → ${resolved ?: "?"}" + if (warnings.isEmpty()) "" else " ⚠ " + warnings.joinToString("; ")
    }

    private sealed interface Cat
    private data class Day(val offset: Int, val ambiguous: Boolean) : Cat
    private data class Period(val name: String) : Cat
    private data class Frac(val delta: Double) : Cat      // savva +¼, saade +½, paune −¼
    private data class AbsFrac(val value: Double) : Cat   // dedh 1½, adich 2½
    private data class Mult(val factor: Long) : Cat
    private data object HourMarker : Cat

    private class Entry(val canonical: String, val cat: Cat, vararg spellings: String) {
        val forms = (spellings.toList() + canonical).map(::normalize).toSet()
    }

    private val ENTRIES = listOf(
        Entry("aaj", Day(0, false), "aj"),
        Entry("kal", Day(1, true), "kaal", "kl"),
        Entry("parso", Day(2, true), "parson"),
        Entry("parva", Day(2, true), "parwa"), // ponytail: treated as ambiguous like parso; confirm usage
        Entry("udya", Day(1, false), "udyaa", "udhya", "udhyaa", "udyla"),
        Entry("sakali", Period("morning"), "skali", "sakli", "sakaali"),
        Entry("sakal", Period("morning"), "skal"),
        Entry("subah", Period("morning"), "subha"),
        Entry("dupari", Period("afternoon"), "dupar", "dupaari"),
        Entry("dopahar", Period("afternoon"), "dopaher"),
        Entry("sandhyakali", Period("evening"), "sandhyakal", "sandhyakaali"),
        Entry("shaam", Period("evening"), "sham"),
        Entry("raatri", Period("night"), "ratri", "ratree"),
        Entry("raat", Period("night")),
        Entry("savva", Frac(0.25), "sawa", "sava"),
        Entry("saade", Frac(0.5), "saadhe", "sade", "sadhe"),
        Entry("paune", Frac(-0.25), "pavne", "pone"),
        Entry("dedh", AbsFrac(1.5), "dhed"),
        Entry("adich", AbsFrac(2.5), "adhich", "adeech"),
        Entry("adhai", AbsFrac(2.5), "dhaai", "dhai"),
        Entry("hazaar", Mult(1_000), "hajaar", "hazar", "hajar"),
        Entry("lakh", Mult(100_000), "lac", "laakh"),
        Entry("crore", Mult(10_000_000), "cr", "karod", "koti"),
        Entry("la", HourMarker, "laa"),
        Entry("baje", HourMarker, "bje"),
        Entry("vajta", HourMarker, "vaje", "vajata", "vajle"),
    )

    private val FUTURE_WORDS = setOf(
        "will", "hoga", "hogi", "honge", "yeil", "jail", "karu", "karin", "karel", "bhetu", "milenge", "aaunga",
        "tomorrow",
    )
    private val FUTURE_SUFFIXES = listOf("ega", "egi", "enge", "unga", "ungi", "oge", "ogi", "nar")
    private val PAST_WORDS = setOf(
        "was", "were", "did", "yesterday", "tha", "thi", "gaya", "gayi", "gaye", "kiya", "hua", "hui",
        "hota", "hoti", "hote", "gela", "geli", "gele", "kela", "keli", "aala", "aali", "zala", "jhala", "aaya",
    )

    private val TOKEN = Regex("""\d+(?:[:.]\d{2})?|\p{L}+""")
    private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun resolve(text: String, at: LocalDateTime): List<Resolution> {
        val src = devanagariDigitsToAscii(text)
        val tokens = TOKEN.findAll(src).map { it.value to it.range }.toList()
        val cats = tokens.map { (t, _) -> lookup(t) }
        val lower = tokens.map { it.first.lowercase() }
        val tense = tenseOf(lower)

        val out = mutableListOf<Resolution>()
        var i = 0
        while (i < tokens.size) {
            if (!startsPhrase(tokens, cats, i)) { i++; continue }
            var j = i
            while (j + 1 < tokens.size && continuesPhrase(tokens, cats, j + 1)) j++
            val run = (i..j).toList()
            val hasSignal = run.any { k ->
                val c = cats[k]?.second
                c is Day || c is Period || c is Mult || c is AbsFrac ||
                    (c is Frac && k + 1 <= j) ||
                    (c == HourMarker && k > i && isNum(tokens[k - 1].first))
            }
            if (hasSignal) out += evaluate(src, tokens, cats, run, at, tense)
            i = j + 1
        }
        return out
    }

    private fun isNum(t: String) = t.first().isDigit()

    private fun startsPhrase(tokens: List<Pair<String, IntRange>>, cats: List<Pair<String, Cat>?>, k: Int) =
        isNum(tokens[k].first) || (cats[k] != null && cats[k]!!.second != HourMarker)

    private fun continuesPhrase(tokens: List<Pair<String, IntRange>>, cats: List<Pair<String, Cat>?>, k: Int) =
        isNum(tokens[k].first) || cats[k] != null && (cats[k]!!.second != HourMarker || isNum(tokens[k - 1].first))

    private fun evaluate(
        src: String,
        tokens: List<Pair<String, IntRange>>,
        cats: List<Pair<String, Cat>?>,
        run: List<Int>,
        at: LocalDateTime,
        tense: Int,
    ): Resolution {
        val phrase = src.substring(tokens[run.first()].second.first, tokens[run.last()].second.last + 1)
        val matches = run.mapNotNull { k -> cats[k]?.let { tokens[k].first to it.first } }
        val warnings = mutableListOf<String>()
        val c = run.map { cats[it]?.second }
        val nums = run.filter { isNum(tokens[it].first) }.map { tokens[it].first }

        val mult = c.filterIsInstance<Mult>().firstOrNull()
        val frac = c.filterIsInstance<Frac>().firstOrNull()?.delta ?: 0.0
        val abs = c.filterIsInstance<AbsFrac>().firstOrNull()?.value

        if (mult != null) {
            val base = abs ?: nums.firstOrNull()?.replace(':', '.')?.toDoubleOrNull()?.plus(frac)
                ?: return Resolution(phrase, null, listOf("amount without number"), matches)
            val value = Math.round(base * mult.factor)
            return Resolution(phrase, value.toString(), warnings, matches)
        }

        val parts = mutableListOf<String>()
        val dayAt = c.indexOfFirst { it is Day }
        if (dayAt >= 0) {
            val d = c[dayAt] as Day
            val sign = if (d.ambiguous) tense else 1
            if (sign == 0) warnings += "${tokens[run[dayAt]].first}: past or future?"
            else parts += at.toLocalDate().plusDays((sign * d.offset).toLong()).fmt()
        }

        val period = c.filterIsInstance<Period>().firstOrNull()?.name
        val hour = abs ?: nums.firstOrNull()?.let { n ->
            val (h, m) = n.split(':', '.').map { it.toInt() } + listOf(0)
            if (h > 24 || m > 59) null else h + m / 60.0
        }?.plus(frac)
        when {
            hour == null && period != null -> parts += period
            hour != null && period != null -> parts += clock(hour, period)
            hour != null && c.any { it == HourMarker || it is Frac || it is AbsFrac } -> warnings += "am or pm?"
        }
        return Resolution(phrase, parts.joinToString(", ").ifEmpty { null }, warnings, matches)
    }

    private fun clock(hour: Double, period: String): String {
        var h = hour
        when (period) {
            "afternoon" -> if (h <= 6) h += 12
            "evening" -> if (h < 12) h += 12
            "night" -> if (h in 5.0..11.99) h += 12 else if (h >= 12 && h < 13) h -= 12
        }
        val mins = Math.round(h * 60).toInt()
        return "%02d:%02d".format(mins / 60 % 24, mins % 60)
    }

    private fun LocalDate.fmt() = format(DATE_FMT)

    /** +1 future, −1 past, 0 unknown or conflicting. */
    private fun tenseOf(words: List<String>): Int {
        val fut = words.any { it in FUTURE_WORDS || (it.length > 4 && FUTURE_SUFFIXES.any(it::endsWith)) }
        val past = words.any { it in PAST_WORDS }
        return if (fut == past) 0 else if (fut) 1 else -1
    }

    private fun lookup(token: String): Pair<String, Cat>? {
        if (token.first().isDigit()) return null
        val n = normalize(token)
        return ENTRIES.firstOrNull { n in it.forms }?.let { it.canonical to it.cat }
    }

    fun normalize(s: String): String {
        var t = s.lowercase().replace("dh", "d").replace("w", "v").replace("z", "j")
        t = t.replace(Regex("([aeiou])\\1+"), "$1")
        return t
    }

    fun devanagariDigitsToAscii(s: String) =
        String(CharArray(s.length) { i -> s[i].let { if (it in '०'..'९') '0' + (it - '०') else it } })
}
