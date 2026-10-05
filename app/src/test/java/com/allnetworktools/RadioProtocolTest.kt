package com.allnetworktools

import com.allnetworktools.data.radio.RadioChannel
import com.allnetworktools.data.radio.RadioException
import com.allnetworktools.data.radio.RadioPower
import com.allnetworktools.data.radio.RadioSpec
import com.allnetworktools.data.radio.RadioTones
import com.allnetworktools.data.radio.Rt470xSpec
import com.allnetworktools.data.radio.SerialLink
import com.allnetworktools.data.radio.Tone
import com.allnetworktools.data.radio.Uv5rSpec
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A radio on the other end of the cable: answers the clone protocol from a memory array. */
private abstract class SimRadio(val memory: ByteArray) : SerialLink {
    protected val inbox = ArrayDeque<Byte>()
    private val outbox = ArrayDeque<Byte>()
    val log = ByteArrayOutputStream()

    protected fun emit(vararg b: Int) = b.forEach { outbox.addLast(it.toByte()) }
    protected fun emit(b: ByteArray) = b.forEach { outbox.addLast(it) }

    abstract fun process()

    override fun write(data: ByteArray) {
        log.write(data)
        data.forEach { inbox.addLast(it) }
        process()
    }

    override fun read(count: Int, timeoutMs: Int): ByteArray {
        val n = minOf(count, outbox.size)
        return ByteArray(n) { outbox.removeFirst() }
    }

    override fun purge() { outbox.clear() }
    override fun close() = Unit

    protected fun take(n: Int): ByteArray? {
        if (inbox.size < n) return null
        return ByteArray(n) { inbox.removeFirst() }
    }

    protected fun peek(n: Int): ByteArray? = if (inbox.size < n) null else ByteArray(n) { inbox.elementAt(it) }
}

private class SimUv5r(memory: ByteArray, private val magic: ByteArray, private val ident: ByteArray) : SimRadio(memory) {
    private var stage = 0 // 0 magic, 1 wait 0x02, 2 wait host ack, 3 commands
    private var firstCommand = true
    var writes = 0

    override fun process() {
        while (true) {
            when (stage) {
                0 -> {
                    val m = take(magic.size) ?: return
                    if (m.contentEquals(magic)) { emit(0x06); stage = 1 } else { return }
                }
                1 -> { take(1) ?: return; emit(ident); stage = 2 }
                2 -> { take(1) ?: return; emit(0x06); stage = 3 }
                else -> {
                    val head = peek(1) ?: return
                    when (head[0].toInt().toChar()) {
                        'S' -> {
                            val c = take(4) ?: return
                            val addr = ((c[1].toInt() and 0xFF) shl 8) or (c[2].toInt() and 0xFF)
                            val size = c[3].toInt() and 0xFF
                            if (!firstCommand) emit(0x06)
                            firstCommand = false
                            emit('X'.code, c[1].toInt(), c[2].toInt(), c[3].toInt())
                            emit(memory.copyOfRange(addr, addr + size))
                        }
                        'X' -> {
                            val c = peek(4) ?: return
                            val size = c[3].toInt() and 0xFF
                            val full = take(4 + size) ?: return
                            val addr = ((full[1].toInt() and 0xFF) shl 8) or (full[2].toInt() and 0xFF)
                            full.copyOfRange(4, full.size).copyInto(memory, addr)
                            writes++
                            emit(0x06)
                        }
                        0x50.toChar() -> {
                            // A new handshake restarts the session.
                            val m = peek(magic.size) ?: return // the host sends the magic one byte at a time
                            if (m.contentEquals(magic)) { take(magic.size); emit(0x06); stage = 1; firstCommand = true } else take(1)
                        }
                        else -> { take(1) } // host acknowledgements
                    }
                }
            }
        }
    }
}

private class SimJc8810(memory: ByteArray, private val fingerprint: ByteArray, private val ignoreFirst: Int = 0) : SimRadio(memory) {
    private var inProgramming = false
    private var magicsSeen = 0
    var writes = 0
    private val magic = "PROGRAMJC81U".toByteArray()

