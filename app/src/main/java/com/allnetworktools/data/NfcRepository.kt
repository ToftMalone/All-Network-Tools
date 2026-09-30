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

    /** True the first time this tag is seen with a card-emulation-capable technology (IsoDep). */
    open fun looksLikeSmartCard(tag: Tag): Boolean = IsoDep.get(tag) != null
}
