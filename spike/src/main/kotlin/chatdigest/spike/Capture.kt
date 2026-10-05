package chatdigest.spike

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chatdigest.core.CaptureEvent
import chatdigest.core.CapturedMsg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/** Logs every WhatsApp post/remove verbatim. Tier 0 measures survival, so it never self-heals. */
class CaptureListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        CaptureLog.append(this, JSONObject().put("kind", "connect"))
        Heartbeat.schedule(this)
    }

    override fun onListenerDisconnected() {
        connected = false
        CaptureLog.append(this, JSONObject().put("kind", "disconnect"))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val msgs = JSONArray()
        style?.messages?.forEach {
            msgs.put(JSONObject().put("time", it.timestamp).put("sender", it.person?.name?.toString()).put("text", it.text?.toString()))
        }
        CaptureLog.append(
            this,
            JSONObject()
                .put("kind", "post")
                .put("pkg", sbn.packageName)
                .put("key", sbn.key)
                .put("postTime", sbn.postTime)
                .put("groupKey", sbn.groupKey)
                .put("groupSummary", n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
                .put("shortcutId", n.shortcutId)
                .put("chat", (style?.conversationTitle ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString())
                .put("isGroup", style?.isGroupConversation)
                .put("hasContentIntent", n.contentIntent != null)
                .put("messages", msgs)
                .put("extras", dump(n.extras)),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        CaptureLog.append(
            this,
            JSONObject()
                .put("kind", "remove")
                .put("key", sbn.key)
                .put("reason", reason)
                .put("chat", (style?.conversationTitle ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()),
        )
    }

    /** Every extra as a string, including the raw per-message bundles (edits/deletions show up here). */
    private fun dump(b: Bundle): JSONObject {
        val o = JSONObject()
        for (k in b.keySet()) {
            @Suppress("DEPRECATION")
            val v = b.get(k)
            o.put(k, when (v) {
                is Array<*> -> JSONArray(v.map { (it as? Bundle)?.let(::dump) ?: it.toString().take(500) })
                is Bundle -> dump(v)
                else -> v?.toString()?.take(2000)
            })
        }
        return o
    }

    companion object {
        @Volatile var connected = false
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CaptureLog.append(context, JSONObject().put("kind", "boot"))
    }
}

class Heartbeat(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CaptureLog.append(applicationContext, JSONObject().put("kind", "heartbeat").put("state", deviceState(applicationContext)))
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "heartbeat", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<Heartbeat>(15, TimeUnit.MINUTES).build(),
        )
    }
}

fun deviceState(ctx: Context): JSONObject {
    val pm = ctx.getSystemService(PowerManager::class.java)
    val bm = ctx.getSystemService(BatteryManager::class.java)
    return JSONObject()
        .put("listenerConnected", CaptureListener.connected)
        .put("batteryExempt", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        .put("powerSave", pm.isPowerSaveMode)
        .put("deviceIdle", pm.isDeviceIdleMode)
        .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
}

object CaptureLog {
    fun file(ctx: Context) = File(ctx.filesDir, "capture.jsonl")

    @Synchronized
    fun append(ctx: Context, o: JSONObject) = file(ctx).appendText(o.put("at", System.currentTimeMillis()).toString() + "\n")

    fun read(ctx: Context): List<CaptureEvent> = file(ctx).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
        runCatching {
            val o = JSONObject(line)
            val ms = o.optJSONArray("messages") ?: JSONArray()
            CaptureEvent(
                at = o.getLong("at"),
                kind = o.getString("kind"),
                chat = o.optString("chat").takeIf { o.has("chat") && !o.isNull("chat") },
                messages = (0 until ms.length()).map { i ->
                    ms.getJSONObject(i).let { CapturedMsg(it.getLong("time"), it.optString("sender"), it.optString("text")) }
                },
                groupSummary = o.optBoolean("groupSummary"),
                removeReason = o.optInt("reason"),
            )
        }.getOrNull()
    }
}
