package com.allnetworktools.data

/** A MIFARE Ultralight / NTAG model, with the pages that hold user data (never lock, config or key pages). */
data class NfcChipModel(val name: String, val userBytes: Int, val firstUserPage: Int, val lastUserPage: Int) {
    val userPages: Int get() = lastUserPage - firstUserPage + 1
}

/** Capability container, page 3 of a Type 2 tag (NFC Forum T2T §6.1). */
data class NfcCapability(val magicOk: Boolean, val version: Int, val announcedBytes: Int, val readOnly: Boolean)

data class NfcChipReport(
    val uidHex: String,
    val uidBytes: Int,
    val manufacturer: String?,
    /** Exact model when the chip answered GET_VERSION, otherwise the family guessed from ATQA/SAK. */
    val chip: String,
    val exact: Boolean,
    val model: NfcChipModel?,
    val techLabels: List<String>,
    val atqa: String?,
    val sak: String?,
    val maxTransceive: Int?,
    val ndefType: String?,
    val ndefCapacity: Int?,
    val ndefUsed: Int?,
    val ndefWritable: Boolean?,
    val ndefCanLock: Boolean?,
    val capability: NfcCapability?,
    val versionHex: String?,
    /** Things that do not add up, e.g. a capability container announcing more memory than the chip has. */
    val anomalies: List<String>,
) {
    val ndefFree: Int? get() = if (ndefCapacity != null && ndefUsed != null) (ndefCapacity - ndefUsed).coerceAtLeast(0) else null
}

object NfcChip {
    /**
     * GET_VERSION (0x60) answer of NXP Ultralight EV1 / NTAG 21x: header, vendor, product type, subtype,
     * major, minor, storage size, protocol. Values from the NTAG213/215/216 and MF0ULx1 datasheets.
     */
    fun model(version: ByteArray): NfcChipModel? {
        if (version.size < 8 || version[1].toInt() != 0x04) return null
        val type = version[2].toInt() and 0xFF
        val storage = version[6].toInt() and 0xFF
        return when (type) {
            0x04 -> when (storage) {
                0x0B -> NfcChipModel("NTAG210", 48, 4, 15)
                0x0E -> NfcChipModel("NTAG212", 128, 4, 35)
                0x0F -> NfcChipModel("NTAG213", 144, 4, 39)
                0x11 -> NfcChipModel("NTAG215", 504, 4, 129)
                0x13 -> if ((version[3].toInt() and 0xFF) == 0x05) null else NfcChipModel("NTAG216", 888, 4, 225)
                else -> null
            }
            0x03 -> when (storage) {
                0x0B -> NfcChipModel("MIFARE Ultralight EV1 (MF0UL11)", 48, 4, 15)
                0x0E -> NfcChipModel("MIFARE Ultralight EV1 (MF0UL21)", 128, 4, 35)
                else -> null
            }
            else -> null
        }
    }

    /** Name for chips that answer GET_VERSION but have no page layout we test (NTAG I²C…). */
    fun versionName(version: ByteArray): String? {
        if (version.size < 8 || version[1].toInt() != 0x04) return null
        return when (version[2].toInt() and 0xFF) {
            0x04 -> if ((version[3].toInt() and 0xFF) == 0x05) "NTAG I²C" else "NTAG (modèle inconnu)"
            0x03 -> "MIFARE Ultralight (modèle inconnu)"
            else -> null
        }
    }

    val UltralightOriginal = NfcChipModel("MIFARE Ultralight", 48, 4, 15)
    val UltralightC = NfcChipModel("MIFARE Ultralight C", 144, 4, 39)

    fun capability(page3: ByteArray): NfcCapability? {
        if (page3.size < 4) return null
        return NfcCapability(
            magicOk = (page3[0].toInt() and 0xFF) == 0xE1,
            version = page3[1].toInt() and 0xFF,
            announcedBytes = (page3[2].toInt() and 0xFF) * 8,
            readOnly = (page3[3].toInt() and 0xFF) == 0x0F,
        )
    }

