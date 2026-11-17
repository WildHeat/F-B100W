package dev.trrrst.watch.data

import android.content.Context
import dev.trrrst.watch.SyncTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One sync attempt. [time] is epoch milliseconds. [error] is plain English (from
 *  SyncMessages), never exception text. */
data class SyncRecord(
    val time: Long,
    val trigger: SyncTrigger,
    val ok: Boolean,
    val steps: Int? = null,
    val error: String? = null,
)

/** Pure list handling + JSON for the sync log (PLAN 1.4). Newest record first. */
object SyncLog {
    const val MAX = 10

    fun add(log: List<SyncRecord>, record: SyncRecord, max: Int = MAX): List<SyncRecord> =
        (listOf(record) + log).take(max)

    fun toJson(log: List<SyncRecord>): String {
        val arr = JSONArray()
        log.forEach { r ->
            val o = JSONObject()
                .put("time", r.time)
                .put("trigger", r.trigger.name)
                .put("ok", r.ok)
            r.steps?.let { o.put("steps", it) }
            r.error?.let { o.put("error", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Never throws: unreadable data gives an empty log, bad entries are skipped. */
    fun fromJson(json: String?): List<SyncRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                SyncRecord(
                    time = o.getLong("time"),
                    trigger = SyncTrigger.valueOf(o.getString("trigger")),
                    ok = o.getBoolean("ok"),
                    steps = if (o.has("steps")) o.getInt("steps") else null,
                    error = if (o.has("error")) o.getString("error") else null,
                )
            }.getOrNull()
        }.take(MAX)
    }
}

/** The last [SyncLog.MAX] syncs, kept in SharedPreferences. For the sync history
 *  list (Phase 5) and the widget (Phase 7); not shown yet. */
class SyncLogStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("watch", Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(SyncLog.fromJson(prefs.getString(KEY, null)))
    val records: StateFlow<List<SyncRecord>> = _records

    @Synchronized
    fun add(record: SyncRecord) {
        val updated = SyncLog.add(_records.value, record)
        prefs.edit().putString(KEY, SyncLog.toJson(updated)).apply()
        _records.value = updated
    }

    private companion object {
        const val KEY = "syncLog"
    }
}
