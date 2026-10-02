package com.allnetworktools.data.sdr

import kotlin.math.floor

/**
 * Meshtastic modem presets: LoRa spreading factor and bandwidth. The coding rate is read from each frame's header, and
 * every preset uses sync word 0x2B. [channelName] is the name the firmware gives the default channel of that preset; it
 * feeds both the channel hash in each packet and the choice of frequency slot.
 */
enum class MeshPreset(val label: String, val channelName: String, val sf: Int, val bandwidthHz: Int) {
    LongFast("Long – rapide", "LongFast", 11, 250_000),
    LongModerate("Long – modéré", "LongMod", 11, 125_000),
    LongSlow("Long – lent", "LongSlow", 12, 125_000),
    MediumFast("Moyen – rapide", "MediumFast", 9, 250_000),
    MediumSlow("Moyen – lent", "MediumSlow", 10, 250_000),
    ShortFast("Court – rapide", "ShortFast", 7, 250_000),
    ShortSlow("Court – lent", "ShortSlow", 8, 250_000),
    ShortTurbo("Court – turbo", "ShortTurbo", 7, 500_000),
    LongTurbo("Long – turbo", "LongTurbo", 11, 500_000);

    val description: String get() = "SF$sf · ${bandwidthHz / 1000} kHz"

    /** HackRF at 2 MS/s: decimation to two samples per chip, as [LoraReceiver] expects. */
    val decimation: Int get() = 1_000_000 / bandwidthHz
}

/** A regulatory region's LoRa band, as Meshtastic defines it. */
enum class MeshRegion(val label: String, val startMhz: Double, val endMhz: Double) {
    Eu868("Europe 868", 869.4, 869.65),
    Eu433("Europe 433", 433.0, 434.0),
    Us("États-Unis / Canada", 902.0, 928.0),
    Anz("Australie / NZ 915", 915.0, 928.0),
    Nz865("Nouvelle-Zélande 865", 864.0, 868.0),
    In("Inde", 865.0, 867.0),
    Jp("Japon", 920.5, 923.5),
    Kr("Corée", 920.0, 923.0),
    Tw("Taïwan", 920.0, 925.0),
    Cn("Chine", 470.0, 510.0),
    Ru("Russie", 868.7, 869.2),
    Th("Thaïlande", 920.0, 925.0),
    My919("Malaisie 919", 919.0, 924.0),
    My433("Malaisie 433", 433.0, 435.0),
    Sg923("Singapour", 917.0, 925.0),
}

/** The channel plan a Meshtastic node derives from its region and preset when no frequency is set by hand. */
object MeshRadio {
    /** The firmware's string hash (djb2) of the channel name, as an unsigned 32-bit value. */
    fun hash(name: String): Long {
        var h = 5381L
        for (b in name.toByteArray(Charsets.UTF_8)) h = ((h shl 5) + h + (b.toLong() and 0xFF)) and 0xFFFFFFFFL
        return h
    }

    /** How many channels of the preset's width fit in the region; 0 if the band is narrower than one channel. */
    fun channels(region: MeshRegion, preset: MeshPreset): Int =
        floor((region.endMhz - region.startMhz) / (preset.bandwidthHz / 1e6) + 1e-9).toInt()

    /** The slot (0-based) used by default: the channel name's hash modulo the number of channels. */
    fun slot(region: MeshRegion, preset: MeshPreset): Int? {
        val n = channels(region, preset)
        return if (n <= 0) null else (hash(preset.channelName) % n).toInt()
    }

    /** Centre frequency in MHz of the default channel, or null when the preset does not fit the region. */
    fun frequencyMhz(region: MeshRegion, preset: MeshPreset): Double? {
        val s = slot(region, preset) ?: return null
        val bw = preset.bandwidthHz / 1e6
        return region.startMhz + bw / 2 + s * bw
    }
}
