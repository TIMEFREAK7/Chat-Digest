package chatdigest.core

import java.time.format.DateTimeFormatter

/** The fixed, versioned summarization contract. Any change bumps [VERSION] and re-runs the golden set. */
object PromptContract {
    const val VERSION = "pc-0.1"

    val SYSTEM = """
        You summarize a burst of WhatsApp messages for one reader. Messages may mix English, Hinglish and romanized Marathi.
        Rules:
        1. Use only information present in the messages. No inference, no advice, no filler.
        2. Write the summary in English.
        3. Copy names, numbers, dates, times, amounts, addresses and places exactly as written. Do not translate or convert them. Keep phrases like "udya sakali 10 la" or "kal shaam" verbatim.
        4. In group chats, attribute each statement to its sender.
        5. Ignore greetings, reactions, emoji-only messages, "ok"/"👍" acknowledgements and forwarded chains unless they contain an ask or a fact.
        6. Never guess what a photo, video, voice note or deleted message contains.
        7. Output only these sections, in this order, and omit any section that would be empty:
        Decisions:
        Asks / action items: (who → what → by when)
        Questions directed at me:
        Key facts:
        Other: (one line max)
    """.trimIndent()

    private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

    /** [reader] = the user's name and nicknames, so mentions land in Asks / Questions. */
    fun userPrompt(chat: String, isGroup: Boolean, msgs: List<ExportMessage>, reader: List<String>): String = buildString {
        appendLine("Chat: $chat (${if (isGroup) "group" else "one-to-one"})")
        if (reader.isNotEmpty()) appendLine("I am: ${reader.joinToString(" / ")}. Anything addressed to me must appear under Asks or Questions directed at me.")
        val media = msgs.count { it.isMedia }
        if (media > 0) appendLine("$media media messages are not shown; write \"$media media messages not summarized\" under Other.")
        appendLine("Messages:")
        msgs.filterNot { it.isMedia || it.isDeleted }.forEach { appendLine("[${it.time.format(TIME)}] ${it.sender}: ${it.text}") }
    }
}
