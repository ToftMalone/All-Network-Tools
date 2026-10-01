package com.allnetworktools.ui.pages.nfc

import android.content.Context
import android.nfc.Tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.EnduranceProgress
import com.allnetworktools.data.EnduranceReport
import com.allnetworktools.data.LinkSample
import com.allnetworktools.data.NfcChipReport
import com.allnetworktools.data.NfcRepository
import com.allnetworktools.data.NfcTagInfo
import com.allnetworktools.data.NfcWriteRequest
import com.allnetworktools.data.NfcWriteResult
import com.allnetworktools.data.WifiTagSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

enum class NfcWritePreset(val label: String) {
    Link("Lien"), Text("Texte"), Wifi("Wi-Fi"), Contact("Contact"), Phone("Appel"), Sms("SMS"), Email("E-mail"), Geo("Position"), App("Appli"),
}

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
    var wifiSsid by mutableStateOf("")
    var wifiKey by mutableStateOf("")
    var wifiSecurity by mutableStateOf(WifiTagSecurity.Wpa2)
    var smsNumber by mutableStateOf("")
    var smsBody by mutableStateOf("")
    var emailTo by mutableStateOf("")
    var emailSubject by mutableStateOf("")
    var emailBody by mutableStateOf("")
    var lat by mutableStateOf("")
    var lon by mutableStateOf("")

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
        NfcWritePreset.Wifi -> NfcWriteRequest.Wifi(wifiSsid, wifiKey, wifiSecurity)
        NfcWritePreset.Sms -> NfcWriteRequest.Sms(smsNumber.trim(), smsBody)
        NfcWritePreset.Email -> NfcWriteRequest.Email(emailTo.trim(), emailSubject, emailBody)
        NfcWritePreset.Geo -> NfcWriteRequest.Geo(lat, lon)
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

/** Identifies the chip of each tag presented, without changing it. */
class NfcAnalyzeController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    var report by mutableStateOf<NfcChipReport?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun begin(context: Context) {
        repo.startReading(context)
        if (job?.isActive != true) {
            job = scope.launch {
                repo.tags.collect { tag ->
                    busy = true
                    report = withContext(Dispatchers.IO) { repo.analyze(tag) }
                    busy = false
                }
            }
        }
    }

    fun end(context: Context) {
        repo.stopReading(context)
        job?.cancel()
        job = null
        busy = false
    }

    internal fun setForTest(r: NfcChipReport) {
        report = r
    }
}

/** Write/read-back stress test of the next tag presented, after an explicit go from the user. */
class NfcEnduranceController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    var cycles by mutableIntStateOf(3)
    var armed by mutableStateOf(false)
        private set
    var progress by mutableStateOf<EnduranceProgress?>(null)
        private set
    var report by mutableStateOf<EnduranceReport?>(null)
        private set
    private var job: Job? = null

    val running: Boolean get() = progress != null

    fun arm(context: Context) {
        report = null
        armed = true
        repo.startReading(context)
        if (job?.isActive != true) {
            job = scope.launch {
                repo.tags.collect { tag ->
                    if (!armed) return@collect
                    armed = false
                    progress = EnduranceProgress(0, cycles, 0, 1)
                    val n = cycles
                    val r = withContext(Dispatchers.IO) {
                        repo.endurance(tag, n) { p -> scope.launch { if (progress != null) progress = p } }
                    }
                    progress = null
                    report = r
                }
            }
        }
    }

    fun disarm(context: Context) {
        armed = false
        repo.stopReading(context)
        job?.cancel()
        job = null
        progress = null
    }

    internal fun setForTest(r: EnduranceReport) {
        report = r
    }
}

/** Phone back split in zones; each zone gets the read quality measured while the tag sits on it. */
class NfcAntennaController(private val repo: NfcRepository, private val scope: CoroutineScope) {
    val scores = mutableStateMapOf<Int, LinkSample>()
    /** Zone waiting for the tag, then being measured. */
    var active by mutableStateOf<Int?>(null)
        private set
    var countdown by mutableIntStateOf(0)
        private set
    var measuring by mutableStateOf(false)
        private set
    var waitingForTag by mutableStateOf(false)
        private set
    private var lastTag: Tag? = null
    private var listen: Job? = null
    private var run: Job? = null

    fun begin(context: Context) {
        repo.startReading(context)
        if (listen?.isActive != true) {
            listen = scope.launch { repo.tags.collect { lastTag = it } }
        }
    }

    fun end(context: Context) {
        repo.stopReading(context)
        listen?.cancel(); listen = null
        cancel()
    }

    fun measure(zone: Int) {
        run?.cancel()
        active = zone
        run = scope.launch {
            measuring = false
            for (s in 3 downTo 1) { countdown = s; delay(700) }
            countdown = 0
            waitingForTag = lastTag == null
            val deadline = System.currentTimeMillis() + 10_000
            while (lastTag == null && System.currentTimeMillis() < deadline) delay(100)
            waitingForTag = false
            val tag = lastTag
            measuring = true
            scores[zone] = if (tag == null) LinkSample(1, 0, null) else withContext(Dispatchers.IO) { repo.probe(tag, MEASURE_MS) }
            measuring = false
            active = null
        }
    }

    fun cancel() {
        run?.cancel(); run = null
        active = null; countdown = 0; measuring = false; waitingForTag = false
    }

    fun reset() {
        cancel()
        scores.clear()
    }

    val best: Int? get() = scores.entries.filter { it.value.score > 0 }.maxByOrNull { it.value.score }?.key

    internal fun setForTest(map: Map<Int, LinkSample>) {
        scores.clear(); scores.putAll(map)
    }

    companion object {
        const val COLS = 3
        const val ROWS = 4
        const val MEASURE_MS = 2500L
    }
}
