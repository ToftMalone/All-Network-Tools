package com.allnetworktools.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Networks whose tags can be used to follow someone. */
enum class TrackerNet(val label: String, val maker: String) {
    AppleFindMy("Localiser (Apple)", "AirTag ou accessoire Localiser"),
    GoogleFindHub("Localiser (Google)", "Tag du réseau Google"),
    SamsungFind("SmartThings Find", "Galaxy SmartTag"),
    Tile("Tile", "Tile"),
}

/**
 * What an advertisement says about a tracker. [separated] is true when the tag announces it is away
 * from its owner (Apple offline-finding frame, Google "unwanted tracking protection" frame), false when
 * it announces its owner is nearby, and null when the protocol does not tell.
 */
data class TrackerSignal(val net: TrackerNet, val separated: Boolean?)

data class Sighting(val timeMs: Long, val lat: Double?, val lon: Double?, val rssi: Int)

enum class FollowLevel(val label: String) {
    Following("Vous suit"),
    Watch("À surveiller"),
    Nearby("À proximité"),
    WithOwner("Avec son propriétaire"),
}

data class FollowAssessment(
    val level: FollowLevel,
    val durationMs: Long,
    /** Largest distance between two places the tag was seen, in metres; null without positions. */
    val spreadM: Double?,
    val places: Int,
    val sightings: Int,
)

object TrackerDetect {
    /** Minimum time with you before a separated tag is reported as following. */
    const val FollowMs = 10 * 60_000L
    const val FollowSpreadM = 300.0
    const val WatchMs = 5 * 60_000L
    const val WatchSpreadM = 100.0
    const val PlaceRadiusM = 150.0

    fun classify(ads: List<AdStructure>): TrackerSignal? {
        Ad.manufacturer(ads)[0x004C]?.let { d ->
            // Continuity TLV: type 0x12 is Find My. A 25-byte payload carries the full rotating key, which
            // accessories only broadcast in "offline finding" mode, i.e. away from their owner's devices.
            var i = 0
            while (i + 2 <= d.size) {
                val t = d.u8(i)
                val l = d.u8(i + 1)
                if (t == 0x12) return TrackerSignal(TrackerNet.AppleFindMy, l >= 0x19)
                if (t == 0) break
                i += 2 + l
            }
        }
        val sd = Ad.serviceData(ads)
        sd[0xFEAA]?.takeIf { it.isNotEmpty() }?.let { d ->
            // Find Hub network (FMDN): frame 0x40, or 0x41 while "unwanted tracking protection" is on.
            when (d.u8(0)) {
                0x40 -> return TrackerSignal(TrackerNet.GoogleFindHub, false)
                0x41 -> return TrackerSignal(TrackerNet.GoogleFindHub, true)
            }
        }
        val uuids = Ad.uuids16(ads)
        if (0xFD5A in uuids) return TrackerSignal(TrackerNet.SamsungFind, null)
        if (uuids.any { it == 0xFEED || it == 0xFEEC || it == 0xFD84 }) return TrackerSignal(TrackerNet.Tile, null)
        return null
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_008.8 * asin(sqrt(a))
    }

    fun assess(signal: TrackerSignal, s: List<Sighting>): FollowAssessment {
        val duration = if (s.isEmpty()) 0L else s.maxOf { it.timeMs } - s.minOf { it.timeMs }
        val located = s.filter { it.lat != null && it.lon != null }
        var spread: Double? = null
        val places = mutableListOf<Pair<Double, Double>>()
        if (located.isNotEmpty()) {
            val first = located.first()
            spread = located.maxOf { distanceM(first.lat!!, first.lon!!, it.lat!!, it.lon!!) }
            located.forEach { p ->
                if (places.none { distanceM(it.first, it.second, p.lat!!, p.lon!!) < PlaceRadiusM }) places += p.lat!! to p.lon!!
            }
        }
        val level = when {
            signal.separated == false -> FollowLevel.WithOwner
            spread != null && duration >= FollowMs && (spread >= FollowSpreadM || places.size >= 3) -> FollowLevel.Following
            spread == null && duration >= 2 * FollowMs -> FollowLevel.Watch
            duration >= WatchMs || (spread ?: 0.0) >= WatchSpreadM -> FollowLevel.Watch
            else -> FollowLevel.Nearby
        }
        return FollowAssessment(level, duration, spread, places.size, s.size)
    }
}
