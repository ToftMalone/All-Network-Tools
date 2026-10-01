package com.allnetworktools.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface NfcWriteResult {
    data object Success : NfcWriteResult
    data class NotWritable(val reason: String) : NfcWriteResult
    data class Failed(val message: String) : NfcWriteResult
}

private tailrec fun activityOf(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> activityOf(context.baseContext)
    else -> null
}

/**
 * Tags are only listened for while a screen asks for them ([startReading]/[stopReading]), through
 * Android's reader mode: unlike foreground dispatch it needs no manifest intent filter, and it hands
 * every tag straight to the caller regardless of which Compose screen is on top.
 */
open class NfcRepository(private val context: Context) {
    private val manager = context.getSystemService(NfcManager::class.java)
    open val adapter: NfcAdapter? = manager?.defaultAdapter

    open val hasNfc: Boolean get() = adapter != null

    open val enabled: Flow<Boolean> = broadcastFlow(context, NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) { adapter?.isEnabled == true }

    private val _tags = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    open val tags: SharedFlow<Tag> = _tags

    private var readerHost: Activity? = null

    open fun startReading(host: Context) {
        val activity = activityOf(host) ?: return
        val a = adapter ?: return
        readerHost = activity
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE
        a.enableReaderMode(activity, { tag -> _tags.tryEmit(tag) }, flags, null)
    }

    open fun stopReading(host: Context) {
        val activity = readerHost ?: activityOf(host) ?: return
        runCatching { adapter?.disableReaderMode(activity) }
        readerHost = null
    }

    /** Technologies present, their UID and, when the tag carries NDEF, its content and capacity. */
    open fun inspect(tag: Tag): NfcTagInfo {
        val techs = tag.techList.toList()
        val uid = tag.id?.joinToString("") { "%02X".format(it) } ?: "—"
        val ndef = Ndef.get(tag)
        val message = ndef?.cachedNdefMessage ?: runCatching { ndef?.let { it.connect(); it.ndefMessage.also { _ -> it.close() } } }.getOrNull()
        val memory = ndef?.maxSize?.takeIf { it > 0 }
            ?: runCatching { MifareUltralight.get(tag)?.let { it.connect(); (it.type.let { t -> if (t == MifareUltralight.TYPE_ULTRALIGHT_C) 144 else 48 }).also { _ -> it.close() } } }.getOrNull()
            ?: runCatching { MifareClassic.get(tag)?.let { it.connect(); (it.size).also { _ -> it.close() } } }.getOrNull()
        return NfcTagInfo(
            uidHex = uid,
            techs = techs,
            techLabels = techs.map(NfcTech::label).distinct(),
            memoryBytes = memory,
            ndefRecords = message?.let(NdefDecode::describe).orEmpty(),
            ndefWritable = ndef?.isWritable == true,
            ndefCanLock = ndef?.canMakeReadOnly() == true,
            hasNdef = ndef != null,
        )
    }

