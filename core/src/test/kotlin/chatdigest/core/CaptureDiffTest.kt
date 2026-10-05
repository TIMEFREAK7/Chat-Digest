package chatdigest.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureDiffTest {
    private val zone = ZoneOffset.UTC
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 5, h, m, s).toInstant(zone).toEpochMilli()

    private val export = ExportParser.parse(
        """
        05/10/2026, 09:00 - Messages and calls are end-to-end encrypted.
        05/10/2026, 10:00 - Aditya: udya sakali 10 la Chakan site
        05/10/2026, 10:01 - Me: ok
        05/10/2026, 10:02 - Aditya: <Media omitted>
        05/10/2026, 10:03 - Aditya: long message that is longer than the notification
        second line
        05/10/2026, 11:00 - Aditya: sent while listener was down
        05/10/2026, 12:10 - Aditya: sent while I had the chat open
        05/10/2026, 13:00 - Aditya: never notified
        05/10/2026, 13:30 - Aditya: dropped from a full window
        05/10/2026, 14:00 - Aditya: This message was deleted
        """.trimIndent().lines(),
    )

    private val events = listOf(
        CaptureEvent(ms(9, 30), "connect"),
        CaptureEvent(ms(10, 0, 20), "post", "Site (2 messages)", listOf(CapturedMsg(ms(10, 0, 15), "Aditya", "udya sakali 10 la Chakan site"))),
        CaptureEvent(ms(10, 3, 10), "post", "Site", listOf(
            CapturedMsg(ms(10, 2, 5), "Aditya", "📷 Photo"),
            CapturedMsg(ms(10, 3, 5), "Aditya", "long message that is longer…"),
        )),
        CaptureEvent(ms(10, 50), "disconnect"),
        CaptureEvent(ms(11, 10), "connect"),
        CaptureEvent(ms(11, 20), "heartbeat"),
        CaptureEvent(ms(11, 35), "heartbeat"),
        CaptureEvent(ms(11, 50), "heartbeat"),
        CaptureEvent(ms(12, 5), "remove", "Site", removeReason = 8),
        CaptureEvent(ms(12, 20), "heartbeat"),
        CaptureEvent(ms(12, 35), "heartbeat"),
        CaptureEvent(ms(12, 50), "heartbeat"),
        CaptureEvent(ms(13, 5), "heartbeat"),
        CaptureEvent(ms(13, 20), "heartbeat"),
        CaptureEvent(ms(13, 31), "post", "Site", listOf(CapturedMsg(ms(13, 31), "Aditya", "a later message"))),
        CaptureEvent(ms(13, 45), "heartbeat"),
        CaptureEvent(ms(14, 30), "heartbeat"),
    )

    @Test fun parsesExport() {
        assertEquals(9, export.size)
        assertEquals("long message that is longer than the notification\nsecond line", export[3].text)
    }

    @Test fun parses12hExportWithNarrowNbsp() {
        val m = ExportParser.parse(listOf("5/10/26, 2:32 pm - Aditya: hi")).single()
        assertEquals(LocalDateTime.of(2026, 10, 5, 14, 32), m.time)
    }

    @Test fun classifiesEveryMiss() {
        val r = CaptureDiff.diff(export, "Site", "Me", events, zone)
        assertEquals(1, r.skippedOwn)
        assertEquals(1, r.skippedDeleted)
        assertEquals(2, r.matched) // text + media
        val causes = r.misses.associate { it.msg.text.lines().first() to it.cause }
        assertEquals(MissCause.TRUNCATED, causes["long message that is longer than the notification"])
        assertEquals(MissCause.LISTENER_DOWN, causes["sent while listener was down"])
        assertEquals(MissCause.SEEN_LIVE, causes["sent while I had the chat open"])
        assertEquals(MissCause.UNEXPLAINED, causes["never notified"])
        assertEquals(MissCause.OVERFLOW, causes["dropped from a full window"])
        assertEquals(4, r.gateMisses)
        assertEquals(listOf("long message that is longer…", "a later message"), r.extraCaptured.map { it.text })
    }
}
