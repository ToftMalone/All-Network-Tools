package com.allnetworktools

import android.net.wifi.ScanResult
import com.allnetworktools.data.Ad
import com.allnetworktools.data.AdvBuilder
import com.allnetworktools.data.AdvConfig
import com.allnetworktools.data.AdvPreset
import com.allnetworktools.data.EvilTwin
import com.allnetworktools.data.FollowLevel
import com.allnetworktools.data.FrOperator
import com.allnetworktools.data.Sighting
import com.allnetworktools.data.TowerQuery
import com.allnetworktools.data.TowerRepository
import com.allnetworktools.data.TrackerDetect
import com.allnetworktools.data.TrackerNet
import com.allnetworktools.data.TwinRisk
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.chooseOperator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewToolsTest {
    private fun ap(ssid: String, bssid: String, rssi: Int, sec: String) = WifiAp(ssid, bssid, rssi, 5180, 80, sec, ScanResult.WIFI_STANDARD_11AX, 5210)

    // ---- Evil twin ----------------------------------------------------------------------------

    @Test fun openCopyOfProtectedNetworkIsHighRisk() {
        val r = EvilTwin.analyze(listOf(ap("Maison", "a4:3e:51:00:00:01", -60, "WPA2"), ap("Maison", "a4:3e:51:00:00:02", -65, "WPA2"), ap("Maison", "02:11:22:33:44:55", -40, "Ouvert"))).single()
        assertEquals(TwinRisk.High, r.risk)
        assertTrue("02:11:22:33:44:55" in r.flagged)
        assertFalse("a4:3e:51:00:00:01" in r.flagged)
    }

    @Test fun meshFromOneMakerIsNormal() {
        val r = EvilTwin.analyze(
            listOf(ap("Mesh", "a4:3e:51:00:00:01", -50, "WPA2/3"), ap("Mesh", "a6:3e:51:00:00:01", -52, "WPA2/3"), ap("Mesh", "a4:3e:51:10:00:07", -70, "WPA2/3")),
        ).single()
        assertEquals(TwinRisk.None, r.risk)
    }

    @Test fun strongerApFromAnotherMakerIsHighRisk() {
        val r = EvilTwin.analyze(listOf(ap("Bureau", "70:fc:8f:00:00:01", -70, "WPA2"), ap("Bureau", "70:fc:8f:00:00:02", -72, "WPA2"), ap("Bureau", "b8:27:eb:00:00:09", -45, "WPA2"))).single()
        assertEquals(TwinRisk.High, r.risk)
        assertEquals(setOf("b8:27:eb:00:00:09"), r.flagged)
    }

    @Test fun wpa3DowngradeIsSuspect() {
        val r = EvilTwin.analyze(listOf(ap("Box", "a4:3e:51:00:00:01", -60, "WPA3"), ap("Box", "a4:3e:51:00:00:02", -62, "WPA2"))).single()
        assertEquals(TwinRisk.Medium, r.risk)
    }

    @Test fun communityHotspotsAreOnlyNoted() {
        val r = EvilTwin.analyze(
            listOf(ap("FreeWifi_secure", "a4:3e:51:00:00:01", -60, "WPA2-EAP"), ap("FreeWifi_secure", "14:0c:76:00:00:01", -70, "WPA2-EAP"), ap("FreeWifi_secure", "68:a3:78:00:00:01", -80, "WPA2-EAP")),
        ).single()
        assertEquals(TwinRisk.Low, r.risk)
    }

    @Test fun apAppearingLaterIsNoted() {
        val t0 = 1_000_000L
        val list = listOf(ap("Box", "a4:3e:51:00:00:01", -60, "WPA2"), ap("Box", "a4:3e:51:00:00:02", -62, "WPA2"))
        val r = EvilTwin.analyze(list, mapOf("a4:3e:51:00:00:01" to t0, "a4:3e:51:00:00:02" to t0 + 120_000), mapOf("Box" to t0), t0 + 180_000).single()
        assertEquals(TwinRisk.Low, r.risk)
        assertEquals("Apparu pendant l'analyse", r.findings.single().title)
    }

    @Test fun fakeScenarioFlagsTheOpenFreeboxCopy() {
        val reports = EvilTwin.analyze(scan + twins)
        val free = reports.first { it.ssid == "Freebox-7A2C" }
        assertEquals(TwinRisk.High, free.risk)
        assertTrue("02:4a:91:c3:5e:10" in free.flagged)
        assertTrue("a4:3e:51:7c:2a:9f" !in free.flagged)
        assertEquals(TwinRisk.None, EvilTwin.analyze(scan).first { it.ssid == "Freebox-7A2C" }.risk)
    }

    // ---- Trackers -------------------------------------------------------------------------------

    @Test fun classifiesFindMySeparatedAndNearOwner() {
        val separated = TrackerDetect.classify(Ad.parseHex("1EFF4C00121910" + "5A".repeat(22) + "0201"))!!
        assertEquals(TrackerNet.AppleFindMy, separated.net)
        assertEquals(true, separated.separated)
        val near = TrackerDetect.classify(Ad.parseHex("07FF4C0012022400"))!!
        assertEquals(false, near.separated)
        // An iPhone's Nearby Info frame (0x10) is not a tracker.
        assertNull(TrackerDetect.classify(Ad.parseHex("0AFF4C0010050B1C8E3D21")))
    }

    @Test fun classifiesGoogleSamsungAndTile() {
        assertEquals(true, TrackerDetect.classify(Ad.parseHex("1916AAFE41" + "AB".repeat(20) + "00"))!!.separated)
        assertEquals(false, TrackerDetect.classify(Ad.parseHex("1916AAFE40" + "AB".repeat(20) + "00"))!!.separated)
        assertEquals(TrackerNet.SamsungFind, TrackerDetect.classify(Ad.parseHex("07165AFD10223344"))!!.net)
        assertEquals(TrackerNet.Tile, TrackerDetect.classify(Ad.parseHex("0303EDFE"))!!.net)
        // Eddystone-URL is a beacon, not a tracker.
        assertNull(TrackerDetect.classify(Ad.parseHex("0303AAFE0E16AAFE10E7036578616D706C6507")))
    }

    @Test fun separatedTagThatTravelsWithYouIsFollowing() {
        val sig = TrackerDetect.classify(Ad.parseHex("1EFF4C00121910" + "5A".repeat(22) + "0201"))!!
        val walk = (0..12).map { Sighting(it * 60_000L, 48.85 + it * 0.001, 2.35, -60) }
        val a = TrackerDetect.assess(sig, walk)
        assertEquals(FollowLevel.Following, a.level)
        assertTrue(a.spreadM!! > 1000)
        // Same tag, but staying in one place for 12 minutes (a neighbour's keys): only worth watching.
        val still = (0..12).map { Sighting(it * 60_000L, 48.85, 2.35, -60) }
        assertEquals(FollowLevel.Watch, TrackerDetect.assess(sig, still).level)
        // A tag announcing its owner is nearby.
        val near = TrackerDetect.classify(Ad.parseHex("07FF4C0012022400"))!!
        assertEquals(FollowLevel.WithOwner, TrackerDetect.assess(near, walk).level)
    }

    // ---- Advertiser ------------------------------------------------------------------------------

    @Test fun buildsIBeaconFrame() {
        val s = AdvBuilder.structures(AdvConfig(beaconUuid = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", major = 1, minor = 2, measuredPower = -59), null).getOrThrow().single()
        assertEquals(Ad.MANUFACTURER, s.type)
        assertEquals("4C000215E2C56DB5DFFB48D2B060D0F5A71096E000010002C5", s.data.joinToString("") { "%02X".format(it) })
        assertEquals(27, AdvBuilder.size(listOf(s), false))
        // Parsed back by the identification engine as an iBeacon.
        assertEquals("iBeacon", com.allnetworktools.data.BleIdentify.identify(null, listOf(s)).model?.substringBefore(" ("))
    }

    @Test fun encodesEddystoneUrl() {
        assertArrayEquals(byteArrayOf(0x03, 'e'.code.toByte(), 'x'.code.toByte(), 0x07), AdvBuilder.eddystoneUrl("https://ex.com"))
        assertArrayEquals(byteArrayOf(0x00) + "example".toByteArray() + byteArrayOf(0x01) + "x".toByteArray(), AdvBuilder.eddystoneUrl("http://www.example.org/x"))
        assertNull(AdvBuilder.eddystoneUrl("ftp://example.com"))
        val frame = AdvBuilder.structures(AdvConfig(preset = AdvPreset.Eddystone, url = "https://example.com"), null).getOrThrow()
        assertEquals("Beacon Eddystone-URL", com.allnetworktools.data.BleIdentify.identify(null, frame).model)
    }

    @Test fun rejectsBadInput() {
        assertTrue(AdvBuilder.structures(AdvConfig(preset = AdvPreset.Manufacturer, payloadHex = "0G"), null).isFailure)
        assertTrue(AdvBuilder.structures(AdvConfig(beaconUuid = "pas-un-uuid"), null).isFailure)
        val long = AdvBuilder.structures(AdvConfig(preset = AdvPreset.Manufacturer, payloadHex = "00".repeat(30)), null).getOrThrow()
        assertTrue(AdvBuilder.size(long, false) > 31)
        val svc = AdvBuilder.structures(AdvConfig(preset = AdvPreset.Service, serviceUuid = "0xFFF0", serviceDataHex = "0102"), null).getOrThrow()
        assertEquals(setOf(0xFFF0), Ad.uuids16(svc))
    }

    // ---- Operators and ANFR data -------------------------------------------------------------------

    @Test fun operatorFollowsTheSimThenTheNetwork() {
        assertEquals(FrOperator.Free, chooseOperator("20815", "20801")!!.operator)
        assertTrue(chooseOperator("20815", "20801")!!.fromSim)
        // MVNO SIM (Transatel 208 22) on Orange's network: Orange sites.
        assertEquals(FrOperator.Orange, chooseOperator("20822", "20801")!!.operator)
        assertEquals(FrOperator.Bouygues, chooseOperator(null, "20820")!!.operator)
        assertEquals(FrOperator.Sfr, chooseOperator("20810", null)!!.operator)
        assertNull(chooseOperator("23415", "23415"))
    }

    @Test fun parsesAnfrRecordsIntoSites() {
        val json = javaClass.classLoader!!.getResource("anfr_free_paris.json")!!.readText()
        val q = TowerQuery(FrOperator.Free, 48.8566, 2.3522, 600)
        val r = TowerRepository.parse(q, json, 0)
        assertEquals(56, r.emitterCount)
        assertEquals(11, r.sites.size)
        assertFalse(r.truncated)
        assertTrue(r.sites.zipWithNext().all { (a, b) -> a.distanceTo(q.lat, q.lon) <= b.distanceTo(q.lat, q.lon) })
        assertTrue(r.sites.all { it.distanceTo(q.lat, q.lon) <= 650 })
        val pompidou = r.sites.first { it.supportId == 579309L }
        assertTrue("CENTRE GEORGES POMPIDOU" in pompidou.address)
        assertEquals(53.0, pompidou.heightM!!, 0.0)
        assertTrue(pompidou.emitters.any { it.system == "5G NR 700" })
        assertEquals(listOf(70, 160), pompidou.emitters.first { it.system == "5G NR 700" }.azimuths)
        assertNotNull(r.dataUpdated)
    }

    @Test fun ignoresRecordsOfOtherOperators() {
        val json = javaClass.classLoader!!.getResource("anfr_free_paris.json")!!.readText()
        val r = TowerRepository.parse(TowerQuery(FrOperator.Orange, 48.8566, 2.3522, 600), json, 0)
        assertTrue(r.sites.isEmpty())
    }
}
