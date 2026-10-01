package com.allnetworktools.data

import android.content.Context
import android.hardware.ConsumerIrManager

/** The phone's infrared emitter (Xiaomi, Redmi, POCO, some Huawei/Honor). Android exposes no IR receiver. */
open class IrRepository(context: Context) {
    private val ir: ConsumerIrManager? = context.getSystemService(ConsumerIrManager::class.java)

    open val hasEmitter: Boolean get() = runCatching { ir?.hasIrEmitter() == true }.getOrDefault(false)

    /** Carrier ranges the emitter supports, in Hz; empty when the HAL does not report them. */
    open fun carrierRanges(): List<IntRange> =
        runCatching { ir?.carrierFrequencies?.map { it.minFrequency..it.maxFrequency } }.getOrNull().orEmpty()

    /** Blocks for the length of the pattern. Returns null on success, otherwise why it failed. */
    open fun transmit(signal: IrSignal): String? {
        val m = ir ?: return "Pas d'émetteur infrarouge"
        val ranges = carrierRanges()
        if (ranges.isNotEmpty() && ranges.none { signal.carrier in it }) {
            return "L'émetteur ne prend pas en charge ${signal.carrier / 1000} kHz"
        }
        return runCatching { m.transmit(signal.carrier, signal.pattern) }.exceptionOrNull()?.let { it.message ?: "Émission refusée" }
    }
}
