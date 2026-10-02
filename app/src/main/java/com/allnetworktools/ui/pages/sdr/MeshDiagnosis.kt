package com.allnetworktools.ui.pages.sdr

import java.util.Locale

/** How far reception has got, from the USB stream to decoded packets. */
enum class MeshHealth { Idle, NoUsb, NoSignal, OtherNetwork, WeakOrMismatched, BadCrc, OtherChannel, Working, Waiting }

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
        val gap = if (d.peakDb != null && d.noiseDb != null) d.peakDb - d.noiseDb else null
        if (d.preambles == 0 && (gap == null || gap < 3.0)) return MeshVerdict(
            MeshHealth.NoSignal,
            "Aucun signal LoRa dans la dernière minute. Vérifiez l'antenne (868 / 915 MHz), les gains et la fréquence. Les nœuds Meshtastic n'émettent que toutes les quelques minutes : laissez écouter 15 minutes.",
        )
        return MeshVerdict(MeshHealth.Waiting, "Signal détecté, en attente d'une trame complète.")
    }
}