    /** Writes [message] to [tag], formatting it for NDEF first if it is blank. */
    open fun write(tag: Tag, message: NdefMessage): NfcWriteResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return NfcWriteResult.NotWritable("Ce tag est en lecture seule.")
                    if (message.byteArrayLength > ndef.maxSize) return NfcWriteResult.NotWritable("Le message (${message.byteArrayLength} o) dépasse la capacité du tag (${ndef.maxSize} o).")
                    ndef.writeNdefMessage(message)
                    NfcWriteResult.Success
                } finally {
                    runCatching { ndef.close() }
                }
            } catch (e: Exception) {
                NfcWriteResult.Failed(e.message ?: "Écriture refusée")
            }
        }
        val formatable = NdefFormatable.get(tag) ?: return NfcWriteResult.NotWritable("Ce tag ne prend pas en charge NDEF.")
        return try {
            formatable.connect()
            try {
                formatable.format(message)
                NfcWriteResult.Success
            } finally {
                runCatching { formatable.close() }
            }
        } catch (e: Exception) {
            NfcWriteResult.Failed(e.message ?: "Formatage refusé")
        }
    }

    /** Overwrites the tag with an empty NDEF message. */
    open fun erase(tag: Tag): NfcWriteResult = write(tag, NdefMessage(android.nfc.NdefRecord.createTextRecord("fr", "")))

    /** Permanently locks the tag's NDEF content against further writes. There is no way back. */
    open fun lock(tag: Tag): NfcWriteResult {
        val ndef = Ndef.get(tag) ?: return NfcWriteResult.NotWritable("Ce tag n'est pas au format NDEF.")
        if (!ndef.canMakeReadOnly()) return NfcWriteResult.NotWritable("Ce tag ne peut pas être verrouillé.")
        return try {
            ndef.connect()
            try {
                if (ndef.makeReadOnly()) NfcWriteResult.Success else NfcWriteResult.Failed("Le verrouillage a échoué")
            } finally {
                runCatching { ndef.close() }
            }
        } catch (e: Exception) {
            NfcWriteResult.Failed(e.message ?: "Verrouillage refusé")
        }
    }

    /** GET_VERSION on a Type 2 tag; null when it is not one or does not support the command (original Ultralight NAKs it). */
    private fun readVersion(tag: Tag): ByteArray? {
        val ul = MifareUltralight.get(tag) ?: return null
        return runCatching {
            ul.connect()
            try { ul.transceive(byteArrayOf(0x60)) } finally { runCatching { ul.close() } }
        }.getOrNull()?.takeIf { it.size >= 8 }
    }

    private fun modelOf(tag: Tag, version: ByteArray?): NfcChipModel? = version?.let(NfcChip::model)
        ?: if (version == null) MifareUltralight.get(tag)?.type?.let {
            when (it) {
                MifareUltralight.TYPE_ULTRALIGHT_C -> NfcChip.UltralightC
                MifareUltralight.TYPE_ULTRALIGHT -> NfcChip.UltralightOriginal
                else -> null
            }
        } else null

    /** Identifies the chip and its memory without touching its content. */
    open fun analyze(tag: Tag): NfcChipReport {
        val uid = tag.id ?: ByteArray(0)
        val techs = tag.techList.toList()
        val nfcA = NfcA.get(tag)
        val version = readVersion(tag)
        val model = modelOf(tag, version)
        val page3 = MifareUltralight.get(tag)?.let { ul ->
            runCatching { ul.connect(); try { ul.readPages(3).copyOf(4) } finally { runCatching { ul.close() } } }.getOrNull()
        }
        val cc = page3?.let(NfcChip::capability)
        val ndef = Ndef.get(tag)
        val chip = model?.name ?: version?.let(NfcChip::versionName) ?: nfcA?.sak?.toInt()?.let(NfcChip::familyFromSak)
            ?: when {
                NfcV.get(tag) != null -> "Tag ISO 15693 (NFC-V)"
                techs.any { it.endsWith("NfcF") } -> "FeliCa (NFC-F)"
                techs.any { it.endsWith("NfcB") } -> "Carte ISO 14443-B"
                else -> "Inconnu"
            }
        return NfcChipReport(
            uidHex = uid.joinToString("") { "%02X".format(it) }.ifEmpty { "—" },
            uidBytes = uid.size,
            manufacturer = NfcChip.manufacturer(uid, NfcV.get(tag) != null),
            chip = chip,
            exact = version != null && model != null,
            model = model,
            techLabels = techs.map(NfcTech::label).distinct(),
            atqa = nfcA?.atqa?.let(NfcChip::hex),
            sak = nfcA?.sak?.let { "%02X".format(it) },
            maxTransceive = nfcA?.maxTransceiveLength ?: NfcV.get(tag)?.maxTransceiveLength,
            ndefType = ndef?.type,
            ndefCapacity = ndef?.maxSize,
            ndefUsed = ndef?.let { it.cachedNdefMessage?.byteArrayLength ?: 0 },
            ndefWritable = ndef?.isWritable,
            ndefCanLock = ndef?.canMakeReadOnly(),
            capability = cc,
            versionHex = NfcChip.hex(version),
            anomalies = NfcChip.anomalies(uid, version, model, cc),
        )
    }

    /**
     * Writes a test pattern to every user page of an NTAG / Ultralight [cycles] times and reads it back,
     * then puts the original content back. Other writable NDEF tags get a whole-message round trip instead.
     */
    open fun endurance(tag: Tag, cycles: Int, progress: (EnduranceProgress) -> Unit): EnduranceReport {
        val ndef = Ndef.get(tag)
        if (ndef != null && !ndef.isWritable) {
            return EnduranceReport("—", false, 0, 0, emptyMap(), 0.0, 0.0, "Ce tag est verrouillé en lecture seule : il ne peut pas être testé.", false)
        }
        val version = readVersion(tag)
        val model = modelOf(tag, version)
        val ul = MifareUltralight.get(tag)
        return when {
            ul != null && model != null -> pageEndurance(ul, model, cycles, progress)
            ndef != null -> ndefEndurance(ndef, cycles, progress)
            else -> EnduranceReport(
                "—", false, 0, 0, emptyMap(), 0.0, 0.0,
                "Ce tag n'est ni un NTAG / Ultralight ni un tag NDEF modifiable : il ne peut pas être testé.", false,
            )
        }
    }

    private fun pageEndurance(ul: MifareUltralight, model: NfcChipModel, cycles: Int, progress: (EnduranceProgress) -> Unit): EnduranceReport {
        val pages = model.firstUserPage..model.lastUserPage
        val faults = sortedMapOf<Int, PageFault>()
        var writeMs = 0.0; var writes = 0; var readMs = 0.0; var reads = 0
        fun reconnect(): Boolean = runCatching { runCatching { ul.close() }; ul.connect() }.isSuccess
        try {
            ul.connect()
        } catch (e: IOException) {
            return EnduranceReport(model.name, true, 0, 0, emptyMap(), 0.0, 0.0, "Tag retiré avant le début du test.", false)
        }
        try {
            // Backup, four pages per READ.
            val backup = HashMap<Int, ByteArray>()
            try {
                var p = pages.first
                while (p <= pages.last) {
                    val block = ul.readPages(p)
                    for (k in 0 until 4) if (p + k <= pages.last) backup[p + k] = block.copyOfRange(k * 4, k * 4 + 4)
                    p += 4
                }
                // A write the tag refuses here means a password or lock bits, not a defect.
                ul.writePage(pages.first, backup.getValue(pages.first))
            } catch (e: IOException) {
                return EnduranceReport(
                    model.name, true, 0, 0, emptyMap(), 0.0, 0.0,
                    "Le tag refuse l'écriture (mot de passe ou pages verrouillées), ou il a été retiré. Rien n'a été modifié.", false,
                )
            }
            val total = cycles * pages.count()
            var done = 0
            var aborted: String? = null
            loop@ for (cycle in 1..cycles) {
                for (page in pages) {
                    val t0 = System.nanoTime()
                    try {
                        ul.writePage(page, testPattern(cycle, page))
                        writeMs += (System.nanoTime() - t0) / 1e6; writes++
                    } catch (e: IOException) {
                        faults[page] = PageFault.WriteRefused
                        if (!reconnect()) { aborted = "Tag retiré pendant le test."; break@loop }
                    }
                    done++
                    if (done % 8 == 0) progress(EnduranceProgress(cycle, cycles, done, total))
                }
                var p = pages.first
                while (p <= pages.last) {
                    val t0 = System.nanoTime()
                    val block = try {
                        ul.readPages(p).also { readMs += (System.nanoTime() - t0) / 1e6; reads++ }
                    } catch (e: IOException) {
                        if (!reconnect()) { aborted = "Tag retiré pendant le test."; break@loop }
                        null
                    }
                    for (k in 0 until 4) {
                        val page = p + k
                        if (page > pages.last || faults[page] == PageFault.WriteRefused) continue
                        val got = block?.copyOfRange(k * 4, k * 4 + 4)
                        if (got == null || !got.contentEquals(testPattern(cycle, page))) faults[page] = PageFault.Mismatch
                    }
                    p += 4
                }
                progress(EnduranceProgress(cycle, cycles, done, total))
            }
            var restored = true
            for (page in pages) {
                val ok = runCatching { ul.writePage(page, backup.getValue(page)) }.isSuccess || (reconnect() && runCatching { ul.writePage(page, backup.getValue(page)) }.isSuccess)
                if (!ok && faults[page] == null) restored = false
            }
            return EnduranceReport(
                model.name, true, cycles, pages.count(), faults,
                if (writes == 0) 0.0 else writeMs / writes, if (reads == 0) 0.0 else readMs / reads, aborted, restored,
            )
        } finally {
            runCatching { ul.close() }
        }
    }

    private fun ndefEndurance(ndef: Ndef, cycles: Int, progress: (EnduranceProgress) -> Unit): EnduranceReport {
        val faults = sortedMapOf<Int, PageFault>()
        var writeMs = 0.0; var readMs = 0.0; var n = 0
        val type = ndef.type.substringAfterLast('.')
        try {
            ndef.connect()
        } catch (e: IOException) {
            return EnduranceReport(type, false, 0, 0, emptyMap(), 0.0, 0.0, "Tag retiré avant le début du test.", false)
        }
        try {
            val backup = runCatching { ndef.ndefMessage }.getOrNull()
            // Fill most of the capacity so as much memory as possible is exercised.
            val textLen = (ndef.maxSize - 16).coerceIn(1, 4000)
            var aborted: String? = null
            for (cycle in 1..cycles) {
                val text = buildString { repeat(textLen) { i -> append('A' + ((cycle * 31 + i * 7) % 26)) } }
                val msg = NdefMessage(android.nfc.NdefRecord.createTextRecord("fr", text))
                try {
                    val t0 = System.nanoTime()
                    ndef.writeNdefMessage(msg)
                    val t1 = System.nanoTime()
                    val back = ndef.ndefMessage
                    readMs += (System.nanoTime() - t1) / 1e6
                    writeMs += (t1 - t0) / 1e6; n++
                    if (back == null || !back.toByteArray().contentEquals(msg.toByteArray())) faults[cycle] = PageFault.Mismatch
                } catch (e: Exception) {
                    aborted = "Tag retiré ou écriture refusée au cycle $cycle."
                    break
                }
                progress(EnduranceProgress(cycle, cycles, cycle, cycles))
            }
            val restored = runCatching {
                ndef.writeNdefMessage(backup ?: NdefMessage(android.nfc.NdefRecord.createTextRecord("fr", "")))
            }.isSuccess
            return EnduranceReport(
                type, false, cycles, 0, faults,
                if (n == 0) 0.0 else writeMs / n, if (n == 0) 0.0 else readMs / n, aborted, restored,
            )
        } finally {
            runCatching { ndef.close() }
        }
    }

    /** Reads the tag over and over for [durationMs] and reports how many reads came back, and how fast. */
    open fun probe(tag: Tag, durationMs: Long): LinkSample {
        val ul = MifareUltralight.get(tag)
        val ndef = Ndef.get(tag)
        val tech: TagTechnology = ul ?: ndef ?: tag.techList.firstNotNullOfOrNull { techOf(tag, it) } ?: return LinkSample(0, 0, null)
        val op: () -> Unit = when (tech) {
            is MifareUltralight -> { { tech.readPages(0) } }
            is Ndef -> { { tech.ndefMessage } }
            // No harmless read is common to every other technology: a reconnect still needs the tag to answer.
            else -> { { tech.close(); tech.connect() } }
        }
        var attempts = 0; var ok = 0; var latency = 0.0
        runCatching { tech.connect() }
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            attempts++
            val t0 = System.nanoTime()
            try {
                if (!tech.isConnected) tech.connect()
                op()
                ok++
                latency += (System.nanoTime() - t0) / 1e6
            } catch (e: Exception) {
                runCatching { tech.close() }
            }
            Thread.sleep(15)
        }
        runCatching { tech.close() }
        return LinkSample(attempts, ok, if (ok == 0) null else latency / ok)
    }

    private fun techOf(tag: Tag, name: String): TagTechnology? = when (name.substringAfterLast('.')) {
        "NfcA" -> NfcA.get(tag)
        "NfcV" -> NfcV.get(tag)
        "IsoDep" -> IsoDep.get(tag)
        "NfcB" -> android.nfc.tech.NfcB.get(tag)
        "NfcF" -> android.nfc.tech.NfcF.get(tag)
        else -> null
    }

    /** True the first time this tag is seen with a card-emulation-capable technology (IsoDep). */
    open fun looksLikeSmartCard(tag: Tag): Boolean = IsoDep.get(tag) != null
}
