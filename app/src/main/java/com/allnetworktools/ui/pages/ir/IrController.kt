package com.allnetworktools.ui.pages.ir

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.IrBrand
import com.allnetworktools.data.IrCode
import com.allnetworktools.data.IrEncoder
import com.allnetworktools.data.IrKey
import com.allnetworktools.data.IrProtocol
import com.allnetworktools.data.IrRepository
import com.allnetworktools.data.IrSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the last emission did, for the status line under the buttons. */
data class IrSent(val label: String, val error: String?, val atMs: Long)

class IrController(private val repo: IrRepository, private val scope: CoroutineScope) {
    var brand by mutableStateOf(IrBrand.Samsung)
    var last by mutableStateOf<IrSent?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var sentCount by mutableIntStateOf(0)
        private set

    // Custom code form.
    var protocol by mutableStateOf(IrProtocol.Nec)
    var address by mutableStateOf("0x04")
    var command by mutableStateOf("0x08")

    private var rc5Toggle = false
    private var job: Job? = null

    val ranges: List<IntRange> by lazy { repo.carrierRanges() }

    fun press(key: IrKey) {
        val code = brand.code(key) ?: return
        send(code, "${brand.label} · ${key.label}")
    }

    fun send(code: IrCode, label: String) {
        // RC5 receivers ignore a repeated frame unless the toggle bit flips between presses.
        if (code.protocol == IrProtocol.Rc5) rc5Toggle = !rc5Toggle
        emit(IrEncoder.encode(code, rc5Toggle), label)
    }

    fun testBurst() = emit(IrEncoder.testBurst(), "Salve de test 38 kHz")

    private fun emit(signal: IrSignal, label: String) {
        if (job?.isActive == true) return
        sending = true
        job = scope.launch {
            val error = withContext(Dispatchers.IO) { repo.transmit(signal) }
            sending = false
            sentCount++
            last = IrSent(label, error, System.currentTimeMillis())
        }
    }

    /** Parses "0x1F", "1F" (hex if it has a letter) or "31". */
    fun parse(value: String): Int? {
        val v = value.trim().lowercase()
        return when {
            v.startsWith("0x") -> v.drop(2).toIntOrNull(16)
            v.any { it in 'a'..'f' } -> v.toIntOrNull(16)
            else -> v.toIntOrNull()
        }
    }

    /** The custom code, or why it cannot be sent. */
    fun customCode(): Pair<IrCode?, String?> {
        val a = parse(address) ?: return null to "Adresse invalide"
        val c = parse(command) ?: return null to "Commande invalide"
        if (a !in 0..protocol.maxAddress) return null to "L'adresse ${protocol.label} va de 0 à ${protocol.maxAddress} (0x${protocol.maxAddress.toString(16).uppercase()})"
        if (c !in 0..protocol.maxCommand) return null to "La commande ${protocol.label} va de 0 à ${protocol.maxCommand} (0x${protocol.maxCommand.toString(16).uppercase()})"
        return IrCode(protocol, a, c) to null
    }

    internal fun setLastForTest(s: IrSent) {
        last = s
    }
}
