package com.allnetworktools.data

import android.nfc.NdefMessage
import android.nfc.NdefRecord

/** What an NDEF record turned out to contain, for display. */
enum class NfcRecordKind(val label: String) {
    Link("Lien"), Text("Texte"), Phone("Numéro de téléphone"), Contact("Contact"),
    WifiHandover("Configuration Wi-Fi"), App("Ouvrir une application"), SmartPoster("Smart Poster"),
    Mime("Données"), Unknown("Enregistrement non reconnu"),
}

data class NfcRecordInfo(val kind: NfcRecordKind, val title: String, val detail: String?, val payloadSize: Int)

/** Everything read off one tag, independent of how it was obtained. */
data class NfcTagInfo(
    val uidHex: String,
    val techs: List<String>,
    /** Human names of [techs], in the order a user should read them. */
    val techLabels: List<String>,
    val memoryBytes: Int?,
    val ndefRecords: List<NfcRecordInfo>,
    val ndefWritable: Boolean,
    val ndefCanLock: Boolean,
    val hasNdef: Boolean,
) {
    val isEmpty: Boolean get() = ndefRecords.isEmpty()
}

object NfcTech {
    /** Friendly French name for an android.nfc.tech.* class name. */
    fun label(className: String): String = when (className.substringAfterLast('.')) {
        "NfcA" -> "NFC-A (ISO 14443-3A)"
        "NfcB" -> "NFC-B (ISO 14443-3B)"
        "NfcF" -> "NFC-F (FeliCa)"
        "NfcV" -> "NFC-V (ISO 15693)"
        "IsoDep" -> "ISO-DEP (ISO 14443-4 : carte à puce, badge sécurisé ou carte de paiement)"
        "MifareClassic" -> "MIFARE Classic"
        "MifareUltralight" -> "MIFARE Ultralight / NTAG"
        "Ndef" -> "NDEF"
        "NdefFormatable" -> "Formatable NDEF"
        "NfcBarcode" -> "NFC Barcode"
        else -> className.substringAfterLast('.')
    }

    /** Whether a tag of this MIFARE Ultralight/NTAG family typically uses this page count for [bytes]. */
    fun ultralightPages(bytes: Int): Int = bytes / 4
}

