package chatdigest.spike

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.WindowInsets
import chatdigest.core.CaptureDiff
import chatdigest.core.ExportMessage
import chatdigest.core.ExportParser
import chatdigest.core.PromptContract
import chatdigest.core.TimeLexicon
import java.io.File
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Throwaway Tier 0 console: one screen of buttons, output appended to report.txt. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var dl: TextView
    private lateinit var names: EditText
    private lateinit var modelBtn: Button
    private lateinit var backendBtn: Button
    private var gpu = true
    private var modelIdx = 0

    private val zone get() = ZoneId.systemDefault()
    private val modelsDir get() = File(filesDir, "models").apply { mkdirs() }
    private val models get() = modelsDir.listFiles().orEmpty().filter { it.extension == "litertlm" }.sortedBy { it.name }
    private val reportFile get() = File(filesDir, "report.txt")
    private val prefs get() = getSharedPreferences("spike", MODE_PRIVATE)

    /** First entry = your name as it appears in exports; the rest are nicknames. */
    private val readerNames get() = names.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = "Digest Spike"; textSize = 22f; setPadding(0, 0, 0, 24) })
        names = EditText(this).apply {
            hint = "Your name in exports, then nicknames (comma-separated)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("names", ""))
        }
        col.addView(names)
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }.also(col::addView)

        button("1. Grant notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button("2. Battery exemption") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("3. Status") { status() }
        button("4. Capture stats") { bg { CaptureDiff.stats(CaptureLog.read(this), zone) } }
        button("5. Diff export(s) vs capture…") { pick(REQ_DIFF) }
        ModelDownloader.MODELS.forEachIndexed { i, m ->
            button("6${'a' + i}. Download Gemma 4 ${m.name} (${m.bytes / 100_000_000 / 10.0} GB)") { download(m) }
        }
        dl = TextView(this).also(col::addView)
        button("6. Import model file manually…") { pick(REQ_MODEL, multiple = false) }
        modelBtn = button("") { modelIdx++; refreshButtons() }
        backendBtn = button("") { gpu = !gpu; refreshButtons() }
        button("7. Summarize export(s) with selected model…") { pick(REQ_EVAL) }
        button("8. Lexicon report on export(s)…") { pick(REQ_LEXICON) }
        button("Save report…") { save(REQ_SAVE_REPORT, "report.txt") }
        button("Save capture log…") { save(REQ_SAVE_LOG, "capture.jsonl") }
        button("Clear report") { reportFile.delete(); out.text = "" }
        button("Clear capture log (restart 48 h run)") { CaptureLog.file(this).delete(); print("capture log cleared") }

        out = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f }
        col.addView(out)
        scroll = ScrollView(this).apply { addView(col) }
        // targetSdk 35+ draws edge-to-edge: keep content clear of the status and navigation bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(b.left + 32, b.top + 32, b.right + 32, b.bottom + 32)
            insets
        }
        setContentView(scroll)
        refreshButtons()
        Heartbeat.schedule(this)
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString("names", names.text.toString()).apply()
    }

    private fun refreshButtons() {
        val m = models
        modelBtn.text = "Model: " + (if (m.isEmpty()) "none imported" else m[modelIdx % m.size].name) + " (tap to switch)"
        backendBtn.text = "Backend: " + (if (gpu) "GPU" else "CPU") + " (tap to switch)"
    }

    private fun status() = print(
        buildString {
            appendLine("== Status ${LocalDateTime.now()} ==")
            appendLine("device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("state ${deviceState(this@MainActivity)}")
            val evs = CaptureLog.read(this@MainActivity)
            appendLine("log events ${evs.size}, last: ${evs.lastOrNull()?.let { "${it.kind} at ${java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime()}" }}")
            appendLine("last WhatsApp post: ${evs.lastOrNull { it.kind == "post" }?.let { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime() }}")
            models.forEach { appendLine("model ${it.name} ${it.length() / 1_000_000} MB") }
        },
    )

    private var meteredOk = false

    /** Wi-Fi by default; on mobile data the first tap only warns. Screen stays on while downloading. */
    private fun download(m: ModelDownloader.Model) {
        if (getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered && !meteredOk) {
            meteredOk = true
            dl.text = "You're on mobile data and ${m.name} is ${m.bytes / 1_000_000} MB. Tap again to download anyway."
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dl.text = "Starting ${m.name}…"
        bg {
            try {
                val f = ModelDownloader.download(m, modelsDir) { done, total ->
                    runOnUiThread { dl.text = "${m.name}: ${done / 1_000_000} / ${total / 1_000_000} MB" + if (done == total) " — verifying checksum…" else "" }
                }
                runOnUiThread { dl.text = "${m.name} ready (checksum verified)"; refreshButtons() }
                "downloaded ${f.name}: ${f.length() / 1_000_000} MB, SHA-256 verified"
            } catch (e: Exception) {
                runOnUiThread { dl.text = "${m.name} stopped: ${e.message}. Tap again to resume." }
                throw e
            } finally {
                runOnUiThread { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    // --- file plumbing ---

    private fun pick(req: Int, multiple: Boolean = true) = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple),
        req,
    )

    private fun save(req: Int, name: String) = startActivityForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, name),
        req,
    )

    @Deprecated("Activity result API is fine for a throwaway spike")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        when (requestCode) {
            REQ_DIFF -> bg { uris.joinToString("\n") { diff(it) } }
            REQ_EVAL -> bg { uris.joinToString("\n") { summarize(it) } }
            REQ_LEXICON -> bg { uris.joinToString("\n") { lexicon(it) } }
            REQ_MODEL -> bg { importModel(uris.single()) }
            REQ_SAVE_REPORT -> bg { copyOut(reportFile, uris.single()) }
            REQ_SAVE_LOG -> bg { copyOut(CaptureLog.file(this), uris.single()) }
        }
    }

    private fun displayName(uri: Uri) = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()

    /** Accepts the .txt or the .zip WhatsApp's "Export chat" produces. */
    private fun readExport(uri: Uri): Pair<String, List<ExportMessage>> {
        val name = displayName(uri)
        val lines = contentResolver.openInputStream(uri)!!.use { input ->
            if (name.endsWith(".zip", true)) {
                val zip = ZipInputStream(input)
                generateSequence { zip.nextEntry }.first { it.name.endsWith(".txt") }
                zip.bufferedReader().readLines()
            } else input.bufferedReader().readLines()
        }
        val chat = name.substringAfter("WhatsApp Chat with ", name).substringBeforeLast('.').substringBefore(" (")
        val msgs = try { ExportParser.parse(lines, dayFirst = true) } catch (e: DateTimeException) { ExportParser.parse(lines, dayFirst = false) }
        require(msgs.isNotEmpty()) {
            "\"$name\" is not a WhatsApp export (0 messages parsed). First lines:\n" +
                lines.filter { it.isNotBlank() }.take(3).joinToString("\n") { "   " + it.take(100) }
        }
        return chat to msgs
    }

    private fun copyOut(src: File, dest: Uri): String {
        contentResolver.openOutputStream(dest)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        return "saved ${src.name} (${src.length()} bytes)"
    }

    private fun importModel(uri: Uri): String {
        val dest = File(modelsDir, displayName(uri))
        contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it, 1 shl 20) } }
        runOnUiThread { refreshButtons() }
        return "imported ${dest.name}: ${dest.length() / 1_000_000} MB at ${dest.absolutePath}"
    }

    // --- Tier 0 jobs ---

    private fun diff(uri: Uri): String {
        val (chat, msgs) = readExport(uri)
        val own = readerNames.firstOrNull() ?: return "Enter your name (as it appears in the export) in the box at the top first.".also {
            runOnUiThread { scroll.fullScroll(ScrollView.FOCUS_UP); names.requestFocus() }
        }
        return CaptureDiff.diff(msgs, chat, own, CaptureLog.read(this), zone).toText(zone)
    }

    /** Production only sees other people's messages, so the burst is the last [WINDOW] of those. */
    private fun burst(msgs: List<ExportMessage>) =
        msgs.filter { m -> readerNames.firstOrNull()?.let { !m.sender.equals(it, true) } ?: true }.takeLast(WINDOW)

    private fun summarize(uri: Uri): String {
        val m = models.ifEmpty { return "Import a model first." }[modelIdx % models.size]
        val (chat, msgs) = readExport(uri)
        val window = burst(msgs)
        val isGroup = msgs.map { it.sender }.distinct().size > 2
        return try {
            Llm(this, m, gpu).use { llm ->
                "== Summary: $chat | ${m.name} | ${PromptContract.VERSION} | ${window.size} msgs ${window.firstOrNull()?.time} → ${window.lastOrNull()?.time} ==\n" +
                    llm.summarize(PromptContract.SYSTEM, PromptContract.userPrompt(chat, isGroup, window, readerNames))
            }
        } catch (t: Throwable) {
            "== Summary: $chat | ${m.name} | ${if (gpu) "GPU" else "CPU"} FAILED: $t"
        }
    }

    private fun lexicon(uri: Uri): String {
        val (chat, msgs) = readExport(uri)
        val rs = msgs.flatMap { m -> TimeLexicon.resolve(m.text, m.time).map { m to it } }
        return buildString {
            appendLine("== Lexicon: $chat | ${msgs.size} msgs, ${rs.size} phrases, ${rs.count { it.second.resolved != null && it.second.warnings.isEmpty() }} fully resolved ==")
            rs.forEach { (m, r) -> appendLine("${m.time} $r   [${r.matches.joinToString { "${it.first}≈${it.second}" }}]") }
        }
    }

    private fun bg(job: () -> String) {
        out.append("working…\n")
        worker.execute { print(runCatching(job).getOrElse { "ERROR: $it" }) }
    }

    private fun print(s: String) {
        reportFile.appendText(s + "\n")
        runOnUiThread { out.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    }

    companion object {
        const val WINDOW = 50 // matches the Tier 0 "50-message summary" benchmark
        const val REQ_DIFF = 1
        const val REQ_MODEL = 2
        const val REQ_EVAL = 3
        const val REQ_LEXICON = 4
        const val REQ_SAVE_REPORT = 5
        const val REQ_SAVE_LOG = 6
    }
}
