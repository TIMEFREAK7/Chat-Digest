package chatdigest.spike

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The ONLY network code in the app (CI enforces this). Downloads a pinned model file by GET from an
 * allowlisted host, resumes after interruption, and keeps it only if its SHA-256 matches.
 * Nothing about the user or their messages is ever sent.
 */
object ModelDownloader {

    data class Model(val name: String, val file: String, val bytes: Long, val sha256: String) {
        val url get() = "https://huggingface.co/litert-community/gemma-4-$name-it-litert-lm/resolve/main/$file"
    }

    val MODELS = listOf(
        Model("E2B", "gemma-4-E2B-it.litertlm", 2_588_147_712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
        Model("E4B", "gemma-4-E4B-it.litertlm", 3_659_530_240, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"),
    )

    /** huggingface.co redirects to its CDN; nothing else is ever contacted. */
    private fun allowed(host: String) = host == "huggingface.co" || host.endsWith(".hf.co")

    /** Blocks until done. [progress] gets (bytesSoFar, total). Returns the verified file. */
    fun download(m: Model, dir: File, progress: (Long, Long) -> Unit): File {
        val dest = File(dir, m.file)
        if (dest.length() == m.bytes) return dest
        val part = File(dir, m.file + ".part")
        if (part.length() > m.bytes) part.delete()
        if (part.length() < m.bytes) fetch(m, part, progress)
        progress(m.bytes, m.bytes)
        return verifyAndMove(m, part, dest)
    }

    private fun fetch(m: Model, part: File, progress: (Long, Long) -> Unit) {
        val conn = open(URL(m.url), part.length())
        val resumed = conn.responseCode == 206
        if (!resumed && conn.responseCode != 200) error("download failed: HTTP ${conn.responseCode}")
        RandomAccessFile(part, "rw").use { out ->
            if (!resumed) out.setLength(0)
            out.seek(out.length())
            conn.inputStream.use { input ->
                val buf = ByteArray(1 shl 20)
                var done = out.length()
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= 25_000_000) { progress(done, m.bytes); lastReport = done }
                }
            }
        }
        conn.disconnect()
    }

    private fun verifyAndMove(m: Model, part: File, dest: File): File {
        val sha = sha256(part)
        if (sha != m.sha256) {
            part.delete()
            error("checksum mismatch (got $sha) — file deleted, tap again to retry")
        }
        check(part.renameTo(dest)) { "could not move model into place" }
        return dest
    }

    /** Follows redirects by hand so every hop is checked against the allowlist. */
    private fun open(start: URL, from: Long): HttpURLConnection {
        var url = start
        repeat(5) {
            require(url.protocol == "https" && allowed(url.host)) { "refusing to contact ${url.host}" }
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            if (c.responseCode !in 300..399) return c
            url = URL(url, c.getHeaderField("Location"))
            c.disconnect()
        }
        error("too many redirects")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
