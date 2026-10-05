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

data class SpeedRecord(val atMs: Long, val network: String, val icon: String, val downMbps: Float, val upMbps: Float, val pingMs: Float, val jitterMs: Float)

/** Speed-test results of the session, kept in memory only: nothing is written to disk. */
open class HistoryStore(context: Context) {
    private val dir = context.filesDir
    private val lock = Mutex()
    private val _speed = MutableStateFlow<List<SpeedRecord>>(emptyList())
    open val speed: StateFlow<List<SpeedRecord>> = _speed.asStateFlow()
    private var cleaned = false

    /** Removes the files written by earlier versions (history, signal samples, NMEA captures). */
    suspend fun purgeLegacyFiles() = lock.withLock {
        if (cleaned) return@withLock
        cleaned = true
        withContext(Dispatchers.IO) {
            File(dir, "history.json").delete()
            File(dir, "signal.csv").delete()
            File(dir, "nmea").deleteRecursively()
        }
    }

    suspend fun addSpeed(r: SpeedRecord) = lock.withLock {
        _speed.value = (listOf(r) + _speed.value).take(200)
    }
}
