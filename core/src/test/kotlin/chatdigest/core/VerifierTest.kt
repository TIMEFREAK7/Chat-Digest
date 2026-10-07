package chatdigest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cases taken from real Tier 0 summaries (7–8 Oct reports). */
class VerifierTest {
    private val source = listOf(
        "Chat: PGCS - Site Team (group)",
        "I am: Aditya Abhyankar",
        "20 media messages are not shown; write \"20 media messages not summarized\" under Other.",
        "[Tue 6 Oct 13:36] Sowmi (PEPSICO): @⁨Priyadarshan Project Pepsico⁩ Plz take 10 pax manpower and utilize them to rectify this from today until Sunday.",
        "[Tue 6 Oct 13:40] Sowmi (PEPSICO): @Mukesh Gurati (ELEC) plz let me know if any shutdown require for electrical point of view in advance.",
        "[Mon 21 Sep 15:52] Ajoy Saha (Omicrest): Sprinkler completion: Plant 1 & 3 below duct installation has been done.",
        "[Tue 6 Oct 22:56] +91 98015 77346: Total Manpower: 39",
        "[Sat 3 Oct 21:27] Chaitrali Johari: Saloni pn yetie. Ti karel mala pick on the way",
    )

    private fun failed(summary: String) = Verifier.verify(summary, source).failed

    @Test fun changedNumberIsCaughtEvenWhenTheDigitExistsElsewhere() {
        assertEquals(listOf("0"), failed("* Sowmi (PEPSICO) → Priyadarshan Project Pepsico → Take 0 pax manpower"))
        // "1" appears in the source ("Plant 1 & 3") but never next to "pax".
        assertEquals(listOf("1"), failed("* Sowmi (PEPSICO) → Take 1 pax manpower"))
    }

    @Test fun garbledDatesAreCaught() {
        assertTrue(failed("Write all communication over the mail (by Mon 2191 Sept 209:19)").containsAll(listOf("2191", "209:19")))
    }

    @Test fun corruptedNamesAreCaught() {
        assertEquals(listOf("PSICO"), failed("* Sowmi (PSICO) stated the audit is next week"))
        assertEquals(listOf("LEC"), failed("* Sowmi (PEPSICO) → Mukesh Gurati (LEC) → shutdown plan"))
        assertEquals(listOf("Priyadharsan"), failed("* Sowmi asked Priyadharsan Project Pepsico to rectify it"))
    }

    @Test fun datesAndPhoneNumbersNeedNoMatchingNeighbour() {
        val src = listOf("[Tue 6 Oct 22:56] +91 98015 77346: Permit details Date: 05.10.2026 Total Manpower: 39")
        assertTrue(Verifier.verify("Manpower list on 05.10.2026 from +91 98015 77346", src).verified)
        assertEquals(listOf("06.10.2026"), Verifier.verify("Manpower list on 06.10.2026", src).failed)
    }

    @Test fun inventedQuoteIsCaught() {
        assertEquals(listOf("\"Saloni yenar nahi\""), failed("Chaitrali Johari said \"Saloni yenar nahi\""))
    }

    @Test fun faithfulSummaryPasses() {
        val ok = """
            Asks / action items:
            * Sowmi (PEPSICO) → Priyadarshan Project Pepsico → Take 10 pax manpower to rectify this until Sunday
            * Sowmi (PEPSICO) → Mukesh Gurati (ELEC) → Report any shutdown needed in advance
            Key facts:
            * +91 98015 77346 reported 39 total manpower.
            * Chaitrali Johari said "Ti karel mala pick on the way" (She will pick me up on the way).
            Other: 20 media messages not summarized
        """.trimIndent()
        assertEquals(emptyList(), failed(ok))
    }

    @Test fun wrongMeaningFromRealWordsIsNotCaught() {
        // Known ceiling: a mistranslation uses only real words, so it verifies. Hand-scoring must catch it.
        assertTrue(Verifier.verify("Chaitrali Johari said \"Ti karel mala pick on the way\" (You will pick me up).", source).verified)
    }
}
