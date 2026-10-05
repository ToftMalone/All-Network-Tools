package com.allnetworktools.data.radio

/** The radio did not answer as expected; the message is meant for the user. */
open class RadioException(message: String) : Exception(message)

/** Nothing answered the handshake of this model: it is probably another one. */
class RadioNoAnswer(message: String) : RadioException(message)

/** What a memory list has to respect for a radio: how many memories, how long the names, which bands and power levels. */
interface RadioLimits {
    val label: String
    val slots: Int
    val nameLength: Int

    /** Frequencies the radio can receive, in Hz. */
    val bands: List<LongRange>

    /** Power levels the radio offers, strongest first. */
    val powers: List<RadioPower>

    fun inBand(hz: Long) = bands.any { hz in it }

    /** The channel as the radio will store it: 10 Hz steps, capital letters that fit the name, a power it has. */
    fun normalize(ch: RadioChannel): RadioChannel = ch.copy(
        rxHz = ch.rxHz / 10 * 10,
        txHz = ch.txHz?.let { it / 10 * 10 },
        name = ch.name.uppercase().filter { it.code in 32..126 }.take(nameLength).trimEnd(),
        power = if (ch.power in powers) ch.power else powers.last(),
    )
}

/** Before a radio is recognised: the most generous limits, so a list can be prepared and then fitted to whatever answers. */
object GenericRadio : RadioLimits {
    override val label = "Talkie-walkie"
    override val slots = 256
    override val nameLength = 12
    override val bands = listOf(18_000_000L..1_000_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Medium, RadioPower.Low)
}

/**
 * A handheld that can be read and written through its programming cable: the clone protocol and the layout of its channel
 * memory. Only the channels are touched: every other byte of the radio's memory is left exactly as it was read.
 */
interface RadioSpec : RadioLimits {
    val id: String
    val baud: Int

    /** The radio's memory image, as downloaded; [decode] and [encode] work on it. */
    val imageSize: Int

    fun decode(image: ByteArray, slot: Int): RadioChannel?

    fun encode(image: ByteArray, ch: RadioChannel)

    fun erase(image: ByteArray, slot: Int)

    /** Reads the memory image. Returns it with whether this firmware is one the app knows (writing is refused otherwise). */
    fun download(link: SerialLink, progress: (Float) -> Unit): Download

    /** Writes back the memory blocks that hold [slots]; [image] is the one from [download] with the edited channels encoded. */
    fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit)

    class Download(val image: ByteArray, val ident: String, val known: Boolean)
}

internal fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

internal fun SerialLink.readExactly(count: Int, timeoutMs: Int, what: String): ByteArray =
    read(count, timeoutMs).also { if (it.size != count) throw RadioException("Le talkie n'a pas répondu ($what).") }

/**
 * Baofeng UV-5R and its clones (BFB291 and earlier firmwares): 9600 baud, 128 memories of 16 bytes at 0x0000 and their
 * 7-character names at 0x1000. Layout and handshake follow the open clone-mode description used by CHIRP.
 */
class Uv5rSpec(private val pauseMs: Long = 50) : RadioSpec {
    override val id = "uv5r"
    override val label = "Baofeng UV-5R"
    override val baud = 9600
    override val slots = 128
    override val nameLength = 7
    override val bands = listOf(130_000_000L..180_000_000L, 400_000_000L..520_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Low)
    override val imageSize = 0x1800

    private fun pause() { if (pauseMs > 0) Thread.sleep(pauseMs) }

    override fun decode(image: ByteArray, slot: Int): RadioChannel? {
        val o = slot * REC
        if (image[o] == 0xFF.toByte()) return null
        val rx = (Bcd.decode(image, o) ?: return null) * 10
        val txRaw = Bcd.decode(image, o + 4)
        val b14 = image[o + 14].toInt()
        val b15 = image[o + 15].toInt()
        return RadioChannel(
            slot = slot, rxHz = rx, txHz = txRaw?.let { it * 10 },
            name = image.asciiName(NAMES + slot * REC, nameLength),
            rxTone = RadioTones.decode(image.u16(o + 8)), txTone = RadioTones.decode(image.u16(o + 10)),
            power = if (b14 and 3 == 0) RadioPower.High else RadioPower.Low,
            wide = b15 and 0x40 != 0, scan = b15 and 0x04 != 0,
        )
    }

