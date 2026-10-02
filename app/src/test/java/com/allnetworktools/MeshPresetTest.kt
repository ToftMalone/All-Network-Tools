package com.allnetworktools

import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.MeshPreset
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.MeshRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPresetTest {
    @Test fun defaultFrequenciesMatchTheFirmwareChannelPlan() {
        // Well-known defaults of the LongFast preset.
        assertEquals(869.525, MeshRadio.frequencyMhz(MeshRegion.Eu868, MeshPreset.LongFast)!!, 1e-9)
        assertEquals(433.875, MeshRadio.frequencyMhz(MeshRegion.Eu433, MeshPreset.LongFast)!!, 1e-9)
        assertEquals(906.875, MeshRadio.frequencyMhz(MeshRegion.Us, MeshPreset.LongFast)!!, 1e-9)
        assertEquals(919.875, MeshRadio.frequencyMhz(MeshRegion.Anz, MeshPreset.LongFast)!!, 1e-9)
        assertEquals(104, MeshRadio.channels(MeshRegion.Us, MeshPreset.LongFast))
        assertEquals(19, MeshRadio.slot(MeshRegion.Us, MeshPreset.LongFast))
    }

    @Test fun otherPresetsFollowTheirOwnNameAndWidth() {
        // Half-width channels: twice as many slots, and the frequency stays inside the band.
        for (r in MeshRegion.entries) for (p in MeshPreset.entries) {
            val f = MeshRadio.frequencyMhz(r, p)
            if (f == null) { assertTrue("${r.name}/${p.name}", p.bandwidthHz / 1e6 > r.endMhz - r.startMhz); continue }
            val half = p.bandwidthHz / 2e6
            assertTrue("${r.name}/${p.name} $f", f - half >= r.startMhz - 1e-9 && f + half <= r.endMhz + 1e-9)
        }
        assertEquals(2, MeshRadio.channels(MeshRegion.Eu868, MeshPreset.LongSlow))
        assertNull(MeshRadio.frequencyMhz(MeshRegion.Eu868, MeshPreset.ShortTurbo)) // 500 kHz do not fit in 250 kHz
        assertEquals(MeshRadio.hash("LongSlow") % 2, MeshRadio.slot(MeshRegion.Eu868, MeshPreset.LongSlow)!!.toLong())
    }

    @Test fun presetsGiveTwoSamplesPerChipAtTwoMegasamples() {
        for (p in MeshPreset.entries) assertEquals(p.name, 2.0 * p.bandwidthHz, 2_000_000.0 / p.decimation, 1e-6)
    }

    @Test fun lowDataRateFollowsTheSixteenMillisecondRule() {
        assertFalse(LoraPhy.lowDataRate(11, 250_000.0))
        assertTrue(LoraPhy.lowDataRate(11, 125_000.0))
        assertTrue(LoraPhy.lowDataRate(12, 125_000.0))
        assertFalse(LoraPhy.lowDataRate(10, 250_000.0))
        assertFalse(LoraPhy.lowDataRate(11, 500_000.0))
        // One more row per block means fewer rows' worth of payload per symbol.
        assertTrue(LoraPhy.payloadSymbols(12, 60, 4, true, ldro = true) > LoraPhy.payloadSymbols(12, 60, 4, true, ldro = false))
    }

    private fun roundTrip(p: MeshPreset, cr: Int, payload: ByteArray, snrDb: Double, cfoHz: Double, seed: Long, delay: Double = 0.0231) {
        val bw = p.bandwidthHz.toDouble()
        val ldro = LoraPhy.lowDataRate(p.sf, bw)
        val frame = LoraTx.frame(LoraTx.symbols(payload, p.sf, cr, ldro = ldro), 0x2B)
        // One crystal drives both the carrier and the chip clock, so the offset is the same number of ppm on each.
        val ppm = cfoHz / 869_525_000.0 * 1e6
        val (re, im) = LoraTx.waveform(frame, p.sf, bw, 2 * bw, delay = delay, cfoHz = cfoHz, ppm = ppm, snrDb = snrDb, seed = seed)
        val got = ArrayList<com.allnetworktools.data.sdr.LoraFrame>()
        val rx = LoraReceiver(p.sf, bw, 869_525_000.0, 0x2B) { got += it }
        var i = 0
        while (i < re.size) { val n = minOf(8192, re.size - i); rx.feed(re.copyOfRange(i, i + n), im.copyOfRange(i, i + n), n); i += n }
        assertEquals("${p.name}: frames", 1, got.size)
        assertTrue("${p.name}: crc", got[0].crcOk)
        assertEquals(payload.toList(), got[0].payload.toList())
        assertEquals(cr, got[0].cr)
    }

    private val message = ByteArray(40) { (it * 7 + 3).toByte() }

    @Test fun decodesShortFastAndMediumPresets() {
        roundTrip(MeshPreset.ShortFast, 1, message, 5.0, 1800.0, 21)
        roundTrip(MeshPreset.MediumFast, 1, message, 0.0, -2500.0, 22)
        roundTrip(MeshPreset.MediumSlow, 1, message, -4.0, 900.0, 23)
    }

    @Test fun decodesTurboAtFiveHundredKilohertz() {
        roundTrip(MeshPreset.ShortTurbo, 1, message, 5.0, 3000.0, 24)
        roundTrip(MeshPreset.LongTurbo, 4, message, 0.0, -4000.0, 25)
    }

    @Test fun decodesLongModerateAndLongSlowWithLowDataRateOptimisation() {
        roundTrip(MeshPreset.LongModerate, 4, message, -4.0, 700.0, 26)
        roundTrip(MeshPreset.LongSlow, 4, message, -10.0, -1200.0, 27)
    }

    /**
     * A fractional timing offset with the chirp's fold in the middle of the analysis window splits the preamble peak in
     * two lobes either side of the true bin (the middle bin is empty). This alignment used to go undetected.
     */
    @Test fun detectsPreambleWhoseDechirpedPeakSplits() {
        // Half a chip late: 0.0231 s × 125 kchip/s = 2887.5 chips.
        roundTrip(MeshPreset.LongModerate, 4, message, -4.0, 700.0, 26, delay = 0.0231)
        roundTrip(MeshPreset.LongModerate, 4, message, -4.0, 700.0, 27, delay = 0.0231)
    }
}