    /** IC manufacturer from the UID (ISO/IEC 7816-6 register). Only meaningful for fixed 7/8-byte UIDs. */
    fun manufacturer(uid: ByteArray, isoVicinity: Boolean): String? {
        val code = when {
            isoVicinity && uid.size == 8 -> uid[6].toInt() and 0xFF // NfcV UIDs arrive LSB first, E0 last.
            !isoVicinity && uid.size >= 7 -> uid[0].toInt() and 0xFF
            else -> return null
        }
        return when (code) {
            0x01 -> "Motorola"
            0x02 -> "STMicroelectronics"
            0x03 -> "Hitachi"
            0x04 -> "NXP Semiconductors"
            0x05 -> "Infineon"
            0x06 -> "Cylink"
            0x07 -> "Texas Instruments"
            0x08 -> "Fujitsu"
            0x09 -> "Matsushita"
            0x0A -> "NEC"
            0x0B -> "Oki"
            0x0C -> "Toshiba"
            0x0D -> "Mitsubishi"
            0x0E -> "Samsung"
            0x0F -> "Hynix"
            0x10 -> "LG Semiconductors"
            0x16 -> "EM Microelectronic-Marin"
            else -> null
        }
    }

    /** Family of an ISO 14443-A tag from SAK (NXP AN10833); a guess, the exact chip needs GET_VERSION. */
    fun familyFromSak(sak: Int): String? = when (sak) {
        0x00 -> "MIFARE Ultralight / NTAG (Type 2)"
        0x08 -> "MIFARE Classic 1K"
        0x09 -> "MIFARE Mini"
        0x18 -> "MIFARE Classic 4K"
        0x10, 0x11 -> "MIFARE Plus"
        0x20 -> "Carte à puce ISO 14443-4 (DESFire, carte bancaire, badge sécurisé…)"
        0x28, 0x38 -> "Carte à puce avec émulation MIFARE Classic"
        else -> null
    }

    fun anomalies(uid: ByteArray, version: ByteArray?, model: NfcChipModel?, cc: NfcCapability?): List<String> = buildList {
        if (model != null && cc != null && cc.magicOk && cc.announcedBytes > model.userBytes) {
            add("Le tag annonce ${cc.announcedBytes} octets alors qu'un ${model.name} n'en a que ${model.userBytes} : signe fréquent d'une puce contrefaite.")
        }
        if (version != null && version.size >= 2 && (version[1].toInt() and 0xFF) == 0x04 && uid.size == 7 && (uid[0].toInt() and 0xFF) != 0x04) {
            add("La puce se présente comme NXP mais son UID ne commence pas par 04 (code fabricant NXP) : copie ou clone probable.")
        }
        if (cc != null && !cc.magicOk && model != null) {
            add("Le conteneur de capacités (page 3) n'est pas au format NDEF : le tag n'a jamais été formaté ou a été réécrit à la main.")
        }
    }

    fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02X".format(it) }
}

// ---- endurance test --------------------------------------------------------------------------------

enum class PageFault { WriteRefused, Mismatch }

data class EnduranceReport(
    val chip: String,
    val pageLevel: Boolean,
    val cycles: Int,
    val pagesTested: Int,
    val faults: Map<Int, PageFault>,
    val avgWriteMs: Double,
    val avgReadMs: Double,
    /** Set when the test could not finish (tag moved away, locked tag…). */
    val aborted: String?,
    val restored: Boolean,
) {
    val ok: Boolean get() = aborted == null && faults.isEmpty()
}

data class EnduranceProgress(val cycle: Int, val cycles: Int, val done: Int, val total: Int)

/** Pseudo-random but reproducible page content for one cycle, so a stuck bit shows up as a mismatch. */
fun testPattern(cycle: Int, page: Int): ByteArray {
    var x = (cycle * 7919 + page * 104729 + 0x5A5A) * 1103515245 + 12345
    return ByteArray(4) { i ->
        x = x * 1103515245 + 12345 + i
        (x ushr 16).toByte()
    }
}

// ---- antenna map -----------------------------------------------------------------------------------

/** How well a tag held at one spot answered a burst of reads. */
data class LinkSample(val attempts: Int, val successes: Int, val avgLatencyMs: Double?) {
    val rate: Double get() = if (attempts == 0) 0.0 else successes.toDouble() / attempts

    /** 0–100: mostly the success rate, minus a little for slow answers. */
    val score: Int
        get() {
            if (attempts == 0) return 0
            val latencyPenalty = ((avgLatencyMs ?: 0.0) - 8.0).coerceIn(0.0, 40.0) / 2
            return (rate * 100 - latencyPenalty).coerceIn(0.0, 100.0).toInt()
        }
}
