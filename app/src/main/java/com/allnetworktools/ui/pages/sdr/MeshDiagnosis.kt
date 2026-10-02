package com.allnetworktools.ui.pages.sdr

import java.util.Locale

/** How far reception has got, from the USB stream to decoded packets. */
enum class MeshHealth { Idle, NoUsb, NoSignal, OtherNetwork, WeakOrMismatched, BadCrc, Overload, OtherChannel, Working, Waiting }

class MeshVerdict(val health: MeshHealth, val text: String)

/** The numbers behind a verdict; kept separate from the controller so it can be tested without a radio. */
class MeshDiagInput(
    val running: Boolean,
    val usbMBps: Double,
    val levelDb: Double?,
    val noiseDb: Double?,
    val peakDb: Double?,
    val preambles: Int,
    val syncMismatches: Int,
    val headerErrors: Int,
    val lastSyncSeen: Int,
    val framesOk: Int,
    val framesBad: Int,
    val decoded: Int,
    val otherChannel: Int,
    val dropped: Int = 0,
    val offsetKHz: Double? = null,
    val widthKHz: Double? = null,
    val bandwidthKHz: Double = 250.0,
    val signal: Boolean = false,
    val sfdLost: Int = 0,
)

object MeshDiagnosis {
    /** At 2 MS/s the HackRF streams 4 MB/s; far below that, samples are being lost or not arriving. */
    const val EXPECTED_MBPS = 4.0

    fun verdict(d: MeshDiagInput): MeshVerdict {
        if (!d.running) return MeshVerdict(MeshHealth.Idle, "Lancez l'écoute pour voir les mesures de réception.")
        if (d.usbMBps < 0.5) return MeshVerdict(
            MeshHealth.NoUsb,
            "Aucun flux de données du HackRF (${"%.1f".format(Locale.FRANCE, d.usbMBps)} Mo/s au lieu de ${EXPECTED_MBPS.toInt()}). Débranchez et rebranchez le HackRF, ou essayez un autre câble OTG.",
        )
        if (d.framesOk > 0 && d.decoded > 0) return MeshVerdict(MeshHealth.Working, "Réception correcte : ${d.decoded} paquets lisibles sur les canaux configurés.")
        if (d.framesOk > 0 && d.otherChannel >= d.framesOk) return MeshVerdict(
            MeshHealth.OtherChannel,
            "Des paquets Meshtastic arrivent, mais d'un canal dont vous n'avez pas la clé. Ajoutez le canal (nom et clé) dans la page Canaux.",
        )
        if (d.preambles > 0 && d.syncMismatches >= d.preambles && d.framesOk == 0 && d.framesBad == 0) {
            val word = if (d.lastSyncSeen >= 0) " (mot de synchro 0x%02X)".format(d.lastSyncSeen) else ""
            val hint = when (d.lastSyncSeen) { 0x34 -> " C'est du LoRaWAN." ; 0x12 -> " C'est un réseau LoRa privé." ; else -> "" }
            return MeshVerdict(MeshHealth.OtherNetwork, "Des signaux LoRa passent mais ce n'est pas du Meshtastic$word.$hint")
        }
        if (d.framesBad > 0 && d.framesOk == 0) return MeshVerdict(
            MeshHealth.BadCrc,
            "Des trames sont reçues mais leur contrôle (CRC) échoue : signal trop faible ou saturé. Ajustez les gains LNA et VGA, ou rapprochez-vous d'un nœud.",
        )
        if (d.preambles > 0 && d.headerErrors > 0 && d.framesOk == 0) return MeshVerdict(
            MeshHealth.WeakOrMismatched,
            "Des débuts de trame sont détectés mais illisibles : signal trop faible, ou préréglage différent de celui du réseau (essayez un autre préréglage).",
        )
        if (d.dropped > 20) return MeshVerdict(
            MeshHealth.Overload,
            "Le téléphone n'arrive pas à suivre le flux (${d.dropped} blocs perdus) : des trames sont abîmées. Fermez les autres applications et coupez l'économiseur de batterie.",
        )
        val gap = if (d.peakDb != null && d.noiseDb != null) d.peakDb - d.noiseDb else null
        if (d.preambles == 0 && (gap == null || gap < 3.0)) return MeshVerdict(
            MeshHealth.NoSignal,
            "Aucun signal LoRa dans la dernière minute. Vérifiez l'antenne (868 / 915 MHz), les gains et la fréquence. Les nœuds Meshtastic n'émettent que toutes les quelques minutes : laissez écouter 15 minutes.",
        )
        val seen = StringBuilder("Signal détecté, en attente d'une trame complète.")
        val off = d.offsetKHz
        val w = d.widthKHz
        if (d.signal && off != null && w != null) {
            seen.append(" Mesuré : décalage %+.0f kHz, largeur %.0f kHz.".format(Locale.FRANCE, off, w))
            if (kotlin.math.abs(off) > 40) seen.append(" Ce décalage est trop grand pour un nœud sur ce canal : vérifiez la fréquence ou le créneau du préréglage.")
            else if (w < d.bandwidthKHz * 0.6) seen.append(" Le signal est plus étroit que ce préréglage (${d.bandwidthKHz.toInt()} kHz) : essayez un préréglage en 125 kHz.")
            else if (w > d.bandwidthKHz * 1.5) seen.append(" Le signal est plus large que ce préréglage (${d.bandwidthKHz.toInt()} kHz) : ce n'est peut-être pas du LoRa.")
        }
        if (d.preambles > 0 && d.sfdLost >= d.preambles) seen.append(" Des débuts de trame sont vus mais la suite se perd : essayez de baisser le gain ou de rapprocher le nœud.")
        return MeshVerdict(MeshHealth.Waiting, seen.toString())
    }
}
