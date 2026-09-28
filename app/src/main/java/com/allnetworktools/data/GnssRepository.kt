package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class Constellation(val label: String, val short: String, val prefix: String) {
    GPS("GPS", "GPS", "G"),
    Galileo("Galileo", "GAL", "E"),
    Glonass("GLONASS", "GLO", "R"),
    BeiDou("BeiDou", "BDS", "C"),
    QZSS("QZSS", "QZS", "J"),
    Other("Autre", "AUT", "S"),
}

private fun constellationOf(type: Int) = when (type) {
    GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
    GnssStatus.CONSTELLATION_GALILEO -> Constellation.Galileo
    GnssStatus.CONSTELLATION_GLONASS -> Constellation.Glonass
    GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BeiDou
    GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
    else -> Constellation.Other
}

data class Satellite(
    val constellation: Constellation,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val used: Boolean,
    val carrierMhz: Float?,
) {
    val id: String get() = constellation.prefix + svid
}

enum class FixType(val label: String) { None("Pas de fix"), Fix2D("Fix 2D"), Fix3D("Fix 3D") }

data class GnssState(
    val satellites: List<Satellite> = emptyList(),
    val location: Location? = null,
    val fix: FixType = FixType.None,
    val hdop: Float? = null,
    val ttffMs: Int? = null,
) {
    val visible get() = satellites.filter { it.elevation > 0f || it.cn0 > 0f }
    val used get() = satellites.filter { it.used }
    val constellationCount get() = visible.map { it.constellation }.filter { it != Constellation.Other }.distinct().size
}

@SuppressLint("MissingPermission")
open class GnssRepository(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)

    open fun lastKnownLocation(): Location? = runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { lm?.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    /** Satellites, GPS fixes and NMEA-derived HDOP / fix type. Requires ACCESS_FINE_LOCATION. */
    open fun status(refreshMs: Long): Flow<GnssState> = callbackFlow {
        val mgr = lm ?: run { trySend(GnssState()); awaitClose { }; return@callbackFlow }
        var state = GnssState(location = lastKnownLocation()?.takeIf { it.provider == LocationManager.GPS_PROVIDER })
        fun update(block: (GnssState) -> GnssState) {
            state = block(state); trySend(state)
        }
        val statusCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = (0 until status.satelliteCount).map { i ->
                    Satellite(
                        constellation = constellationOf(status.getConstellationType(i)),
                        svid = status.getSvid(i),
                        cn0 = status.getCn0DbHz(i),
                        elevation = status.getElevationDegrees(i),
                        azimuth = status.getAzimuthDegrees(i),
                        used = status.usedInFix(i),
                        carrierMhz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) / 1e6f else null,
                    )
                }.distinctBy { it.id }
                update { it.copy(satellites = sats) }
            }

            override fun onFirstFix(ttffMillis: Int) = update { it.copy(ttffMs = ttffMillis) }

            override fun onStopped() = update { it.copy(fix = FixType.None) }
        }
        val locationListener = LocationListener { loc ->
            update { s ->
                val fix = if (s.fix != FixType.None) s.fix else if (loc.hasAltitude()) FixType.Fix3D else FixType.Fix2D
                s.copy(location = loc, fix = fix)
            }
        }
        val nmea = OnNmeaMessageListener { msg, _ -> parseNmea(msg)?.let { (fix, hdop) -> update { s -> s.copy(fix = fix ?: s.fix, hdop = hdop ?: s.hdop) } } }
        runCatching {
            mgr.registerGnssStatusCallback(context.mainExecutor, statusCb)
            mgr.addNmeaListener(context.mainExecutor, nmea)
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, refreshMs, 0f, locationListener, context.mainLooper)
        }
        trySend(state)
        awaitClose {
            mgr.unregisterGnssStatusCallback(statusCb)
            mgr.removeNmeaListener(nmea)
            mgr.removeUpdates(locationListener)
        }
    }

    /** Fix type from GSA and HDOP from GGA/GSA. */
    private fun parseNmea(msg: String): Pair<FixType?, Float?>? {
        val body = msg.trim().substringBefore('*')
        val f = body.split(',')
        if (f.isEmpty() || f[0].length < 6) return null
        return when (f[0].substring(3)) {
            "GSA" -> {
                val fix = when (f.getOrNull(2)) { "3" -> FixType.Fix3D; "2" -> FixType.Fix2D; "1" -> FixType.None; else -> null }
                fix to f.getOrNull(16)?.toFloatOrNull()
            }
            "GGA" -> {
                val quality = f.getOrNull(6)?.toIntOrNull()
                (if (quality == 0) FixType.None else null) to f.getOrNull(8)?.toFloatOrNull()
            }
            else -> null
        }
    }
}
