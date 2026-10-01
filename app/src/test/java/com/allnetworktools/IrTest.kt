package com.allnetworktools

import com.allnetworktools.data.IrBrand
import com.allnetworktools.data.IrCode
import com.allnetworktools.data.IrEncoder
import com.allnetworktools.data.IrKey
import com.allnetworktools.data.IrProtocol
import com.allnetworktools.data.WifiDirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrTest {
    /** Decodes a pulse-distance frame back to its bytes (LSB first), skipping the leader. */
    private fun pulseDistanceBytes(p: IntArray, threshold: Int): List<Int> {
        val bits = (0 until 32).map { i -> if (p[3 + 2 * i] > threshold) 1 else 0 }
        return bits.chunked(8).map { b -> b.foldIndexed(0) { i, acc, bit -> acc or (bit shl i) } }
    }

    @Test fun necMatchesLgPowerCode() {
        // LG power is the well-known 0x20DF10EF: address 0x04, command 0x08, sent LSB first.
        val s = IrEncoder.encode(IrBrand.Lg.code(IrKey.Power)!!)
        assertEquals(38_000, s.carrier)
        assertEquals(9000, s.pattern[0])
        assertEquals(4500, s.pattern[1])
        assertEquals(listOf(0x04, 0xFB, 0x08, 0xF7), pulseDistanceBytes(s.pattern, 1000))
        assertEquals(68, s.pattern.size) // leader 2 + 32 bits × 2 + stop mark + trailer
        assertEquals(0, s.pattern.size % 2)
        // As the classic 32-bit value, MSB-first per byte: 20 DF 10 EF.
        val msbFirst = pulseDistanceBytes(s.pattern, 1000).map { Integer.reverse(it) ushr 24 }
        assertEquals(listOf(0x20, 0xDF, 0x10, 0xEF), msbFirst)
    }

    @Test fun samsungMatchesE0E040BF() {
        val s = IrEncoder.encode(IrBrand.Samsung.code(IrKey.Power)!!)
        assertEquals(4500, s.pattern[0])
        assertEquals(4500, s.pattern[1])
        val msbFirst = pulseDistanceBytes(s.pattern, 1000).map { Integer.reverse(it) ushr 24 }
        assertEquals(listOf(0xE0, 0xE0, 0x40, 0xBF), msbFirst)
    }

    @Test fun sonyRepeatsThreeFramesOn45ms() {
        val s = IrEncoder.encode(IrBrand.Sony.code(IrKey.Power)!!)
        assertEquals(40_000, s.carrier)
        assertEquals(78, s.pattern.size) // 3 × (leader 2 + 12 bits × 2)
        val first = s.pattern.copyOfRange(0, 26)
        assertEquals(45_000, first.sum())
        assertEquals(2400, first[0])
        // Command 0x15 = 0010101 LSB first: 1,0,1,0,1,0,0 then address 1: 1,0,0,0,0.
        val bits = (0 until 12).map { if (first[2 + 2 * it] == 1200) 1 else 0 }
        assertEquals(listOf(1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0), bits)
    }

    @Test fun rc5IsManchesterAndToggles() {
        val code = IrBrand.Philips.code(IrKey.Power)!!
        val a = IrEncoder.encode(code, rc5Toggle = false)
        val b = IrEncoder.encode(code, rc5Toggle = true)
        assertEquals(36_000, a.carrier)
        assertTrue(a != b)
        // Every duration is one or two half-bits (889 µs), except the trailing pause.
        a.pattern.dropLast(1).forEach { assertTrue("$it", it == 889 || it == 1778) }
        // 14 bits = 28 half-bits, minus the dropped leading space.
        val halves = a.pattern.dropLast(1).sumOf { it / 889 } + (a.pattern.last() % 40_000) / 889
        assertEquals(27, halves)
        assertEquals(0, a.pattern.size % 2)
    }

    @Test fun rejectsOutOfRangeCodes() {
        assertTrue(runCatching { IrEncoder.encode(IrCode(IrProtocol.Sirc12, 32, 1)) }.isFailure)
        assertTrue(runCatching { IrEncoder.encode(IrCode(IrProtocol.Rc5, 0, 64)) }.isFailure)
        assertTrue(runCatching { IrEncoder.encode(IrCode(IrProtocol.Nec, 255, 255)) }.isSuccess)
    }

    @Test fun everyBrandEncodesItsKeysUnderTwoSeconds() {
        IrBrand.entries.forEach { b ->
            assertNotNull(b.code(IrKey.Power))
            b.keys.keys.forEach { k -> assertTrue(IrEncoder.encode(b.code(k)!!).durationUs < 2_000_000) }
        }
        assertNull(IrBrand.Philips.code(IrKey.Source))
        assertTrue(IrEncoder.testBurst().durationUs < 2_000_000)
    }

    @Test fun wifiDirectDeviceTypes() {
        assertEquals("Imprimante", WifiDirect.category("3-0050F204-1")!!.label)
        assertEquals("Téléviseur", WifiDirect.category("7-0050F204-1")!!.label)
        assertEquals("Smartphone", WifiDirect.category("10-0050F204-5")!!.label)
        assertNull(WifiDirect.category("10-00E04C01-5"))
        assertNull(WifiDirect.category(null))
        assertNull(WifiDirect.category("garbage"))
    }
}
