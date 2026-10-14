package com.allnetworktools

import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellState
import com.allnetworktools.data.HandoverLog
import com.allnetworktools.data.PositionSet
import com.allnetworktools.data.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverTest {
    private fun cell(tech: RadioTech, pci: Int, id: Long?) = CellMeasure(
        tech, true, "B3", "EARFCN", 1850, pci, 1, id, null, null, "208", "01", -95, -10, 5, -70, null, null, null, null,
    )

    private fun state(tech: String, s: CellMeasure) = CellState("Orange F", 1, tech, tech, tech, false, true, s, emptyList(), emptyList(), 3, true)

    @Test fun recordsOnlyChanges() {
        HandoverLog.setForTest(emptyList())
        val p = PositionSet()
        assertNotNull(HandoverLog.observe(state("4G", cell(RadioTech.LTE, 10, 111)), p, 1_000))
        assertNull(HandoverLog.observe(state("4G", cell(RadioTech.LTE, 10, 111)), p, 2_000))
        val e = HandoverLog.observe(state("4G", cell(RadioTech.LTE, 20, 222)), p, 3_000)!!
        assertEquals("LTE 111", e.fromCell)
        assertEquals("LTE 222", e.toCell)
        // Same cell, other technology label (4G → 4G+): also a change.
        assertNotNull(HandoverLog.observe(state("4G+", cell(RadioTech.LTE, 20, 222)), p, 4_000))
        val ev = HandoverLog.events.value
        assertEquals(3, ev.size)
        assertTrue(ev.first().isStart)
        val csv = HandoverLog.csv().lines()
        assertTrue(csv.first().startsWith("heure_utc,operateur"))
        assertEquals(4, csv.count { it.isNotBlank() })
        HandoverLog.setForTest(emptyList())
    }
}
