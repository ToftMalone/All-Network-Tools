package com.allnetworktools.data.drone

/**
 * One decoded Remote ID message (ASTM F3411 / ASD-STAN EN 4709-002, the "Direct Remote ID" that drones sold in the EU and
 * the US must broadcast). Every message is 25 bytes; the layout follows the OpenDroneID reference implementation.
 * Unknown or "no value" fields decode to null.
 */
sealed interface RidMessage {
    data class BasicId(val idType: Int, val uaType: Int, val id: String) : RidMessage

    data class Location(
        val status: Int,
        val directionDeg: Double?,
        val speedMs: Double?,
        val verticalMs: Double?,
        val lat: Double?,
        val lon: Double?,
        val altBaroM: Double?,
        val altGeoM: Double?,
        val heightM: Double?,
        /** True when [heightM] is above ground level, false when above the take-off point. */
        val heightAgl: Boolean,
    ) : RidMessage

    data class SelfId(val descType: Int, val text: String) : RidMessage

    data class System(
        val operatorLocationType: Int,
        val operatorLat: Double?,
        val operatorLon: Double?,
        val operatorAltM: Double?,
        val categoryEu: Int,
        val classEu: Int,
        /** Seconds since 2019-01-01 00:00 UTC, as sent. */
        val timestampS: Long?,
    ) : RidMessage

    data class OperatorId(val type: Int, val id: String) : RidMessage
}

object RemoteId {
    const val MESSAGE_SIZE = 25

    /** Bluetooth: service data under the 16-bit UUID 0xFFFA, starting with application code 0x0D and a counter. */
    const val BLE_UUID = "0000fffa-0000-1000-8000-00805f9b34fb"
    const val APP_CODE = 0x0D

