package com.allnetworktools.data

import android.content.Context
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A country outline: rings of (lon, lat) pairs in degrees, stored flat as [lon0, lat0, lon1, lat1, …]. */
class Country(val name: String, val rings: List<FloatArray>) {
    private val minLon = rings.minOf { r -> (r.indices step 2).minOf { r[it] } }
    private val maxLon = rings.maxOf { r -> (r.indices step 2).maxOf { r[it] } }
    private val minLat = rings.minOf { r -> (1 until r.size step 2).minOf { r[it] } }
    private val maxLat = rings.maxOf { r -> (1 until r.size step 2).maxOf { r[it] } }

    fun contains(lat: Float, lon: Float): Boolean {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
        return rings.any { r -> inRing(r, lat, lon) }
    }

    private fun inRing(r: FloatArray, lat: Float, lon: Float): Boolean {
        var inside = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[i * 2]; val yi = r[i * 2 + 1]
            val xj = r[j * 2]; val yj = r[j * 2 + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/** Natural Earth 1:110m countries (public domain), bundled as assets/world/countries.txt. */
object WorldMap {
    @Volatile private var cache: List<Country>? = null

    fun parse(text: String): List<Country> = text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val parts = line.split('|')
        Country(
            parts[0],
            parts.drop(1).map { ring ->
                val pts = ring.split(' ')
                FloatArray(pts.size * 2).also { out ->
                    pts.forEachIndexed { i, p ->
                        val c = p.indexOf(',')
                        out[i * 2] = p.substring(0, c).toInt() / 10f
                        out[i * 2 + 1] = p.substring(c + 1).toInt() / 10f
                    }
                }
            },
        )
    }.toList()

    suspend fun load(context: Context): List<Country> = cache ?: withContext(Dispatchers.IO) {
        parse(context.assets.open("world/countries.txt").bufferedReader().readText()).also { cache = it }
    }

    fun countryAt(countries: List<Country>, lat: Double, lon: Double): String? =
        countries.firstOrNull { it.contains(lat.toFloat(), lon.toFloat()) }?.name

    /** Rough ocean name for points outside every country. */
    fun oceanAt(lat: Double, lon: Double): String = when {
        lat < -60 -> "Océan Austral"
        lat > 66 -> "Océan Arctique"
        // Enclosed and marginal seas first: the broad ocean boxes below would swallow them.
        lat in 48.5..51.3 && lon in -5.5..2.0 -> "La Manche"
        lat in 51.0..61.0 && lon in -4.0..9.0 -> "Mer du Nord"
        lat in 53.5..66.0 && lon in 9.0..30.5 -> "Mer Baltique"
        lat in 43.0..48.5 && lon in -10.0..-1.0 -> "Golfe de Gascogne"
        lat in 61.0..66.0 && lon in -5.0..15.0 -> "Mer de Norvège"
        lat in 40.5..47.0 && lon in 27.0..42.0 -> "Mer Noire"
        lat in 36.5..47.5 && lon in 46.5..55.0 -> "Mer Caspienne"
        lat in 30.0..46.0 && lon in -6.0..36.5 -> "Mer Méditerranée"
        lat in 12.0..30.0 && lon in 32.0..44.0 -> "Mer Rouge"
        lat in 23.5..30.5 && lon in 47.5..57.0 -> "Golfe Persique"
        lon in 20.0..146.0 && lat < 25 && !(lon > 100 && lat > 0) -> "Océan Indien"
        lat >= 0 && lon in -100.0..-5.0 && !(lon < -80 && lat < 9) -> "Océan Atlantique"
        lat < 0 && lon in -68.0..20.0 -> "Océan Atlantique"
        lat >= 0 && lon in -5.0..15.0 -> "Océan Atlantique"
        else -> "Océan Pacifique"
    }
}

/** Sub-satellite points from the observer's position and the satellite's azimuth / elevation. */
object SatGeo {
    private const val EarthKm = 6371.0
    private const val GeoKm = 35_786.0
    private val BeidouGeoIgso = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13, 16, 31, 38, 39, 40, 56, 59, 60, 61, 62, 63)

    /** Nominal orbit altitude: MEO constellations, GEO/IGSO for SBAS, QZSS, NavIC and part of BeiDou. */
    fun altitudeKm(c: Constellation, svid: Int): Double = when (c) {
        Constellation.GPS -> 20_180.0
        Constellation.Glonass -> 19_130.0
        Constellation.Galileo -> 23_222.0
        Constellation.BeiDou -> if (svid in BeidouGeoIgso) GeoKm else 21_528.0
        Constellation.QZSS, Constellation.Other -> GeoKm
    }

    /**
     * Point on Earth directly below the satellite (lat, lon in degrees). The Earth central angle
     * between observer and sub-point is ψ = 90° − el − asin(R·cos(el) / (R + h)).
     */
    fun subPoint(latDeg: Double, lonDeg: Double, azDeg: Double, elDeg: Double, altKm: Double): Pair<Double, Double> {
        val el = Math.toRadians(elDeg.coerceIn(0.0, 90.0))
        val psi = PI / 2 - el - asin(EarthKm * cos(el) / (EarthKm + altKm))
        val lat1 = Math.toRadians(latDeg)
        val az = Math.toRadians(azDeg)
        val lat2 = asin(sin(lat1) * cos(psi) + cos(lat1) * sin(psi) * cos(az))
        val lon2 = Math.toRadians(lonDeg) + atan2(sin(az) * sin(psi) * cos(lat1), cos(psi) - sin(lat1) * sin(lat2))
        val lon = (Math.toDegrees(lon2) + 540) % 360 - 180
        return Math.toDegrees(lat2) to lon
    }

    fun subPoint(latDeg: Double, lonDeg: Double, s: Satellite) =
        subPoint(latDeg, lonDeg, s.azimuth.toDouble(), s.elevation.toDouble(), altitudeKm(s.constellation, s.svid))
}