    override fun process() {
        while (true) {
            if (!inProgramming) {
                val m = take(magic.size) ?: return
                magicsSeen++
                if (m.contentEquals(magic) && magicsSeen > ignoreFirst) { inProgramming = true; emit(0x06) }
                continue
            }
            val head = peek(1) ?: return
            when (head[0].toInt().toChar()) {
                'F' -> { take(1); emit(fingerprint) }
                'R' -> {
                    val c = take(4) ?: return
                    val addr = ((c[1].toInt() and 0xFF) shl 8) or (c[2].toInt() and 0xFF)
                    emit(c)
                    emit(memory.copyOfRange(addr, addr + (c[3].toInt() and 0xFF)))
                }
                'W' -> {
                    val c = peek(4) ?: return
                    val size = c[3].toInt() and 0xFF
                    val full = take(4 + size) ?: return
                    val addr = ((full[1].toInt() and 0xFF) shl 8) or (full[2].toInt() and 0xFF)
                    full.copyOfRange(4, full.size).copyInto(memory, addr)
                    writes++
                    emit(0x06)
                }
                else -> { take(1); inProgramming = false }
            }
        }
    }
}

class RadioProtocolTest {
    private val uvMagic = byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25)
    private val uvIdent = uvMagic + 0xDD.toByte()
    private val rtFingerprint = byteArrayOf(0, 0, 0, 0x36, 0, 0x20, 0xDC.toByte(), 0x04)

    private fun channel(slot: Int, mhz: Double, name: String, tx: Double? = mhz, rx: Tone = Tone.None, txTone: Tone = Tone.None) =
        RadioChannel(slot, (mhz * 1e6).toLong(), tx?.let { (it * 1e6).toLong() }, name, rx, txTone, RadioPower.Low, wide = false, scan = true)

    private fun emptyImage(spec: RadioSpec) = ByteArray(spec.imageSize) { 0xFF.toByte() }

    @Test fun tonesRoundTrip() {
        listOf(Tone.None, Tone.Ctcss(88.5), Tone.Ctcss(254.1), Tone.Dcs(23), Tone.Dcs(754, inverted = true), Tone.Dcs(645)).forEach {
            assertEquals(it, RadioTones.decode(RadioTones.encode(it)))
        }
        assertEquals(105, RadioTones.dcs.size)
        assertEquals(0x6A + 1, RadioTones.encode(Tone.Dcs(25, inverted = true)))
        assertEquals(885, RadioTones.encode(Tone.Ctcss(88.5)))
    }

    @Test fun uv5rImageCodecKeepsUnknownBits() {
        val spec = Uv5rSpec(0)
        val img = emptyImage(spec)
        spec.encode(img, channel(3, 145.6, "RV48", tx = 145.0, rx = Tone.None, txTone = Tone.Ctcss(88.5)))
        // PTT-ID bits written by the manufacturer's software must survive an edit.
        img[3 * 16 + 15] = (img[3 * 16 + 15].toInt() or 0x03).toByte()
        img[3 * 16 + 13] = 0x01
        val before = img.copyOf()
        spec.encode(img, channel(3, 145.6, "RV48", tx = 145.0, txTone = Tone.Ctcss(88.5)).copy(name = "RELAIS"))
        val c = spec.decode(img, 3)!!
        assertEquals(145_600_000L, c.rxHz)
        assertEquals(145_000_000L, c.txHz)
        assertEquals("RELAIS", c.name)
        assertEquals(Tone.Ctcss(88.5), c.txTone)
        assertFalse(c.wide)
        assertEquals(RadioPower.Low, c.power)
        assertEquals(0x01, img[3 * 16 + 13].toInt())
        assertEquals(0x03, img[3 * 16 + 15].toInt() and 0x03)
        // Only the name block differs from before.
        for (i in 0 until 3 * 16) assertEquals(before[i], img[i])
        assertNull(spec.decode(img, 4))
    }

    @Test fun uv5rListenOnlyChannelHasNoTransmitFrequency() {
        val spec = Uv5rSpec(0)
        val img = emptyImage(spec)
        spec.encode(img, channel(0, 156.8, "MARINE16", tx = null))
        assertNull(spec.decode(img, 0)!!.txHz)
        assertEquals(0xFF, img[4].toInt() and 0xFF)
    }

    @Test fun uv5rDownloadThenUploadWritesOnlyTheEditedMemory() {
        val spec = Uv5rSpec(0)
        val mem = emptyImage(spec)
        spec.encode(mem, channel(0, 446.00625, "PMR1"))
        spec.encode(mem, channel(1, 446.01875, "PMR2"))
        val radio = SimUv5r(mem.copyOf(), uvMagic, uvIdent)
        val d = spec.download(radio, {})
        assertTrue(d.known)
        assertEquals("PMR1", spec.decode(d.image, 0)!!.name)
        assertArrayEquals(mem, d.image)

        spec.encode(d.image, channel(1, 446.01875, "CHAT"))
        spec.upload(radio, d.image, listOf(1), {})
        assertEquals(2, radio.writes) // the 16-byte memory and its name
        assertEquals("CHAT", spec.decode(radio.memory, 1)!!.name)
        assertEquals("PMR1", spec.decode(radio.memory, 0)!!.name)
    }

    @Test fun uv5rNoAnswerGivesAHelpfulMessage() {
        val silent = object : SerialLink {
            override fun write(data: ByteArray) = Unit
            override fun read(count: Int, timeoutMs: Int) = ByteArray(0)
            override fun purge() = Unit
            override fun close() = Unit
        }
        try {
            Uv5rSpec(0).download(silent, {})
            fail("should not succeed")
        } catch (e: RadioException) {
            assertTrue(e.message!!.contains("câble"))
        }
    }

    @Test fun rt470xDownloadAndUpload() {
        val spec = Rt470xSpec()
        val mem = emptyImage(spec)
        spec.encode(mem, channel(0, 433.5, "APPEL", rx = Tone.Ctcss(67.0), txTone = Tone.Ctcss(67.0)))
        spec.encode(mem, channel(1, 118.1, "AIR", tx = null))
        spec.encode(mem, channel(2, 145.5, "VHF"))
        val radio = SimJc8810(mem.copyOf(), rtFingerprint)
        val d = spec.download(radio, {})
        assertTrue(d.known)
        assertArrayEquals(mem, d.image)
        val c0 = spec.decode(d.image, 0)!!
        assertEquals("APPEL", c0.name)
        assertEquals(Tone.Ctcss(67.0), c0.rxTone)
        assertNull(spec.decode(d.image, 1)!!.txHz)

        spec.encode(d.image, c0.copy(name = "BASE"))
        spec.upload(radio, d.image, listOf(0), {})
        assertEquals(1, radio.writes) // one 64-byte block holding memories 0 and 1
        assertEquals("BASE", spec.decode(radio.memory, 0)!!.name)
        assertEquals("AIR", spec.decode(radio.memory, 1)!!.name)
        assertEquals("VHF", spec.decode(radio.memory, 2)!!.name)
    }

    @Test fun rt470xRetriesTheHandshake() {
        val spec = Rt470xSpec()
        val radio = SimJc8810(emptyImage(spec), rtFingerprint, ignoreFirst = 2)
        assertNotNull(spec.download(radio, {}))
    }

    @Test fun rt470xUnknownFirmwareCanBeReadButNotWritten() {
        val spec = Rt470xSpec()
        val mem = emptyImage(spec)
        spec.encode(mem, channel(0, 433.5, "APPEL"))
        val radio = SimJc8810(mem.copyOf(), byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val d = spec.download(radio, {})
        assertFalse(d.known)
        assertEquals("APPEL", spec.decode(d.image, 0)!!.name)
        try {
            spec.upload(radio, d.image, listOf(0), {})
            fail("writing must be refused")
        } catch (e: RadioException) {
            assertTrue(e.message!!.contains("Firmware non reconnu"))
        }
        assertEquals(0, radio.writes)
    }
}

