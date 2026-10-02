package com.allnetworktools

import com.allnetworktools.ui.pages.sdr.MeshDiagInput
import com.allnetworktools.ui.pages.sdr.MeshDiagnosis
import com.allnetworktools.ui.pages.sdr.MeshHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshDiagnosisTest {
    private fun d(
        running: Boolean = true, usb: Double = 4.0, level: Double? = -50.0, noise: Double? = -52.0, peak: Double? = -51.0,
        pre: Int = 0, mis: Int = 0, hdr: Int = 0, seen: Int = -1, ok: Int = 0, bad: Int = 0, decoded: Int = 0, other: Int = 0,
    ) = MeshDiagnosis.verdict(MeshDiagInput(running, usb, level, noise, peak, pre, mis, hdr, seen, ok, bad, decoded, other))

    @Test fun idleBeforeStart() = assertEquals(MeshHealth.Idle, d(running = false).health)

    @Test fun noUsbStreamIsReportedFirst() {
        val v = d(usb = 0.0, pre = 3, ok = 2, decoded = 2)
        assertEquals(MeshHealth.NoUsb, v.health)
        assertTrue(v.text.contains("HackRF"))
    }

    @Test fun silenceMeansNoSignal() = assertEquals(MeshHealth.NoSignal, d(peak = -51.5, noise = -52.0).health)

    @Test fun aSignalWithoutPreambleKeepsWaiting() = assertEquals(MeshHealth.Waiting, d(peak = -35.0, noise = -52.0).health)

    @Test fun otherSyncWordIsNamed() {
        val v = d(pre = 4, mis = 4, seen = 0x34)
        assertEquals(MeshHealth.OtherNetwork, v.health)
        assertTrue(v.text.contains("0x34") && v.text.contains("LoRaWAN"))
        assertTrue(d(pre = 2, mis = 2, seen = 0x12).text.contains("privé"))
    }

    @Test fun badCrcAndUnreadableHeaders() {
        assertEquals(MeshHealth.BadCrc, d(pre = 3, bad = 3).health)
        assertEquals(MeshHealth.WeakOrMismatched, d(pre = 3, hdr = 3).health)
    }

    @Test fun otherChannelAndSuccess() {
        assertEquals(MeshHealth.OtherChannel, d(pre = 5, ok = 5, other = 5, decoded = 0).health)
        val v = d(pre = 5, ok = 5, decoded = 3, other = 2)
        assertEquals(MeshHealth.Working, v.health)
        assertTrue(v.text.contains("3"))
    }

    private fun v(dropped: Int = 0, off: Double? = null, w: Double? = null, pre: Int = 0, lost: Int = 0) = MeshDiagnosis.verdict(
        MeshDiagInput(true, 4.0, -40.0, -52.0, -35.0, pre, 0, 0, -1, 0, 0, 0, 0, dropped, off, w, 250.0, true, lost),
    )

    @Test fun lostUsbBlocksAreBlamedOnThePhone() = assertEquals(MeshHealth.Overload, v(dropped = 50).health)

    @Test fun waitingExplainsAnOffsetSignal() {
        val r = v(off = 180.0, w = 240.0)
        assertEquals(MeshHealth.Waiting, r.health)
        assertTrue(r.text.contains("décalage +180 kHz"))
        assertTrue(r.text.contains("fréquence"))
    }

    @Test fun waitingNotesANarrowSignal() = assertTrue(v(off = 5.0, w = 110.0).text.contains("125 kHz"))

    @Test fun waitingNotesLostFrames() = assertTrue(v(pre = 3, lost = 3).text.contains("se perd"))
}
