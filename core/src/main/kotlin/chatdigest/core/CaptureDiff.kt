package chatdigest.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** One line of the spike's capture log, already parsed. Times are epoch millis. */
data class CaptureEvent(
    val at: Long,
    /** post, remove, connect, disconnect, heartbeat */
    val kind: String,
    val chat: String? = null,
    val messages: List<CapturedMsg> = emptyList(),
    val groupSummary: Boolean = false,
    val removeReason: Int = 0,
)

data class CapturedMsg(val time: Long, val sender: String?, val text: String)

enum class MissCause(val countsAgainstGate: Boolean, val meaning: String) {
    LISTENER_DOWN(true, "listener disconnected or app silent (gap) when it arrived"),
    OVERFLOW(true, "WhatsApp notified for the chat but this message was outside the notification window"),
    TRUNCATED(true, "captured, but notification text was cut short"),
    SEEN_LIVE(false, "chat notification had just been cleared — most likely read live with the chat open"),
    UNEXPLAINED(true, "no notification for this chat around that time"),
}

data class Miss(val msg: ExportMessage, val cause: MissCause)

data class DiffReport(
    val chat: String,
    val windowStart: LocalDateTime,
    val windowEnd: LocalDateTime,
    val considered: Int,
    val matched: Int,
    val skippedOwn: Int,
    val skippedDeleted: Int,
    val misses: List<Miss>,
    val extraCaptured: List<CapturedMsg>,
    val titlesSeen: Set<String>,
) {
    val gateMisses get() = misses.count { it.cause.countsAgainstGate }

    fun toText(zone: ZoneId): String = buildString {
        appendLine("== Capture diff: $chat ==")
        appendLine("window $windowStart → $windowEnd")
        appendLine("export msgs in window from others: $considered, matched: $matched, own skipped: $skippedOwn, deleted skipped: $skippedDeleted")
        appendLine("GATE MISSES: $gateMisses ${if (gateMisses == 0) "(pass)" else "(FAIL)"}")
        MissCause.entries.forEach { c ->
            val ms = misses.filter { it.cause == c }
            if (ms.isEmpty()) return@forEach
            appendLine("-- ${c.name} (${ms.size}): ${c.meaning}")
            ms.forEach { appendLine("   ${it.msg.time} ${it.msg.sender}: ${it.msg.text.take(80)}") }
        }
        if (extraCaptured.isNotEmpty()) {
            appendLine("-- captured but not in export (edits? sent after export?): ${extraCaptured.size}")
            extraCaptured.forEach { appendLine("   ${LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), zone)} ${it.sender}: ${it.text.take(80)}") }
        }
        if (matched == 0) appendLine("No match at all. Chat titles seen in capture: ${titlesSeen.sorted()}")
    }
}

object CaptureDiff {
    private const val MATCH_BEFORE_MS = 60_000L   // export times are truncated to the minute
    private const val MATCH_AFTER_MS = 120_000L
    private const val GAP_MS = 20 * 60_000L        // heartbeat runs every 15 min
    private const val SEEN_LIVE_MS = 30 * 60_000L
    private const val OVERFLOW_MS = 5 * 60_000L
    private val MEDIA = listOf("📷", "🎥", "🎤", "🎵", "📄", "👤", "📍", "Photo", "Video", "Voice message", "Sticker", "GIF", "Document", "Audio", "Contact", "Location")
    private val COUNT_SUFFIX = Regex("""\s*\(\d+ (?:new )?messages?\)$""")

