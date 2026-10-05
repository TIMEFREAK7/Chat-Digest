package chatdigest.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Deterministic resolver for Hinglish / romanized-Marathi date, time and amount phrases.
 * The model copies phrases verbatim; this turns them into "phrase → Tue 6 Oct, 10:00".
 *
 * Matching rule: message tokens shorter than 4 chars match only an explicit variant list;
 * tokens of 4+ chars match after normalization with edit distance ≤ 1.
 */
object TimeLexicon {

    data class Resolution(
        val phrase: String,
        /** null when nothing in the phrase could be resolved without guessing. */
        val resolved: String?,
        /** Ambiguities the reader must see (⚠). Empty when fully resolved. */
        val warnings: List<String>,
        /** message token → lexicon entry, so fuzzy hits are auditable in Tier 0. */
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

    private class Entry(val canonical: String, val cat: Cat, val shortVariants: Set<String> = emptySet())

    private val ENTRIES = listOf(
        Entry("aaj", Day(0, false), setOf("aaj", "aj")),
        Entry("kal", Day(1, true), setOf("kal", "kaal", "kl")),
        Entry("parso", Day(2, true)),
        Entry("parva", Day(2, true)), // ponytail: parva treated as ambiguous like parso; confirm usage in Tier 0
        Entry("udya", Day(1, false)),
        Entry("sakali", Period("morning")), Entry("sakal", Period("morning")), Entry("subah", Period("morning")),
        Entry("dupari", Period("afternoon")), Entry("dopahar", Period("afternoon")),
        Entry("sandhyakali", Period("evening")), Entry("sandhya", Period("evening")),
        Entry("shaam", Period("evening"), setOf("sam")),
        Entry("raatri", Period("night")), Entry("raat", Period("night")),
        Entry("savva", Frac(0.25), setOf("sva")), Entry("sawa", Frac(0.25)),
        Entry("saade", Frac(0.5)), Entry("saadhe", Frac(0.5)),
        Entry("paune", Frac(-0.25)), Entry("pavne", Frac(-0.25)),
        Entry("dedh", AbsFrac(1.5)), Entry("adich", AbsFrac(2.5)), Entry("dhaai", AbsFrac(2.5)), Entry("adhai", AbsFrac(2.5)),
        Entry("hazaar", Mult(1_000)), Entry("hajaar", Mult(1_000)),
        Entry("lakh", Mult(100_000), setOf("lac", "lk")),
        Entry("crore", Mult(10_000_000), setOf("cr")), Entry("karod", Mult(10_000_000)), Entry("koti", Mult(10_000_000)),
        Entry("la", HourMarker, setOf("la", "laa")),
        Entry("baje", HourMarker, setOf("bje", "baj")),
        Entry("vaje", HourMarker), Entry("vajta", HourMarker), Entry("vajata", HourMarker),
    )

    /** English words one edit away from a 4+ char entry. Grown from Tier 0 misfires. */
    private val ENGLISH_BLOCK = setOf(
        "same", "safe", "sale", "side", "sake", "shame", "sham", "rate", "rant", "rapt", "lack", "lake",
        "lace", "bake", "bike", "save", "sava", "sakes", "sandy", "pause", "core", "crores", "dead", "deed",
        "seed", "load", "lead", "subs", "pune",
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
        val t = token.lowercase()
        if (t.first().isDigit()) return null
        ENTRIES.firstOrNull { t in it.shortVariants || t == it.canonical }?.let { return it.canonical to it.cat }
        if (t.length < 4 || t in ENGLISH_BLOCK) return null
        val n = normalize(t)
        return ENTRIES.filter { it.canonical.length >= 4 }
            .map { it to editDistance(n, normalize(it.canonical)) }
            .filter { it.second <= 1 }
            .minByOrNull { it.second }
            ?.let { it.first.canonical to it.first.cat }
    }

    fun normalize(s: String): String {
        var t = s.lowercase().replace("dh", "d").replace("w", "v").replace("z", "j")
        t = t.replace(Regex("([aeiou])\\1+"), "$1")
        return t
    }

    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun devanagariDigitsToAscii(s: String) =
        String(CharArray(s.length) { i -> s[i].let { if (it in '०'..'९') '0' + (it - '०') else it } })
}
