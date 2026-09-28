package com.allnetworktools.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UsagePeriod(val label: String) { Day("Jour"), Week("Semaine"), Month("Mois") }

data class AppUsage(val uid: Int, val label: String, val bytes: Long)

data class UsageReport(
    val period: UsagePeriod,
    val start: Long,
    val end: Long,
    val total: Long,
    /** Hourly (day) or daily (week, month) totals, oldest first. */
    val buckets: List<Long>,
    val bucketStarts: List<Long>,
    val apps: List<AppUsage>,
)

/**
 * Mobile data counted by NetworkStatsManager. Needs the "usage access" special permission.
 * Android does not let regular apps filter by SIM (the subscriber ID is privileged), so the
 * figures cover every mobile network of the device.
 */
open class UsageRepository(private val context: Context) {
    private val stats = context.getSystemService(NetworkStatsManager::class.java)

    fun range(period: UsagePeriod, now: Long = System.currentTimeMillis()): Triple<Long, Int, Int> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return when (period) {
            UsagePeriod.Day -> Triple(cal.timeInMillis, Calendar.HOUR_OF_DAY, 24)
            UsagePeriod.Week -> { cal.add(Calendar.DAY_OF_YEAR, -6); Triple(cal.timeInMillis, Calendar.DAY_OF_YEAR, 7) }
            UsagePeriod.Month -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                Triple(cal.timeInMillis, Calendar.DAY_OF_YEAR, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            }
        }
    }

    private fun device(start: Long, end: Long): Long = runCatching {
        stats.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end).let { it.rxBytes + it.txBytes }
    }.getOrDefault(0L)

    open suspend fun mobile(period: UsagePeriod): UsageReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val (start, field, count) = range(period, now)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        val starts = mutableListOf<Long>()
        val buckets = mutableListOf<Long>()
        repeat(count) {
            val s = cal.timeInMillis
            cal.add(field, 1)
            if (s <= now) {
                starts += s
                buckets += device(s, minOf(cal.timeInMillis, now))
            }
        }
        val perUid = mutableMapOf<Int, Long>()
        runCatching {
            stats.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, now).use { ns ->
                val b = NetworkStats.Bucket()
                while (ns.hasNextBucket()) {
                    ns.getNextBucket(b)
                    perUid[b.uid] = (perUid[b.uid] ?: 0L) + b.rxBytes + b.txBytes
                }
            }
        }
        val apps = perUid.filterValues { it > 0 }.map { (uid, bytes) -> AppUsage(uid, labelFor(uid), bytes) }
            .groupBy { it.label }.map { (label, list) -> AppUsage(list.first().uid, label, list.sumOf { it.bytes }) }
            .sortedByDescending { it.bytes }
        UsageReport(period, start, now, device(start, now).takeIf { it > 0 } ?: buckets.sum(), buckets, starts, apps)
    }

    private fun labelFor(uid: Int): String {
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return "Applications désinstallées"
            NetworkStats.Bucket.UID_TETHERING -> return "Partage de connexion"
            Process.SYSTEM_UID, 0 -> return "Système Android"
        }
        val pm = context.packageManager
        val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: return "Autres (masquées par Android)"
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
}