    /** Wi-Fi beacon: vendor-specific element (221) with the ASD-STAN OUI FA:0B:BC, type 0x0D, a counter, then a pack. */
    val WIFI_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())
    const val WIFI_OUI_TYPE = 0x0D

    private const val TYPE_BASIC = 0
    private const val TYPE_LOCATION = 1
    private const val TYPE_SELF = 3
    private const val TYPE_SYSTEM = 4
    private const val TYPE_OPERATOR = 5
    private const val TYPE_PACK = 0xF

    /** Decodes a message or a message pack starting at [off]; anything malformed is skipped. */
    fun decode(b: ByteArray, off: Int = 0): List<RidMessage> {
        if (off >= b.size) return emptyList()
        val type = b.u8(off) shr 4
        if (type == TYPE_PACK) {
            if (off + 3 > b.size) return emptyList()
            val size = b.u8(off + 1)
            val count = b.u8(off + 2)
            if (size != MESSAGE_SIZE || count > 9) return emptyList()
            return (0 until count).flatMap { i ->
                val o = off + 3 + i * MESSAGE_SIZE
                if (o + MESSAGE_SIZE <= b.size && b.u8(o) shr 4 != TYPE_PACK) decode(b, o) else emptyList()
            }
        }
        return if (off + MESSAGE_SIZE <= b.size) listOfNotNull(single(b, off, type)) else emptyList()
    }

    private fun single(b: ByteArray, o: Int, type: Int): RidMessage? = when (type) {
        TYPE_BASIC -> RidMessage.BasicId(b.u8(o + 1) shr 4, b.u8(o + 1) and 0xF, b.text(o + 2, 20)).takeIf { it.id.isNotEmpty() }
        TYPE_LOCATION -> {
            val flags = b.u8(o + 1)
            val ew = flags and 0x2 != 0
            val dir = b.u8(o + 2) + if (ew) 180 else 0
            val sp = b.u8(o + 3)
            val speed = if (flags and 0x1 != 0) sp * 0.75 + 255 * 0.25 else sp * 0.25
            val vs = b[o + 4].toInt()
            RidMessage.Location(
                status = flags shr 4,
                directionDeg = dir.takeIf { it <= 360 }?.toDouble(),
                speedMs = speed.takeIf { it < 255.0 },
                verticalMs = if (vs == 126) null else vs * 0.5, // 63 m/s means "unknown"
                lat = latLon(b.i32(o + 5), 90.0),
                lon = latLon(b.i32(o + 9), 180.0),
                altBaroM = alt(b.u16(o + 13)),
                altGeoM = alt(b.u16(o + 15)),
                heightM = alt(b.u16(o + 17)),
                heightAgl = flags and 0x4 != 0,
            )
        }
        TYPE_SELF -> RidMessage.SelfId(b.u8(o + 1), b.text(o + 2, 23)).takeIf { it.text.isNotEmpty() }
        TYPE_SYSTEM -> {
            val flags = b.u8(o + 1)
            val ts = b.u32(o + 20)
            RidMessage.System(
                operatorLocationType = flags and 0x3,
                operatorLat = latLon(b.i32(o + 2), 90.0),
                operatorLon = latLon(b.i32(o + 6), 180.0),
                operatorAltM = alt(b.u16(o + 18)),
                categoryEu = b.u8(o + 17) shr 4,
                classEu = b.u8(o + 17) and 0xF,
                timestampS = ts.takeIf { it != 0L },
            )
        }
        TYPE_OPERATOR -> RidMessage.OperatorId(b.u8(o + 1), b.text(o + 2, 20)).takeIf { it.id.isNotEmpty() }
        else -> null // authentication pages carry nothing to show
    }

    /** Bluetooth service data for UUID 0xFFFA: app code, counter, then one message (legacy) or a pack (long range). */
    fun fromBleServiceData(data: ByteArray): List<RidMessage> =
        if (data.size >= 2 + MESSAGE_SIZE && data.u8(0) == APP_CODE) decode(data, 2) else emptyList()

    /** Payload of a vendor-specific Wi-Fi element (after id and length): OUI, type, counter, pack. */
    fun fromWifiVendorElement(data: ByteArray): List<RidMessage> {
        if (data.size < 5 + 3 || data[0] != WIFI_OUI[0] || data[1] != WIFI_OUI[1] || data[2] != WIFI_OUI[2] || data.u8(3) != WIFI_OUI_TYPE) return emptyList()
        return decode(data, 5)
    }

    /** Manufacturer from an ANSI/CTA-2063-A serial number: its first four characters are the maker's code. */
    fun manufacturer(serial: String?): String? = when (serial?.take(4)) {
        "1581" -> "DJI"
        else -> null
    }

    fun uaTypeLabel(t: Int?): String? = when (t) {
        1 -> "Avion"
        2 -> "Multirotor"
        3 -> "Autogire"
        4 -> "Décollage vertical (VTOL)"
        5 -> "Ornithoptère"
        6 -> "Planeur"
        7 -> "Cerf-volant"
        8 -> "Ballon libre"
        9 -> "Ballon captif"
        10 -> "Dirigeable"
        11 -> "Parachute"
        12 -> "Fusée"
        13 -> "Aéronef captif"
        14 -> "Obstacle au sol"
        15 -> "Autre"
        else -> null
    }

    fun statusLabel(s: Int?): String? = when (s) {
        1 -> "Au sol"
        2 -> "En vol"
        3 -> "Urgence"
        4 -> "Panne du Remote ID"
        else -> null
    }

    fun idTypeLabel(t: Int?): String = when (t) {
        1 -> "Numéro de série"
        2 -> "Immatriculation"
        3 -> "Identifiant UTM"
        4 -> "Identifiant de session"
        else -> "Identifiant"
    }

    /** "Ouverte · C1" style label of the EU category and class, null when undeclared. */
    fun euLabel(category: Int?, cls: Int?): String? {
        val cat = when (category) { 1 -> "Ouverte"; 2 -> "Spécifique"; 3 -> "Certifiée"; else -> null }
        val c = cls?.takeIf { it in 1..7 }?.let { "C${it - 1}" }
        return listOfNotNull(cat, c).joinToString(" · ").ifEmpty { null }
    }

    private fun latLon(v: Int, max: Double): Double? = (v / 1e7).takeIf { v != 0 && it in -max..max }
    private fun alt(v: Int): Double? = if (v == 0) null else v * 0.5 - 1000

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.i32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
    private fun ByteArray.u32(i: Int) = i32(i).toLong() and 0xFFFFFFFFL
    private fun ByteArray.text(i: Int, n: Int): String =
        String(copyOfRange(i, i + n), Charsets.US_ASCII).trimEnd('\u0000', ' ').filter { it.code in 32..126 }
}

/** What one received frame carried, from which radio. */
data class RidFrame(val address: String, val transport: RidTransport, val rssi: Int, val messages: List<RidMessage>)

enum class RidTransport(val label: String) { Bluetooth("Bluetooth"), Wifi("Wi-Fi") }