object NdefDecode {
    private val UriPrefixes = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
        "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    /** RTD_URI (NFC Forum Well Known Type "U"): one abbreviation byte, then the rest of the URI. */
    fun decodeUri(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        val prefix = UriPrefixes.getOrElse(code) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    /** RTD_TEXT: status byte (bit 7 = encoding, bits 0-5 = language-code length), language code, then text. */
    fun decodeText(payload: ByteArray): Pair<String, String>? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLen = status and 0x3F
        if (1 + langLen > payload.size) return null
        val lang = String(payload, 1, langLen, Charsets.US_ASCII)
        val text = String(payload, 1 + langLen, payload.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
        return lang to text
    }

    /** A vCard's FN (or N), TEL and EMAIL lines, unfolded loosely — just enough to show who it is. */
    fun decodeVCard(text: String): String {
        fun field(prefix: String) = text.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val name = field("FN") ?: field("N")
        val tel = text.lineSequence().firstOrNull { it.startsWith("TEL", ignoreCase = true) }?.substringAfter(':')?.trim()
        val mail = field("EMAIL")
        return listOfNotNull(name, tel?.let { "Tél. $it" }, mail).joinToString(" · ").ifEmpty { "Carte de contact" }
    }

    private fun describeSingle(r: NdefRecord): NfcRecordInfo {
        val type = String(r.type, Charsets.US_ASCII)
        return when (r.tnf.toInt()) {
            NdefRecord.TNF_WELL_KNOWN.toInt() -> when (type) {
                "U" -> decodeUri(r.payload)?.let { uri ->
                    if (uri.startsWith("tel:")) NfcRecordInfo(NfcRecordKind.Phone, uri.removePrefix("tel:"), null, r.payload.size)
                    else NfcRecordInfo(NfcRecordKind.Link, uri, null, r.payload.size)
                } ?: NfcRecordInfo(NfcRecordKind.Unknown, "URI illisible", null, r.payload.size)
                "T" -> decodeText(r.payload)?.let { (lang, text) -> NfcRecordInfo(NfcRecordKind.Text, text, "Langue : $lang", r.payload.size) }
                    ?: NfcRecordInfo(NfcRecordKind.Unknown, "Texte illisible", null, r.payload.size)
                "Sp" -> runCatching { NdefMessage(r.payload) }.getOrNull()?.records?.toList()?.let { nested ->
                    val title = nested.firstOrNull { it.tnf == NdefRecord.TNF_WELL_KNOWN && String(it.type, Charsets.US_ASCII) == "T" }
                        ?.let { decodeText(it.payload)?.second }
                    val uri = nested.firstOrNull { it.tnf == NdefRecord.TNF_WELL_KNOWN && String(it.type, Charsets.US_ASCII) == "U" }
                        ?.let { decodeUri(it.payload) }
                    NfcRecordInfo(NfcRecordKind.SmartPoster, title ?: uri ?: "Smart Poster", uri?.takeIf { it != title }, r.payload.size)
                } ?: NfcRecordInfo(NfcRecordKind.SmartPoster, "Smart Poster illisible", null, r.payload.size)
                else -> NfcRecordInfo(NfcRecordKind.Unknown, "Type « $type » non pris en charge", null, r.payload.size)
            }
            NdefRecord.TNF_MIME_MEDIA.toInt() -> when {
                type.equals("text/vcard", true) || type.equals("text/x-vcard", true) ->
                    NfcRecordInfo(NfcRecordKind.Contact, decodeVCard(String(r.payload, Charsets.UTF_8)), type, r.payload.size)
                type.equals("application/vnd.wfa.wsc", true) ->
                    NfcRecordInfo(NfcRecordKind.WifiHandover, "Identifiants Wi-Fi (Wi-Fi Simple Connect)", "Contenu chiffré, non décodé", r.payload.size)
                else -> NfcRecordInfo(NfcRecordKind.Mime, type, "${r.payload.size} octets", r.payload.size)
            }
            NdefRecord.TNF_EXTERNAL_TYPE.toInt() -> when (type) {
                "android.com:pkg" -> NfcRecordInfo(NfcRecordKind.App, String(r.payload, Charsets.US_ASCII), "Application Android", r.payload.size)
                else -> NfcRecordInfo(NfcRecordKind.Unknown, type, "${r.payload.size} octets, type externe", r.payload.size)
            }
            NdefRecord.TNF_ABSOLUTE_URI.toInt() -> NfcRecordInfo(NfcRecordKind.Link, String(r.payload, Charsets.UTF_8), null, r.payload.size)
            NdefRecord.TNF_EMPTY.toInt() -> NfcRecordInfo(NfcRecordKind.Unknown, "Enregistrement vide", null, 0)
            else -> NfcRecordInfo(NfcRecordKind.Unknown, "TNF ${r.tnf}", "${r.payload.size} octets", r.payload.size)
        }
    }

    fun describe(message: NdefMessage): List<NfcRecordInfo> = message.records.map(::describeSingle)
}

/** Records a user can ask to write; kept separate from parsing so the write form stays simple. */
sealed interface NfcWriteRequest {
    data class Link(val url: String) : NfcWriteRequest
    data class Text(val text: String, val lang: String = "fr") : NfcWriteRequest
    data class Phone(val number: String) : NfcWriteRequest
    data class Contact(val name: String, val phone: String?, val email: String?) : NfcWriteRequest
    data class App(val packageName: String) : NfcWriteRequest
}

object NdefBuild {
    fun message(req: NfcWriteRequest): NdefMessage = when (req) {
        is NfcWriteRequest.Link -> NdefMessage(NdefRecord.createUri(req.url))
        is NfcWriteRequest.Text -> NdefMessage(NdefRecord.createTextRecord(req.lang, req.text))
        is NfcWriteRequest.Phone -> NdefMessage(NdefRecord.createUri("tel:" + req.number.filter { it.isDigit() || it == '+' }))
        is NfcWriteRequest.Contact -> {
            val vcard = buildString {
                append("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:${req.name}\r\n")
                req.phone?.takeIf { it.isNotBlank() }?.let { append("TEL:$it\r\n") }
                req.email?.takeIf { it.isNotBlank() }?.let { append("EMAIL:$it\r\n") }
                append("END:VCARD\r\n")
            }
            NdefMessage(NdefRecord.createMime("text/vcard", vcard.toByteArray(Charsets.UTF_8)))
        }
        is NfcWriteRequest.App -> NdefMessage(NdefRecord.createApplicationRecord(req.packageName))
    }

    /** True when [req] looks well-formed enough to write, without touching the tag. */
    fun validate(req: NfcWriteRequest): String? = when (req) {
        is NfcWriteRequest.Link -> if (req.url.isBlank()) "Entrez un lien" else if (!req.url.contains("://") && !req.url.startsWith("mailto:")) "Le lien doit commencer par https:// (ou un autre protocole)" else null
        is NfcWriteRequest.Text -> if (req.text.isBlank()) "Entrez un texte" else null
        is NfcWriteRequest.Phone -> if (req.number.filter { it.isDigit() }.length < 4) "Numéro de téléphone incomplet" else null
        is NfcWriteRequest.Contact -> if (req.name.isBlank()) "Entrez un nom" else null
        is NfcWriteRequest.App -> if (!req.packageName.matches(Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+"""))) "Nom de package invalide (ex. com.exemple.app)" else null
    }
}
