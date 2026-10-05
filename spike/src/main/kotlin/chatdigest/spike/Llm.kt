package chatdigest.spike

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/** Load → generate → unload, with the Tier 0 measurements around each step. */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class Llm(private val ctx: Context, model: File, val gpu: Boolean) : AutoCloseable {
    val loadMs: Long
    private val engine: Engine

    init {
        ExperimentalFlags.enableBenchmark = true
        val t = SystemClock.elapsedRealtime()
        engine = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = if (gpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = 4096,
                cacheDir = ctx.cacheDir.absolutePath,
            ),
        )
        engine.initialize()
        loadMs = SystemClock.elapsedRealtime() - t
    }

    fun summarize(system: String, user: String): String {
        val before = Probe(ctx)
        val t = SystemClock.elapsedRealtime()
        // Greedy decoding so golden-set runs are repeatable.
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
        )
        val (text, bench) = engine.createConversation(config).use { c ->
            val reply = c.sendMessage(user)
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } to
                runCatching { c.getBenchmarkInfo() }.getOrNull()
        }
        val wallMs = SystemClock.elapsedRealtime() - t
        val after = Probe(ctx)
        return buildString {
            appendLine("backend=${if (gpu) "GPU" else "CPU"} load=${loadMs}ms generate=${wallMs}ms")
            bench?.let {
                appendLine(
                    "prefill ${it.lastPrefillTokenCount} tok @ ${"%.1f".format(it.lastPrefillTokensPerSecond)} tok/s, " +
                        "decode ${it.lastDecodeTokenCount} tok @ ${"%.1f".format(it.lastDecodeTokensPerSecond)} tok/s, " +
                        "TTFT ${"%.2f".format(it.timeToFirstTokenInSecond)}s",
                )
            }
            appendLine("peak RSS ${peakRssMb()} MB, battery ${before.chargeUah - after.chargeUah} µAh used, " +
                "temp ${before.tempC}→${after.tempC} °C, thermal ${before.thermal}→${after.thermal}")
            appendLine("--- summary ---")
            appendLine(text.trim())
        }
    }

    override fun close() = engine.close()

    private class Probe(ctx: Context) {
        val chargeUah = ctx.getSystemService(BatteryManager::class.java).getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val tempC = (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val thermal = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
    }

    /** VmHWM = peak resident set size of this process. */
    private fun peakRssMb() = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM") }?.filter(Char::isDigit)?.toLongOrNull()?.div(1024)
}
