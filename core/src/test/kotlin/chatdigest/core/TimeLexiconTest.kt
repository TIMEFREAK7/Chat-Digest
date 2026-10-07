package chatdigest.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeLexiconTest {
    private val monday = LocalDateTime.of(2026, 10, 5, 9, 15) // Mon 5 Oct 2026

    private fun one(text: String) = TimeLexicon.resolve(text, monday).single()

    @Test fun marathiTomorrowMorning() {
        val r = one("udya sakali 10 la ye")
        assertEquals("udya sakali 10 la", r.phrase)
        assertEquals("Tue 6 Oct, 10:00", r.resolved)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun listedSpellingVariants() {
        assertEquals("Tue 6 Oct, 10:00", one("udhyaa sakaali 10 la").resolved)
        assertEquals("Tue 6 Oct, 10:00", one("udyaa sakli 10 vajta").resolved)
    }

    @Test fun kalResolvedByTense() {
        assertEquals("Tue 6 Oct, evening", TimeLexicon.resolve("kal shaam milenge", monday).single().resolved)
        assertEquals("Sun 4 Oct, evening", TimeLexicon.resolve("kal shaam aaya tha", monday).single().resolved)
        assertEquals("Sun 4 Oct", TimeLexicon.resolve("kaal gela hota", monday).single().resolved)
    }

    @Test fun kalWithoutTenseStaysUnresolved() {
        val r = one("kal shaam")
        assertEquals("evening", r.resolved)
        assertTrue(r.warnings.single().contains("past or future"))
    }

    @Test fun shortTokensNeedExactVariant() {
        // "kab" (when) is one edit from "kal" but is under 4 chars, so it must not match.
        assertTrue(TimeLexicon.resolve("kab aaoge", monday).isEmpty())
        assertEquals("Mon 5 Oct", one("aj milte").resolved)
        assertEquals("kal", one("kl aaunga").matches.single().second)
    }

    @Test fun fractionsAndPeriods() {
        assertEquals("16:30", one("dupari saade 4 la").resolved)
        assertEquals("13:30", one("dupari dedh vajta").resolved)
        assertEquals("21:45", one("raatri paune 10").resolved)
        assertEquals("22:00", one("raat 10 baje").resolved)
    }

    @Test fun hourWithoutPeriodIsNotGuessed() {
        val r = one("saade 4 baje")
        assertNull(r.resolved)
        assertTrue("am or pm?" in r.warnings)
    }

    @Test fun amounts() {
        assertEquals("150000", one("dedh lakh dila").resolved)
        assertEquals("2500", one("adich hazaar").resolved)
        assertEquals("200000", one("2 lakh").resolved)
        assertEquals("3500", one("saade 3 hazaar").resolved)
    }

    @Test fun devanagariDigits() {
        assertEquals("Tue 6 Oct, 10:00", one("udya sakali १० la").resolved)
    }

    @Test fun tier0FalsePositivesStayUnmatched() {
        // Each of these was matched by the old edit-distance rule in real chats (7 Oct report).
        val words = "need deep kiti koni hoti adhi baat vaat jaat sadhya sarva naii kadhich saniya madhe lach gham"
        assertTrue(TimeLexicon.resolve("$words 5 la", monday).none { r -> r.matches.any { it.first in words.split(" ") } })
    }

    @Test fun plainEnglishAndBareNumbersIgnored() {
        assertTrue(TimeLexicon.resolve("same side sale, safe rate, 5 people, Pune 3", monday).isEmpty())
    }
}