/** Everything known about one drone, merged from all its messages. */
data class RemoteDrone(
    val key: String,
    val id: String?,
    val idType: Int?,
    val uaType: Int?,
    val status: Int?,
    val lat: Double?,
    val lon: Double?,
    val altGeoM: Double?,
    val altBaroM: Double?,
    val heightM: Double?,
    val heightAgl: Boolean,
    val speedMs: Double?,
    val verticalMs: Double?,
    val directionDeg: Double?,
    val operatorLat: Double?,
    val operatorLon: Double?,
    val operatorAltM: Double?,
    val operatorId: String?,
    val description: String?,
    val categoryEu: Int?,
    val classEu: Int?,
    val rssi: Int,
    val transports: Set<RidTransport>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val messages: Int,
    val track: List<Pair<Double, Double>>,
) {
    val manufacturer: String? get() = if (idType == 1) RemoteId.manufacturer(id) else null
    val isDji: Boolean get() = manufacturer == "DJI"
}

/**
 * Merges frames into drones. Bluetooth addresses can rotate and one drone may send over Bluetooth and Wi-Fi at once,
 * so frames are grouped by sender address and then by the drone's identifier once its Basic ID message has been heard.
 */
class RemoteIdTracker {
    private class Rec(val address: String, val firstMs: Long) {
        var id: String? = null
        var idType: Int? = null
        var uaType: Int? = null
        var loc: RidMessage.Location? = null
        var system: RidMessage.System? = null
        var operatorId: String? = null
        var description: String? = null
        var rssi = -127
        var lastMs = 0L
        var count = 0
        val transports = LinkedHashSet<RidTransport>()
        val track = ArrayList<Pair<Double, Double>>()
    }

    private val recs = LinkedHashMap<String, Rec>()

    fun add(f: RidFrame, nowMs: Long) {
        if (f.messages.isEmpty()) return
        val r = recs.getOrPut(f.address) { Rec(f.address, nowMs) }
        r.rssi = f.rssi; r.lastMs = nowMs; r.count++; r.transports += f.transport
        for (m in f.messages) when (m) {
            is RidMessage.BasicId -> if (r.id == null || m.idType == 1) { r.id = m.id; r.idType = m.idType; if (m.uaType != 0) r.uaType = m.uaType }
            is RidMessage.Location -> {
                r.loc = m
                if (m.lat != null && m.lon != null && r.track.lastOrNull() != (m.lat to m.lon)) {
                    r.track += m.lat to m.lon
                    if (r.track.size > MAX_TRACK) r.track.removeAt(0)
                }
            }
            is RidMessage.System -> r.system = m
            is RidMessage.OperatorId -> r.operatorId = m.id
            is RidMessage.SelfId -> r.description = m.text
        }
    }

    /** Drones heard in the last [maxAgeMs], most recent first. */
    fun snapshot(nowMs: Long, maxAgeMs: Long = 60_000): List<RemoteDrone> {
        recs.values.removeAll { nowMs - it.lastMs > maxAgeMs }
        return recs.values.groupBy { it.id ?: "@${it.address}" }.map { (key, group) ->
            val latest = group.maxBy { it.lastMs }
            fun <T> pick(f: (Rec) -> T?): T? = group.sortedByDescending { it.lastMs }.firstNotNullOfOrNull(f)
            val loc = pick { it.loc }
            val sys = pick { it.system }
            RemoteDrone(
                key = key,
                id = pick { it.id }, idType = pick { it.idType }, uaType = pick { it.uaType },
                status = loc?.status,
                lat = loc?.lat, lon = loc?.lon, altGeoM = loc?.altGeoM, altBaroM = loc?.altBaroM, heightM = loc?.heightM,
                heightAgl = loc?.heightAgl == true, speedMs = loc?.speedMs, verticalMs = loc?.verticalMs, directionDeg = loc?.directionDeg,
                operatorLat = sys?.operatorLat, operatorLon = sys?.operatorLon, operatorAltM = sys?.operatorAltM,
                operatorId = pick { it.operatorId }, description = pick { it.description },
                categoryEu = sys?.categoryEu, classEu = sys?.classEu,
                rssi = latest.rssi,
                transports = group.flatMapTo(LinkedHashSet()) { it.transports },
                firstSeenMs = group.minOf { it.firstMs }, lastSeenMs = latest.lastMs,
                messages = group.sumOf { it.count },
                track = group.maxBy { it.track.size }.track.toList(),
            )
        }.sortedByDescending { it.lastSeenMs }
    }

    fun clear() = recs.clear()

    private companion object {
        const val MAX_TRACK = 300
    }
}
