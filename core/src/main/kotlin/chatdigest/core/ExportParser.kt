package chatdigest.core

import java.time.LocalDateTime

/** One message from WhatsApp's Android "Export chat" (without media) text file. */
data class ExportMessage(val time: LocalDateTime, val sender: String, val text: String) {
    val isMedia get() = text.trim().let { it == "<Media omitted>" || it.endsWith("(file attached)") || it == "null" }
    val isDeleted get() = text.trim().let { it == "This message was deleted" || it == "You deleted this message" }
}

object ExportParser {
    // "05/10/2026, 14:32 - Name: text" and "5/10/26, 2:32 pm - Name: text" (narrow NBSP before am/pm).
    private val LINE = Regex(
        """^‎?(\d{1,2})/(\d{1,2})/(\d{2,4}),?\s(\d{1,2}):(\d{2})(?::\d{2})?[\s ]?([aApP]\.?\s?[mM]\.?)?\s[-–]\s(.*)$""",
    )

    /** [dayFirst] = true for Indian locale exports (dd/mm/yy). System lines (no "Sender: ") are dropped. */
    fun parse(lines: List<String>, dayFirst: Boolean = true): List<ExportMessage> {
        val out = mutableListOf<ExportMessage>()
        var system = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            val m = LINE.matchEntire(line)
            if (m == null) {
                // Continuation of a multi-line message.
                if (!system && out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.copy(text = it.text + "\n" + line) }
                continue
            }
            val (a, b, y, hh, mm, ampm, body) = m.destructured
            val (day, month) = if (dayFirst) a.toInt() to b.toInt() else b.toInt() to a.toInt()
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            var hour = hh.toInt()
            if (ampm.isNotEmpty()) {
                val pm = ampm[0].lowercaseChar() == 'p'
                hour = hour % 12 + if (pm) 12 else 0
            }
            val sep = body.indexOf(": ")
            system = sep < 0
            if (system) continue
            out += ExportMessage(
                LocalDateTime.of(year, month, day, hour, mm.toInt()),
                body.substring(0, sep).trim(),
                body.substring(sep + 2),
            )
        }
        return out
    }
}
