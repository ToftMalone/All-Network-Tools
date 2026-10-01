package com.allnetworktools.data

/** Remote-control protocols the app can encode; carrier in Hz, from each protocol's specification. */
enum class IrProtocol(val label: String, val carrier: Int, val addressBits: Int, val commandBits: Int) {
    Nec("NEC", 38_000, 8, 8),
    Samsung32("Samsung", 38_000, 8, 8),
    Sirc12("Sony SIRC", 40_000, 5, 7),
    Rc5("Philips RC5", 36_000, 5, 6),
    ;

    val maxAddress: Int get() = (1 shl addressBits) - 1
    val maxCommand: Int get() = (1 shl commandBits) - 1
}

data class IrCode(val protocol: IrProtocol, val address: Int, val command: Int)

/** One frame (or the repeats a protocol needs) as alternating mark/space durations in microseconds. */
data class IrSignal(val carrier: Int, val pattern: IntArray) {
    val durationUs: Long get() = pattern.sumOf { it.toLong() }

    override fun equals(other: Any?) = other is IrSignal && other.carrier == carrier && other.pattern.contentEquals(pattern)
    override fun hashCode() = 31 * carrier + pattern.contentHashCode()
}

object IrEncoder {
    /** Pause after the last frame: also gives the pattern an even length, which some IR HALs require. */
    private const val TRAILER = 40_000

    fun encode(code: IrCode, rc5Toggle: Boolean = false): IrSignal {
        val p = code.protocol
        require(code.address in 0..p.maxAddress) { "Adresse hors limites pour ${p.label}" }
        require(code.command in 0..p.maxCommand) { "Commande hors limites pour ${p.label}" }
        val pattern = when (p) {
            IrProtocol.Nec -> pulseDistance(9000, 4500, 562, 562, 1687, listOf(code.address, code.address.inv() and 0xFF, code.command, code.command.inv() and 0xFF))
            IrProtocol.Samsung32 -> pulseDistance(4500, 4500, 560, 560, 1690, listOf(code.address, code.address, code.command, code.command.inv() and 0xFF))
            IrProtocol.Sirc12 -> sirc(code.address, code.command)
            IrProtocol.Rc5 -> rc5(code.address, code.command, rc5Toggle)
        }
        return IrSignal(p.carrier, pattern)
    }

    /** NEC-style: leader, then each byte LSB first, a bit's value carried by the space length, then a stop mark. */
    private fun pulseDistance(leadMark: Int, leadSpace: Int, mark: Int, zero: Int, one: Int, bytes: List<Int>): IntArray {
        val out = ArrayList<Int>(4 + bytes.size * 16)
        out += leadMark; out += leadSpace
        for (b in bytes) for (i in 0 until 8) {
            out += mark
            out += if ((b shr i) and 1 == 1) one else zero
        }
        out += mark; out += TRAILER
        return out.toIntArray()
    }

    /**
     * Sony SIRC 12 bits: 2.4 ms leader, then 7 command and 5 address bits LSB first, a bit's value carried by
     * the mark length (1.2 ms = 1, 0.6 ms = 0). Sent three times on a 45 ms period, as Sony receivers expect.
     */
    private fun sirc(address: Int, command: Int): IntArray {
        val bits = (0 until 7).map { (command shr it) and 1 } + (0 until 5).map { (address shr it) and 1 }
        val frame = ArrayList<Int>()
        frame += 2400; frame += 600
        bits.forEach { frame += if (it == 1) 1200 else 600; frame += 600 }
        val out = ArrayList<Int>()
        repeat(3) { i ->
            val f = frame.toMutableList()
            val used = f.sum()
            f[f.lastIndex] += if (i < 2) 45_000 - used else TRAILER
            out += f
        }
        return out.toIntArray()
    }

    /**
     * Philips RC5: 14 Manchester bits of 1.778 ms (start, field, toggle, 5 address bits, 6 command bits, MSB
     * first); a 1 is space then mark, a 0 mark then space. The leading space of the first start bit is dropped.
     */
    private fun rc5(address: Int, command: Int, toggle: Boolean): IntArray {
        val half = 889
        val bits = buildList {
            add(1); add(1); add(if (toggle) 1 else 0)
            for (i in 4 downTo 0) add((address shr i) and 1)
            for (i in 5 downTo 0) add((command shr i) and 1)
        }
        val levels = bits.flatMap { if (it == 1) listOf(false, true) else listOf(true, false) }.dropWhile { !it }
        val out = ArrayList<Int>()
        var current = levels.first()
        var length = 0
        for (l in levels) {
            if (l == current) length += half else { out += length; current = l; length = half }
        }
        out += length
        // The pattern starts on a mark: odd size means it ends on a mark, so append the trailer as a space.
        if (out.size % 2 == 1) out += TRAILER else out[out.lastIndex] += TRAILER
        return out.toIntArray()
    }

    /** A steady 38 kHz burst, chopped so no single mark exceeds what IR HALs accept. */
    fun testBurst(totalMs: Int = 1500): IrSignal {
        val out = ArrayList<Int>()
        var t = 0
        while (t < totalMs * 1000) { out += 10_000; out += 1_000; t += 11_000 }
        return IrSignal(38_000, out.toIntArray())
    }
}

enum class IrKey(val label: String) {
    Power("Marche/Arrêt"), VolUp("Volume +"), VolDown("Volume −"), Mute("Muet"), ChUp("Chaîne +"), ChDown("Chaîne −"), Source("Source"),
}

/**
 * TV codes widely documented for each brand (LIRC and Flipper Zero IR databases): the same codes work across
 * most of the brand's models, but not necessarily all of them.
 */
enum class IrBrand(val label: String, val protocol: IrProtocol, val address: Int, val keys: Map<IrKey, Int>) {
    Samsung(
        "Samsung", IrProtocol.Samsung32, 0x07,
        mapOf(IrKey.Power to 0x02, IrKey.VolUp to 0x07, IrKey.VolDown to 0x0B, IrKey.Mute to 0x0F, IrKey.ChUp to 0x12, IrKey.ChDown to 0x10, IrKey.Source to 0x01),
    ),
    Lg(
        "LG", IrProtocol.Nec, 0x04,
        mapOf(IrKey.Power to 0x08, IrKey.VolUp to 0x02, IrKey.VolDown to 0x03, IrKey.Mute to 0x09, IrKey.ChUp to 0x00, IrKey.ChDown to 0x01, IrKey.Source to 0x0B),
    ),
    Sony(
        "Sony", IrProtocol.Sirc12, 0x01,
        mapOf(IrKey.Power to 0x15, IrKey.VolUp to 0x12, IrKey.VolDown to 0x13, IrKey.Mute to 0x14, IrKey.ChUp to 0x10, IrKey.ChDown to 0x11, IrKey.Source to 0x25),
    ),
    Philips(
        "Philips", IrProtocol.Rc5, 0x00,
        mapOf(IrKey.Power to 0x0C, IrKey.VolUp to 0x10, IrKey.VolDown to 0x11, IrKey.Mute to 0x0D, IrKey.ChUp to 0x20, IrKey.ChDown to 0x21),
    ),
    ;

    fun code(key: IrKey): IrCode? = keys[key]?.let { IrCode(protocol, address, it) }
}
