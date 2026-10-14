package com.allnetworktools.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Nothing is kept: this only deletes what earlier versions wrote to disk. */
open class HistoryStore(context: Context) {
    private val dir = context.filesDir
    private val lock = Mutex()
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
}
