package com.allnetworktools.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SpeedRecord(val atMs: Long, val network: String, val icon: String, val downMbps: Float, val upMbps: Float, val pingMs: Float, val jitterMs: Float)

data class CellEvent(
    val atMs: Long,
    /** handover, up, down, lost, back. */
    val kind: String,
    val title: String,
    val detail: String,
    val level: Int?,
    val note: String = "",
    /** Radio technology after the event (NR, LTE…). */
    val tech: String? = null,
)

/** One serving-cell measurement per minute, for the signal history. */
data class SignalSample(val atMs: Long, val tech: String, val rsrp: Int?, val rsrq: Int?, val sinr: Int?)

/** Measurement history kept on the device only (JSON file in app storage). */
open class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val signalFile = File(context.filesDir, "signal.csv")
    private val _signal = MutableStateFlow<List<SignalSample>>(emptyList())
    open val signal: StateFlow<List<SignalSample>> = _signal.asStateFlow()
    private val lock = Mutex()
    private val _speed = MutableStateFlow<List<SpeedRecord>>(emptyList())
    private val _cellEvents = MutableStateFlow<List<CellEvent>>(emptyList())
    open val speed: StateFlow<List<SpeedRecord>> = _speed.asStateFlow()
    open val cellEvents: StateFlow<List<CellEvent>> = _cellEvents.asStateFlow()
    private var loaded = false

    suspend fun load(retentionDays: Int) = lock.withLock {
        if (loaded) return@withLock
        loaded = true
        withContext(Dispatchers.IO) {
            val minAt = if (retentionDays > 0) System.currentTimeMillis() - retentionDays * 86_400_000L else 0L
            if (signalFile.exists()) {
                val all = runCatching { signalFile.readLines() }.getOrDefault(emptyList()).mapNotNull(::parseSignal)
                val kept = all.filter { it.atMs >= minAt }
                _signal.value = kept
                if (kept.size != all.size) signalFile.writeText(kept.joinToString("") { signalLine(it) })
            }
            val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withContext
            _speed.value = root.optJSONArray("speed").objects().map {
                SpeedRecord(it.getLong("at"), it.getString("net"), it.getString("icon"), it.getDouble("dl").toFloat(), it.getDouble("ul").toFloat(), it.getDouble("ping").toFloat(), it.optDouble("jitter", 0.0).toFloat())
            }.filter { it.atMs >= minAt }
            _cellEvents.value = root.optJSONArray("cell").objects().map {
                CellEvent(
                    it.getLong("at"), it.getString("kind"), it.getString("title"), it.getString("detail"),
                    if (it.has("level")) it.getInt("level") else null, it.optString("note"), it.optString("tech").ifEmpty { null },
                )
            }.filter { it.atMs >= minAt }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("speed", JSONArray(_speed.value.map { JSONObject().put("at", it.atMs).put("net", it.network).put("icon", it.icon).put("dl", it.downMbps.toDouble()).put("ul", it.upMbps.toDouble()).put("ping", it.pingMs.toDouble()).put("jitter", it.jitterMs.toDouble()) }))
        root.put("cell", JSONArray(_cellEvents.value.map { e -> JSONObject().put("at", e.atMs).put("kind", e.kind).put("title", e.title).put("detail", e.detail).put("note", e.note).apply { e.level?.let { put("level", it) }; e.tech?.let { put("tech", it) } } }))
        file.writeText(root.toString())
    }

    suspend fun addSpeed(r: SpeedRecord) = lock.withLock {
        _speed.value = (listOf(r) + _speed.value).take(200)
        save()
    }

    suspend fun addCellEvent(e: CellEvent) = lock.withLock {
        _cellEvents.value = (listOf(e) + _cellEvents.value).take(2000)
        save()
    }

    private fun signalLine(s: SignalSample) = "${s.atMs},${s.tech},${s.rsrp ?: ""},${s.rsrq ?: ""},${s.sinr ?: ""}\n"

    private fun parseSignal(line: String): SignalSample? {
        val f = line.split(',')
        if (f.size < 5) return null
        return SignalSample(f[0].toLongOrNull() ?: return null, f[1], f[2].toIntOrNull(), f[3].toIntOrNull(), f[4].toIntOrNull())
    }

    /** Appends to a CSV file rather than rewriting the JSON: one line per minute adds up. */
    suspend fun addSignal(s: SignalSample) = lock.withLock {
        _signal.value = (_signal.value + s).takeLast(50_000)
        withContext(Dispatchers.IO) { runCatching { signalFile.appendText(signalLine(s)) } }
    }

    val isEmpty: Boolean get() = _speed.value.isEmpty() && _cellEvents.value.isEmpty() && _signal.value.isEmpty()

    suspend fun clear() = lock.withLock {
        _speed.value = emptyList()
        _cellEvents.value = emptyList()
        _signal.value = emptyList()
        withContext(Dispatchers.IO) { file.delete(); signalFile.delete() }
    }

    /** Raw JSON of everything stored, for export. */
    suspend fun exportJson(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val root = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            root.put("signal", JSONArray(_signal.value.map { s -> JSONObject().put("at", s.atMs).put("tech", s.tech).put("rsrp", s.rsrp).put("rsrq", s.rsrq).put("sinr", s.sinr) }))
            root.toString(2)
        }
    }
}
