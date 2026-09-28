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

data class CellEvent(val atMs: Long, val kind: String, val title: String, val detail: String, val level: Int?)

/** Measurement history kept on the device only (JSON file in app storage). */
open class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
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
            val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withContext
            val minAt = if (retentionDays > 0) System.currentTimeMillis() - retentionDays * 86_400_000L else 0L
            _speed.value = root.optJSONArray("speed").objects().map {
                SpeedRecord(it.getLong("at"), it.getString("net"), it.getString("icon"), it.getDouble("dl").toFloat(), it.getDouble("ul").toFloat(), it.getDouble("ping").toFloat(), it.optDouble("jitter", 0.0).toFloat())
            }.filter { it.atMs >= minAt }
            _cellEvents.value = root.optJSONArray("cell").objects().map {
                CellEvent(it.getLong("at"), it.getString("kind"), it.getString("title"), it.getString("detail"), if (it.has("level")) it.getInt("level") else null)
            }.filter { it.atMs >= minAt }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("speed", JSONArray(_speed.value.map { JSONObject().put("at", it.atMs).put("net", it.network).put("icon", it.icon).put("dl", it.downMbps.toDouble()).put("ul", it.upMbps.toDouble()).put("ping", it.pingMs.toDouble()).put("jitter", it.jitterMs.toDouble()) }))
        root.put("cell", JSONArray(_cellEvents.value.map { e -> JSONObject().put("at", e.atMs).put("kind", e.kind).put("title", e.title).put("detail", e.detail).apply { e.level?.let { put("level", it) } } }))
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

    val isEmpty: Boolean get() = _speed.value.isEmpty() && _cellEvents.value.isEmpty()

    suspend fun clear() = lock.withLock {
        _speed.value = emptyList()
        _cellEvents.value = emptyList()
        withContext(Dispatchers.IO) { file.delete() }
    }

    /** Raw JSON of everything stored, for export. */
    suspend fun exportJson(): String = lock.withLock { withContext(Dispatchers.IO) { if (file.exists()) file.readText() else "{}" } }
}
