package com.allnetworktools

import com.allnetworktools.data.sdr.SondeLanding
import com.allnetworktools.data.sdr.SondeTracker
import com.allnetworktools.data.sdr.SondeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SondeLandingTest {
    @Test fun noPredictionWhileClimbing() {
        assertNull(SondeLanding.predict(48.0, 2.0, 20_000.0, 5.0, emptyList()))
    }

    @Test fun calmAirFallsStraightDown() {
        // Without wind the sonde lands where it is; 5 m/s at sea level from 1000 m takes a little over 3 minutes.
        val l = SondeLanding.predict(48.0, 2.0, 1000.0, -5.0 * Math.exp(1000.0 / 14_400), emptyList())!!
        assertEquals(48.0, l.lat, 1e-9); assertEquals(2.0, l.lon, 1e-9)
        // ∫ dh / (v0·e^(h/2H)) from 0 to 1000 m = (2H / v0)·(1 − e^(−1000/2H))
        assertEquals(14_400 / 5.0 * (1 - Math.exp(-1000.0 / 14_400)), l.inSeconds, 1.0)
    }

    @Test fun driftsWithTheWindOfEachLayer() {
        // 10 m/s wind to the east everywhere: the sonde ends east of where it is, by wind × fall time.
        val winds = (0..30).map { doubleArrayOf(it * 1000.0, 10.0, 0.0) }
        val l = SondeLanding.predict(45.0, 5.0, 10_000.0, -15.0, winds)!!
        assertEquals(45.0, l.lat, 1e-6)
        assertTrue(l.lon > 5.0)
        val km = (l.lon - 5.0) * 111.32 * Math.cos(Math.toRadians(45.0))
        assertEquals(10.0 * l.inSeconds / 1000, km, 0.3)
        // Faster at altitude: about 15 m/s at 10 km falls in roughly a quarter of an hour.
        assertTrue(l.inSeconds in 600.0..1500.0)
    }

    @Test fun trackerBuildsAProfileAndPredicts() {
        val t = SondeTracker()
        // Ascent with an east wind, then the fall.
        for (h in 0..200) t.update(SondeFrame("RS41", "V1", h, null, 48.0, 2.0 + h * 1e-4, h * 100.0, 8.0, 90.0, 5.0, 9), h * 1000L)
        t.update(SondeFrame("RS41", "V1", 201, null, 48.0, 2.03, 15_000.0, 8.0, 90.0, -20.0, 9), 300_000)
        val s = t.sondes.getValue("V1")
        assertTrue(s.winds.size > 100)
        val l = SondeLanding.predict(s.lat!!, s.lon!!, s.altitudeM!!, s.climbMs!!, s.winds.values.toList())
        assertNotNull(l)
        assertTrue(l!!.lon > 2.03)
    }
}
