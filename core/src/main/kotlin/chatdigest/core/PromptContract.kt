package chatdigest.core

import java.time.format.DateTimeFormatter

/** The fixed, versioned summarization contract. Any change bumps [VERSION] and re-runs the golden set. */
object PromptContract {
    const val VERSION = "pc-0.3"

    val SYSTEM = """
        You summarize a burst of WhatsApp messages for one reader. Messages may mix English, Hinglish and romanized Marathi.
        Rules:
        1. Use only information present in the messages. No inference, no advice, no filler.
        2. Write the summary in English.
        3. Copy names, numbers, dates, times, amounts, addresses and places exactly as written. Do not translate or convert them. Keep phrases like "udya sakali 10 la" or "kal shaam" verbatim.
        4. In group chats, attribute each statement to its sender.
        5. Ignore greetings, reactions, emoji-only messages, "ok"/"👍" acknowledgements and forwarded chains unless they contain an ask or a fact.
        6. Never guess what a photo, video, voice note or deleted message contains.
        7. Write every item in English, including questions; put the original words in quotes after the English when they are not English.
        8. The [time] before each message is when it was sent, not a deadline. Write "by when" only if a message states a deadline in words; otherwise leave it out.
        9. "Questions directed at me" holds only questions addressed to me by name, or any question in a one-to-one chat. Questions between other people go under Key facts.
        10. Output only these sections, in this order. Leave out a section completely when it has nothing: never write "None" or an empty placeholder.
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
        // WhatsApp wraps @mentions in invisible isolate marks (U+2068/U+2069); strip them so names stay clean.
        msgs.filterNot { it.isMedia || it.isDeleted }.forEach {
            appendLine("[${it.time.format(TIME)}] ${it.sender}: ${it.text.replace("\u2068", "").replace("\u2069", "")}")
        }
    }
}