    override fun encode(image: ByteArray, ch: RadioChannel) {
        val o = ch.slot * REC
        // A memory that already exists keeps its other bits (PTT-ID, busy-channel lockout…).
        if (image[o] == 0xFF.toByte()) image.fill(0, o, o + REC)
        Bcd.encode(ch.rxHz / 10, image, o)
        if (ch.txHz == null) image.fill(0xFF.toByte(), o + 4, o + 8) else Bcd.encode(ch.txHz / 10, image, o + 4)
        image.putU16(o + 8, RadioTones.encode(ch.rxTone))
        image.putU16(o + 10, RadioTones.encode(ch.txTone))
        image[o + 14] = ((image[o + 14].toInt() and 0xFC) or if (ch.power == RadioPower.High) 0 else 1).toByte()
        var b15 = image[o + 15].toInt() and 0xFF
        b15 = if (ch.wide) b15 or 0x40 else b15 and 0x40.inv()
        b15 = if (ch.scan) b15 or 0x04 else b15 and 0x04.inv()
        image[o + 15] = b15.toByte()
        image.putAsciiName(NAMES + ch.slot * REC, nameLength, ch.name)
    }

    override fun erase(image: ByteArray, slot: Int) {
        image.fill(0xFF.toByte(), slot * REC, slot * REC + REC)
        image.fill(0xFF.toByte(), NAMES + slot * REC, NAMES + slot * REC + REC)
    }

    private fun identify(link: SerialLink): String {
        var last = "aucune réponse"
        for (magic in MAGICS) {
            link.purge()
            for (b in magic) { link.write(byteArrayOf(b)); if (pauseMs > 0) Thread.sleep(10) }
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) { last = if (ack.isEmpty()) "aucune réponse" else "réponse ${ack.hex()}"; pause(); continue }
            link.write(byteArrayOf(0x02))
            // The ident ends with 0xDD; some firmwares send 12 bytes instead of 8.
            val resp = java.io.ByteArrayOutputStream()
            for (i in 0 until 12) {
                val b = link.read(1, 1000)
                if (b.isEmpty()) break
                resp.write(b[0].toInt())
                if (b[0] == 0xDD.toByte()) break
            }
            val ident = resp.toByteArray()
            if (ident.size != 8 && ident.size != 12) throw RadioException("Identification inattendue du talkie : ${ident.hex()}.")
            link.write(byteArrayOf(ACK))
            val ack2 = link.read(1, 1000)
            if (ack2.size != 1 || ack2[0] != ACK) throw RadioException("Le talkie a refusé le mode clonage.")
            return ident.hex()
        }
        throw RadioNoAnswer(
            "Le talkie ne répond pas ($last). Vérifiez que le câble est enfoncé à fond dans la prise du talkie, que celui-ci est allumé " +
                "et que son volume n'est pas à zéro.",
        )
    }

    private fun readBlock(link: SerialLink, addr: Int, size: Int, first: Boolean): ByteArray {
        link.write(byteArrayOf('S'.code.toByte(), (addr shr 8).toByte(), addr.toByte(), size.toByte()))
        if (!first) {
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) throw RadioException("Le talkie a refusé d'envoyer le bloc %04X.".format(addr))
        }
        val head = link.readExactly(4, 1000, "bloc %04X".format(addr))
        if (head[0] != 'X'.code.toByte() || (((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)) != addr || head[3].toInt() != size) {
            throw RadioException("Réponse inattendue du talkie au bloc %04X.".format(addr))
        }
        val data = link.readExactly(size, 1000, "bloc %04X".format(addr))
        link.write(byteArrayOf(ACK))
        pause()
        return data
    }

    override fun download(link: SerialLink, progress: (Float) -> Unit): RadioSpec.Download {
        val ident = identify(link)
        // The first block is read once and dropped: the radio answers it without the usual leading acknowledgement.
        readBlock(link, 0, BLOCK, true)
        val image = ByteArray(imageSize)
        for (a in 0 until imageSize step BLOCK) {
            readBlock(link, a, BLOCK, false).copyInto(image, a)
            progress((a + BLOCK).toFloat() / imageSize)
        }
        return RadioSpec.Download(image, ident, known = true)
    }

    override fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit) {
        identify(link)
        val regions = slots.sorted().flatMap { listOf(it * REC, NAMES + it * REC) }
        regions.forEachIndexed { i, a ->
            link.write(byteArrayOf('X'.code.toByte(), (a shr 8).toByte(), a.toByte(), REC.toByte()) + image.copyOfRange(a, a + REC))
            pause()
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) throw RadioException("Le talkie a refusé le bloc %04X : rien de plus n'a été écrit.".format(a))
            progress((i + 1f) / regions.size)
        }
    }

    private companion object {
        const val REC = 16
        const val NAMES = 0x1000
        const val BLOCK = 0x40
        const val ACK = 0x06.toByte()
        val MAGICS = listOf(
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25), // BFB291 and later
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x01, 0x25, 0x98.toByte(), 0x4D), // original firmware
        )
    }
}