    fun chatKey(title: String?) = title.orEmpty().replace(COUNT_SUFFIX, "").trim().lowercase()

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    fun diff(
        export: List<ExportMessage>,
        chatName: String,
        ownName: String,
        events: List<CaptureEvent>,
        zone: ZoneId,
    ): DiffReport {
        val evs = events.sortedBy { it.at }
        require(evs.isNotEmpty()) { "capture log is empty" }
        val start = evs.first().at
        val end = evs.last().at
        fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

        val key = chatKey(chatName)
        val chatEvents = evs.filter { chatKey(it.chat) == key }
        val captured = chatEvents.flatMap { it.messages }.distinctBy { it.time to it.text }.toMutableList()
        val inWindow = export.filter { ms(it.time) in start..end }
        val own = inWindow.filter { norm(it.sender) == norm(ownName) }
        val deleted = inWindow.filter { it.isDeleted && it !in own }
        val considered = inWindow - own.toSet() - deleted.toSet()

        val gaps = downIntervals(evs)
        val misses = mutableListOf<Miss>()
        var matched = 0
        for (m in considered) {
            val t = ms(m.time)
            val near = captured.filter { it.time in (t - MATCH_BEFORE_MS)..(t + MATCH_AFTER_MS) }
            val hit = near.firstOrNull { norm(it.text) == norm(m.text) }
                ?: if (m.isMedia) near.firstOrNull { c -> MEDIA.any { c.text.contains(it) } } else null
            if (hit != null) {
                matched++
                captured.remove(hit)
                continue
            }
            misses += Miss(m, cause(m, t, near, gaps, chatEvents))
        }
        val extra = captured.filter { it.time in start..end }
        return DiffReport(
            chatName,
            LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(end), zone),
            considered.size, matched, own.size, deleted.size, misses, extra,
            evs.mapNotNull { it.chat?.let(::chatKey) }.toSet(),
        )
    }

    private fun cause(
        m: ExportMessage,
        t: Long,
        near: List<CapturedMsg>,
        gaps: List<LongRange>,
        chatEvents: List<CaptureEvent>,
    ): MissCause {
        val text = norm(m.text)
        if (near.any { c -> norm(c.text).removeSuffix("…").let { it.length >= 10 && text.startsWith(it) } }) return MissCause.TRUNCATED
        if (gaps.any { t in it }) return MissCause.LISTENER_DOWN
        val lastBefore = chatEvents.lastOrNull { it.at <= t + MATCH_AFTER_MS }
        if (lastBefore?.kind == "remove" && t - lastBefore.at <= SEEN_LIVE_MS) return MissCause.SEEN_LIVE
        if (chatEvents.any { it.kind == "post" && it.at in t..(t + OVERFLOW_MS) }) return MissCause.OVERFLOW
        return MissCause.UNEXPLAINED
    }

    /** Disconnect→connect spans, plus any silence longer than [GAP_MS] (heartbeats included). */
    fun downIntervals(evs: List<CaptureEvent>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var downSince: Long? = null
        for ((prev, cur) in evs.zipWithNext()) {
            if (cur.at - prev.at > GAP_MS) out += prev.at..cur.at
        }
        for (e in evs) {
            if (e.kind == "disconnect" && downSince == null) downSince = e.at
            if (e.kind == "connect" && downSince != null) { out += downSince..e.at; downSince = null }
        }
        downSince?.let { out += it..evs.last().at }
        return out
    }

    /** Tier 0 questions about the notification payload itself. */
    fun stats(events: List<CaptureEvent>, zone: ZoneId): String = buildString {
        val evs = events.sortedBy { it.at }
        if (evs.isEmpty()) return "capture log is empty"
        fun t(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val posts = evs.filter { it.kind == "post" }
        val sizes = posts.filter { !it.groupSummary }.map { it.messages.size }
        appendLine("== Capture stats ==")
        appendLine("span ${t(evs.first().at)} → ${t(evs.last().at)}")
        appendLine("events by kind: ${evs.groupingBy { it.kind }.eachCount()}")
        appendLine("posts: ${posts.size} (group summaries: ${posts.count { it.groupSummary }})")
        if (sizes.isNotEmpty()) {
            appendLine("messages per MessagingStyle window: max ${sizes.max()}, avg ${"%.1f".format(sizes.average())}")
            appendLine("window size histogram: ${sizes.groupingBy { it }.eachCount().toSortedMap()}")
        }
        appendLine("remove reasons: ${evs.filter { it.kind == "remove" }.groupingBy { it.removeReason }.eachCount()}")
        val longest = posts.flatMap { it.messages }.maxByOrNull { it.text.length }
        appendLine("longest captured text: ${longest?.text?.length ?: 0} chars, ends with: ${longest?.text?.takeLast(20)}")
        val gaps = downIntervals(evs)
        appendLine("down/silent intervals: ${gaps.size}")
        gaps.forEach { appendLine("   ${t(it.first)} → ${t(it.last)} (${(it.last - it.first) / 60_000} min)") }
        appendLine("chats seen: ${posts.mapNotNull { it.chat?.let(::chatKey) }.toSet().size}")
    }
}
