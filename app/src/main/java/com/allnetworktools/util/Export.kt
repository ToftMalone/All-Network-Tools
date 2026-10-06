package com.allnetworktools.util

import com.allnetworktools.ui.pages.sdr.AprsInfo
import com.allnetworktools.ui.pages.sdr.Plane
import com.allnetworktools.ui.pages.sdr.Ship
import com.allnetworktools.ui.pages.sdr.SondeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the files the receivers export when asked: comma-separated values with a header line, dots for decimals and
 * UTC times, so any spreadsheet or mapping tool reads them. Nothing here touches storage; the user picks where the file goes.
 */
object Export {
    private fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** File name stamp in local time, e.g. 2026-10-06_14-32. */
    fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(ms))

    private fun cell(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Double -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun csv(header: List<String>, rows: List<List<Any?>>): String = buildString {
        append(header.joinToString(",")).append("\r\n")
        rows.forEach { r -> append(r.joinToString(",") { cell(it) }).append("\r\n") }
    }

    fun planes(list: List<Plane>, nowMs: Long): String = csv(
        listOf("dernier_message_utc", "icao", "indicatif", "latitude", "longitude", "altitude_ft", "vitesse_kt", "cap_deg", "vario_ft_min", "messages", "niveau_db"),
        list.map { p -> listOf(utc(nowMs - p.ageS * 1000L), p.hex, p.callsign?.trim(), p.lat, p.lon, p.altitudeFt, p.speedKt, p.headingDeg, p.verticalFtMin, p.messages, p.levelDb) },
    )

    fun ships(list: List<Ship>, nowMs: Long): String = csv(
        listOf("dernier_message_utc", "mmsi", "nom", "indicatif", "type", "destination", "statut", "latitude", "longitude", "vitesse_kn", "route_deg", "cap_deg", "messages", "aide_navigation", "station_base"),
        list.map { s -> listOf(utc(nowMs - s.ageS * 1000L), s.mmsi, s.name, s.callsign, s.shipType, s.destination, s.navStatus, s.lat, s.lon, s.sogKn, s.cogDeg, s.heading, s.messages, s.aid, s.base) },
    )

    /** One line per sonde, its flight path in the last column as "lat lon" pairs separated by '|'. */
    fun sondes(list: List<SondeInfo>, nowMs: Long): String = csv(
        listOf("derniere_trame_utc", "numero", "latitude", "longitude", "altitude_m", "altitude_max_m", "vitesse_verticale_m_s", "vitesse_m_s", "route_deg", "batterie_v", "satellites", "trames", "trajet"),
        list.map { s ->
            listOf(
                utc(nowMs - s.ageS * 1000L), s.serial, s.lat, s.lon, s.altitudeM, s.maxAltitudeM, s.climbMs, s.speedMs, s.courseDeg, s.batteryV, s.satellites, s.frames,
                s.track.joinToString("|") { (la, lo) -> "${cell(la)} ${cell(lo)}" },
            )
        },
    )

    fun aprs(list: List<AprsInfo>, nowMs: Long): String = csv(
        listOf("dernier_paquet_utc", "indicatif", "objet", "latitude", "longitude", "altitude_m", "vitesse_kn", "route_deg", "symbole", "commentaire", "statut", "vent_deg", "vent_mph", "rafales_mph", "temperature_f", "humidite_pct", "pression_hpa", "via", "paquets"),
        list.map { a ->
            val w = a.weather
            listOf(
                utc(nowMs - a.ageS * 1000L), a.title, a.isObject, a.lat, a.lon, a.altitudeM, a.speedKn, a.courseDeg, a.symbol, a.comment, a.status,
                w?.windDir, w?.windMph, w?.gustMph, w?.tempF, w?.humidity, w?.pressureHpa, a.via, a.packets,
            )
        },
    )
}