/**
 * Radtel RT-470X (JC-8810 family): 57600 baud, 256 memories of 32 bytes at 0x0000 including their 12-character names.
 * Reading works with any firmware; writing only with the firmware identifications listed below.
 */
class Rt470xSpec(private val pauseMs: Long = 0) : RadioSpec {
    override val id = "rt470x"
    override val label = "Radtel RT-470X"
    override val baud = 57600
    override val slots = 256
    override val nameLength = 12
    override val bands = listOf(18_000_000L..1_000_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Medium, RadioPower.Low)
    override val imageSize = 0x2000

    override fun decode(image: ByteArray, slot: Int): RadioChannel? {
        val o = slot * REC
        if (image[o] == 0xFF.toByte()) return null
        val rx = (Bcd.decode(image, o) ?: return null) * 10
        val txRaw = Bcd.decode(image, o + 4)
        val power = when (image[o + 14].toInt() and 3) { 0 -> RadioPower.High; 2 -> RadioPower.Medium; else -> RadioPower.Low }
        val b15 = image[o + 15].toInt()
        return RadioChannel(
            slot = slot, rxHz = rx, txHz = txRaw?.let { it * 10 }, name = image.asciiName(o + 20, nameLength),
            rxTone = RadioTones.decode(image.u16(o + 8)), txTone = RadioTones.decode(image.u16(o + 10)),
            power = power, wide = b15 and 0x40 == 0, scan = b15 and 0x04 != 0,
        )
    }

    override fun encode(image: ByteArray, ch: RadioChannel) {
        val o = ch.slot * REC
        if (image[o] == 0xFF.toByte()) {
            image.fill(0, o, o + 16)
            image.fill(0xFF.toByte(), o + 16, o + REC)
        }
        Bcd.encode(ch.rxHz / 10, image, o)
        if (ch.txHz == null) image.fill(0xFF.toByte(), o + 4, o + 8) else Bcd.encode(ch.txHz / 10, image, o + 4)
        image.putU16(o + 8, RadioTones.encode(ch.rxTone))
        image.putU16(o + 10, RadioTones.encode(ch.txTone))
        val p = when (ch.power) { RadioPower.High -> 0; RadioPower.Medium -> 2; RadioPower.Low -> 1 }
        image[o + 14] = ((image[o + 14].toInt() and 0xFC) or p).toByte()
        var b15 = image[o + 15].toInt() and 0xFF
        b15 = if (ch.wide) b15 and 0x40.inv() else b15 or 0x40
        b15 = if (ch.scan) b15 or 0x04 else b15 and 0x04.inv()
        image[o + 15] = b15.toByte()
        image.putAsciiName(o + 20, nameLength, ch.name)
    }