private class SimCables(context: android.content.Context, private val link: SerialLink) : com.allnetworktools.data.radio.RadioCableRepository(context) {
    override fun hasPermission(c: com.allnetworktools.data.radio.RadioCable) = true
    override fun open(c: com.allnetworktools.data.radio.RadioCable, baud: Int): SerialLink = link
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelsControllerTest {
    private val cable = com.allnetworktools.data.radio.RadioCable("FTDI", null)

    private fun waitIdle(c: com.allnetworktools.ui.pages.talkie.ChannelsController) {
        val end = System.currentTimeMillis() + 30_000
        while (c.busy && System.currentTimeMillis() < end) Thread.sleep(20)
        assertFalse("still busy", c.busy)
    }

    @Test fun readEditWriteVerifyAndRestore() {
        val spec = Uv5rSpec(0)
        val mem = ByteArray(spec.imageSize) { 0xFF.toByte() }
        fun ch(slot: Int, mhz: Double, name: String) = RadioChannel(slot, (mhz * 1e6).toLong(), (mhz * 1e6).toLong(), name, wide = false, power = RadioPower.Low)
        listOf(ch(0, 145.5, "APPEL"), ch(1, 446.00625, "PMR1"), ch(2, 433.5, "UHF")).forEach { spec.encode(mem, it) }
        val radio = SimUv5r(mem, byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25), byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25, 0xDD.toByte()))
        val c = com.allnetworktools.ui.pages.talkie.ChannelsController(
            SimCables(androidx.test.core.app.ApplicationProvider.getApplicationContext(), radio),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), listOf(spec),
        )

        c.read(cable)
        waitIdle(c)
        assertEquals(3, c.count)
        assertEquals(0, c.changed)
        assertEquals("PMR1", c.channels[1]!!.name)

        c.edit(c.channels[1]!!.copy(name = "chat", txHz = null))
        c.clear(2)
        assertEquals(2, c.changed)
        c.write(cable)
        waitIdle(c)
        assertEquals(c.message?.text, com.allnetworktools.ui.pages.talkie.ProgMessage.Kind.Success, c.message?.kind)
        assertTrue(c.message!!.text.contains("vérifiés"))
        assertEquals("CHAT", spec.decode(radio.memory, 1)!!.name)
        assertNull(spec.decode(radio.memory, 1)!!.txHz)
        assertNull(spec.decode(radio.memory, 2))
        assertEquals("APPEL", spec.decode(radio.memory, 0)!!.name)
        assertTrue(c.canRestore)

        c.restore(cable)
        waitIdle(c)
        assertEquals("PMR1", spec.decode(radio.memory, 1)!!.name)
        assertEquals("UHF", spec.decode(radio.memory, 2)!!.name)
        assertEquals(3, c.count)
    }

    @Test fun anIdenticalListWritesNothing() {
        val spec = Uv5rSpec(0)
        val mem = ByteArray(spec.imageSize) { 0xFF.toByte() }
        spec.encode(mem, RadioChannel(0, 145_500_000, 145_500_000, "APPEL"))
        val magic = byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25)
        val radio = SimUv5r(mem, magic, magic + 0xDD.toByte())
        val c = com.allnetworktools.ui.pages.talkie.ChannelsController(
            SimCables(androidx.test.core.app.ApplicationProvider.getApplicationContext(), radio),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), listOf(spec),
        )
        c.read(cable); waitIdle(c)
        c.write(cable); waitIdle(c)
        assertEquals(0, radio.writes)
        assertTrue(c.message!!.text.contains("rien à écrire"))
    }

    @Test fun presetsFillFreeMemoriesAndRespectListenOnly() {
        val spec = Uv5rSpec(0)
        val radio = SimUv5r(ByteArray(spec.imageSize), byteArrayOf(), byteArrayOf())
        val c = com.allnetworktools.ui.pages.talkie.ChannelsController(
            SimCables(androidx.test.core.app.ApplicationProvider.getApplicationContext(), radio),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), listOf(spec),
        )
        val pmr = com.allnetworktools.data.radio.RadioPresets.all.first { it.id == "pmr446" }
        val r = c.insertPreset(pmr, listenOnly = true)
        assertEquals(16, r.added)
        assertEquals(16, c.count)
        assertTrue(c.channels.filterNotNull().all { it.txHz == null })
        val marine = com.allnetworktools.data.radio.RadioPresets.all.first { it.id == "marine" }
        c.insertPreset(marine, listenOnly = false)
        assertTrue(c.channels.filterNotNull().drop(16).all { it.txHz == null }) // marine never transmits
        val amateur = com.allnetworktools.data.radio.RadioPresets.all.first { it.id == "radioamateur" }
        c.insertPreset(amateur, listenOnly = false)
        val appel = c.channels.filterNotNull().first { it.name == "APPEL2M" }
        assertEquals(145_500_000L, appel.txHz)
        assertNull(c.channels.filterNotNull().first { it.name == "ISS VOX" }.txHz) // a downlink: never
    }
}
