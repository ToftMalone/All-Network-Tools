package com.allnetworktools.ui.pages.nfc

import android.content.Context
import android.nfc.Tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.NfcRepository
import com.allnetworktools.data.NfcTagInfo
import com.allnetworktools.data.NfcWriteRequest
import com.allnetworktools.data.NfcWriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Session-only history of tags read while the Lecteur NFC tool is open. */
class NfcReaderController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    var current by mutableStateOf<NfcTagInfo?>(null)
        private set
    var lastReadAtMs by mutableLongStateOf(0L)
        private set
    var readCount by mutableIntStateOf(0)
        private set
    val history = mutableStateListOf<NfcTagInfo>()
    private var job: Job? = null

    fun begin(context: Context) {
        repo.startReading(context)
        if (job?.isActive != true) {
            job = scope.launch {
                repo.tags.collect { tag -> onTag(tag) }
            }
        }
    }

    fun end(context: Context) {
        repo.stopReading(context)
        job?.cancel()
        job = null
    }

    private fun onTag(tag: Tag) {
        val info = repo.inspect(tag)
        current = info
        lastReadAtMs = System.currentTimeMillis()
        readCount++
        history.add(0, info)
        while (history.size > 30) history.removeAt(history.lastIndex)
    }

    internal fun setForTest(info: NfcTagInfo) {
        current = info
        lastReadAtMs = System.currentTimeMillis()
        readCount++
        history.add(0, info)
    }
}

enum class NfcWritePreset { Link, Text, Contact, Phone, App }

/** Builds one [NfcWriteRequest] from simple form fields, then writes it to the next tag presented. */
class NfcWriteController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    var preset by mutableStateOf(NfcWritePreset.Link)
    var url by mutableStateOf("https://")
    var text by mutableStateOf("")
    var contactName by mutableStateOf("")
    var contactPhone by mutableStateOf("")
    var contactEmail by mutableStateOf("")
    var phone by mutableStateOf("")
    var packageName by mutableStateOf("")

    var armed by mutableStateOf(false)
        private set
    var result by mutableStateOf<NfcWriteResult?>(null)
        private set
    private var job: Job? = null

    fun request(): NfcWriteRequest = when (preset) {
        NfcWritePreset.Link -> NfcWriteRequest.Link(url.trim())
        NfcWritePreset.Text -> NfcWriteRequest.Text(text)
        NfcWritePreset.Contact -> NfcWriteRequest.Contact(contactName.trim(), contactPhone.trim().ifBlank { null }, contactEmail.trim().ifBlank { null })
        NfcWritePreset.Phone -> NfcWriteRequest.Phone(phone.trim())
        NfcWritePreset.App -> NfcWriteRequest.App(packageName.trim())
    }

    fun arm(context: Context) {
        result = null
        armed = true
        repo.startReading(context)
        if (job?.isActive != true) {
            job = scope.launch { repo.tags.collect { tag -> if (armed) write(tag) } }
        }
    }

    fun disarm(context: Context) {
        armed = false
        repo.stopReading(context)
        job?.cancel()
        job = null
    }

    private fun write(tag: Tag) {
        armed = false
        result = repo.write(tag, com.allnetworktools.data.NdefBuild.message(request()))
    }

    internal fun setResultForTest(r: NfcWriteResult) {
        result = r
    }
}

enum class NfcMaintAction { Erase, Lock }

class NfcMaintController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    var pending by mutableStateOf<NfcMaintAction?>(null)
        private set
    var result by mutableStateOf<NfcWriteResult?>(null)
        private set
    var action by mutableStateOf<NfcMaintAction?>(null)
        private set
    private var job: Job? = null

    fun arm(context: Context, a: NfcMaintAction) {
        pending = a
        result = null
        repo.startReading(context)
        if (job?.isActive != true) {
            job = scope.launch { repo.tags.collect { tag -> pending?.let { run(tag, it) } } }
        }
    }

    fun disarm(context: Context) {
        pending = null
        repo.stopReading(context)
        job?.cancel()
        job = null
    }

    private fun run(tag: Tag, a: NfcMaintAction) {
        pending = null
        action = a
        result = when (a) {
            NfcMaintAction.Erase -> repo.erase(tag)
            NfcMaintAction.Lock -> repo.lock(tag)
        }
    }

    internal fun setResultForTest(a: NfcMaintAction, r: NfcWriteResult) {
        action = a
        result = r
    }
}

/** Counts how quickly consecutive reads land while the tag is held near the phone, to feel out the antenna. */
class NfcRangeController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    val hits = mutableStateListOf<Long>()
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun begin(context: Context) {
        running = true
        repo.startReading(context)
        if (job?.isActive != true) {
            job = scope.launch { repo.tags.collect { hits.add(0, System.currentTimeMillis()); while (hits.size > 100) hits.removeAt(hits.lastIndex) } }
        }
    }

    fun end(context: Context) {
        running = false
        repo.stopReading(context)
        job?.cancel()
        job = null
    }

    fun reset() = hits.clear()

    internal fun setForTest(list: List<Long>) {
        hits.clear(); hits.addAll(list)
    }
}