    override fun erase(image: ByteArray, slot: Int) = image.fill(0xFF.toByte(), slot * REC, slot * REC + REC)

    private fun identify(link: SerialLink): Pair<String, Boolean> {
        var ok = false
        for (attempt in 0 until 5) {
            link.purge()
            link.write(MAGIC)
            val ack = link.read(1, 1000)
            if (ack.size == 1 && ack[0] == ACK) { ok = true; break }
            if (pauseMs > 0) Thread.sleep(pauseMs)
        }
        if (!ok) {
            throw RadioNoAnswer(
                "Le talkie ne répond pas. Vérifiez que le câble est enfoncé à fond dans la prise du talkie, que celui-ci est allumé, " +
                    "puis éteignez-le et rallumez-le.",
            )
        }
        link.write(byteArrayOf('F'.code.toByte()))
        val ident = link.readExactly(8, 1000, "identification")
        return ident.hex() to FINGERPRINTS.any { it.contentEquals(ident) }
    }

    private fun readBlock(link: SerialLink, addr: Int, size: Int): ByteArray {
        val cmd = byteArrayOf('R'.code.toByte(), (addr shr 8).toByte(), addr.toByte(), size.toByte())
        link.write(cmd)
        val resp = link.readExactly(4 + size, 1000, "bloc %04X".format(addr))
        if (!resp.copyOf(4).contentEquals(cmd)) throw RadioException("Réponse inattendue du talkie au bloc %04X.".format(addr))
        return resp.copyOfRange(4, resp.size)
    }

    override fun download(link: SerialLink, progress: (Float) -> Unit): RadioSpec.Download {
        val (ident, known) = identify(link)
        val image = ByteArray(imageSize)
        for (a in 0 until imageSize step BLOCK) {
            readBlock(link, a, BLOCK).copyInto(image, a)
            progress((a + BLOCK).toFloat() / imageSize)
        }
        runCatching { link.write(byteArrayOf('E'.code.toByte())) }
        return RadioSpec.Download(image, ident, known)
    }

    override fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit) {
        val (ident, known) = identify(link)
        if (!known) throw RadioException("Firmware non reconnu ($ident) : l'écriture est refusée par sécurité. La lecture reste possible.")
        // Whole 64-byte blocks, as the manufacturer's software writes them: two memories each.
        val blocks = slots.map { it * REC / BLOCK * BLOCK }.distinct().sorted()
        blocks.forEachIndexed { i, a ->
            val cmd = byteArrayOf('W'.code.toByte(), (a shr 8).toByte(), a.toByte(), BLOCK.toByte())
            link.write(cmd + image.copyOfRange(a, a + BLOCK))
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) throw RadioException("Le talkie a refusé le bloc %04X : rien de plus n'a été écrit.".format(a))
            progress((i + 1f) / blocks.size)
        }
        runCatching { link.write(byteArrayOf('E'.code.toByte())) }
    }

    private companion object {
        const val REC = 32
        const val BLOCK = 0x40
        const val ACK = 0x06.toByte()
        val MAGIC = "PROGRAMJC81U".toByteArray(Charsets.US_ASCII)

        /** Firmware identifications of the RT-470X (original board v1.18A, second board v2.10A and v2.13A). */
        val FINGERPRINTS = listOf(
            byteArrayOf(0, 0, 0, 0x20, 0, 0x20, 0xCC.toByte(), 0x04),
            byteArrayOf(0, 0, 0, 0x2C, 0, 0x20, 0xD8.toByte(), 0x04),
            byteArrayOf(0, 0, 0, 0x36, 0, 0x20, 0xDC.toByte(), 0x04),
        )
    }
}

object RadioSpecs {
    fun all(): List<RadioSpec> = listOf(Uv5rSpec(), Rt470xSpec())
}
