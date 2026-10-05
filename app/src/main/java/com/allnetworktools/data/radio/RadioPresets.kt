package com.allnetworktools.data.radio

import kotlin.math.roundToLong

/** One frequency of a preset list; the transmit side is derived from [shiftHz] and the "listen only" choice. */
data class PresetChannel(
    val name: String,
    val rxHz: Long,
    /** Transmit minus receive, 0 for simplex. */
    val shiftHz: Long = 0,
    val wide: Boolean = true,
    val tone: Tone = Tone.None,
    /** Downlinks and the like: never transmit, whatever the user chose. */
    val listenOnly: Boolean = false,
)

/** A ready-made list of memories: licence-free bands, marine VHF, amateur calling channels and repeater outputs. */
data class RadioPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Marine channels and the like: the app never lets the radio transmit on them. */
    val alwaysListenOnly: Boolean,
    /** Shown under the list; the rules that apply to transmitting there. */
    val note: String,
    val channels: List<PresetChannel>,
) {
    /** The memories, starting at [firstSlot]; transmit is blocked when [listenOnly] (or the preset demands it). */
    fun toChannels(firstSlots: List<Int>, listenOnly: Boolean, power: RadioPower = RadioPower.Low): List<RadioChannel> {
        val block = listenOnly || alwaysListenOnly
        return channels.zip(firstSlots).map { (c, slot) ->
            RadioChannel(
                slot = slot, rxHz = c.rxHz, txHz = if (block || c.listenOnly) null else c.rxHz + c.shiftHz, name = c.name,
                rxTone = Tone.None, txTone = c.tone, power = power, wide = c.wide, scan = true,
            )
        }
    }
}

object RadioPresets {
    private fun mhz(v: Double) = (v * 1_000_000).roundToLong()

    private val pmr = RadioPreset(
        "pmr446", "PMR446", "16 canaux, 446,00625 à 446,19375 MHz", alwaysListenOnly = false,
        note = "Émettre en PMR446 demande un appareil homologué, à antenne fixe et de 0,5 W au plus : un talkie programmable ne l'est pas.",
        channels = (0 until 16).map { PresetChannel("PMR ${it + 1}", mhz(446.00625) + it * 12_500L, wide = false) },
    )

    private val lpd = RadioPreset(
        "lpd433", "LPD433", "69 canaux, 433,075 à 434,775 MHz", alwaysListenOnly = false,
        note = "Bande libre à 10 mW au plus : un talkie de plusieurs watts n'a pas le droit d'y émettre.",
        channels = (0 until 69).map { PresetChannel("LPD ${it + 1}", mhz(433.075) + it * 25_000L) },
    )

    private val marine = RadioPreset(
        "marine", "VHF marine", "Canaux simplex, 156,3 à 156,875 MHz", alwaysListenOnly = true,
        note = "Écoute seule : émettre en marine exige un appareil et un certificat dédiés.",
        channels = listOf(
            6 to 156.300, 8 to 156.400, 9 to 156.450, 10 to 156.500, 11 to 156.550, 12 to 156.600, 13 to 156.650, 14 to 156.700,
            15 to 156.750, 16 to 156.800, 17 to 156.850, 67 to 156.375, 68 to 156.425, 69 to 156.475, 71 to 156.575, 72 to 156.625,
            73 to 156.675, 74 to 156.725, 77 to 156.875,
        ).sortedBy { it.second }.map { (ch, f) -> PresetChannel("MAR $ch", mhz(f)) },
    )

    private val relays2m = RadioPreset(
        "relais2m", "Relais radioamateur 2 m", "RV48 à RV63, sorties 145,600 à 145,7875 MHz, décalage −600 kHz", alwaysListenOnly = false,
        note = "Émettre demande une licence radioamateur. Beaucoup de relais exigent une tonalité d'accès (souvent 88,5 Hz) : à régler canal par canal.",
        channels = (0 until 16).map { PresetChannel("RV${48 + it}", mhz(145.600) + it * 12_500L, shiftHz = -600_000L, wide = false) },
    )

    private val amateur = RadioPreset(
        "radioamateur", "Radioamateur : appels et spéciaux", "Appel 2 m et 70 cm, APRS, ISS", alwaysListenOnly = false,
        note = "Émettre demande une licence radioamateur. L'ISS ne s'écoute qu'en réception.",
        channels = listOf(
            PresetChannel("APPEL2M", mhz(145.500), wide = false),
            PresetChannel("APPEL70", mhz(433.500), wide = false),
            PresetChannel("APRS", mhz(144.800), wide = false),
            PresetChannel("ISS VOX", mhz(145.800), wide = false, listenOnly = true),
            PresetChannel("ISS PKT", mhz(145.825), wide = false, listenOnly = true),
        ),
    )

    val all = listOf(pmr, lpd, marine, relays2m, amateur)
}
