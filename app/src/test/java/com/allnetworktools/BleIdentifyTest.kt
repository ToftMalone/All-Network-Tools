package com.allnetworktools

import com.allnetworktools.data.Ad
import com.allnetworktools.data.AdStructure
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleIdentify
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.BleVendor
import com.allnetworktools.data.GattIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identification checked against real advertising payloads. */
class BleIdentifyTest {
    private fun ads(hex: String) = Ad.parseHex(hex)
    private fun id(hex: String, name: String? = null, gatt: GattIdentity? = null) = BleIdentify.identify(name, ads(hex), "AA:BB:CC:DD:EE:FF", gatt)
    private fun byName(name: String) = BleIdentify.identify(name, emptyList())

    @Test fun parsesStructuresAndRoundTrips() {
        val hex = "0201060A094D79204465766963650303F5FE"
        val list = ads(hex)
        assertEquals(listOf(0x01, 0x09, 0x03), list.map { it.type })
        assertEquals("My Device", Ad.name(list))
        assertEquals(hex, Ad.hex(list))
        assertEquals(setOf(0xFEF5), Ad.uuids16(list))
    }

    @Test fun mergesPacketsOfTheSameDevice() {
        val map = LinkedHashMap<String, AdStructure>()
        Ad.merge(map, ads("0303F5FE"))
        Ad.merge(map, ads("03030F18" + "0A094D7920446576696365"))
        assertEquals(setOf(0xFEF5, 0x180F), Ad.uuids16(map.values.toList()))
        assertEquals("My Device", Ad.name(map.values.toList()))
    }

    @Test fun airPodsProFromProximityPairing() {
        val r = id("1EFF4C000719010E202B8F" + "00".repeat(20))
        assertEquals(BleKind.Audio, r.kind)
        assertEquals("AirPods Pro", r.model)
        assertEquals("Apple", r.maker)
        assertTrue(r.confidence >= 90)
    }

    @Test fun iBeaconWithKnownVendorUuid() {
        val r = id("1AFF4C000215B9407F30F5F8466EAFF925556B57FE6D0001000 2C5".replace(" ", ""))
        assertEquals(BleKind.Tag, r.kind)
        assertTrue(r.model!!.contains("Estimote"))
        assertEquals("1 / 2", r.details.first { it.first == "Major / minor" }.second)
        assertEquals("B9407F30-F5F8-466E-AFF9-25556B57FE6D", r.details.first { it.first == "iBeacon UUID" }.second)
    }

    @Test fun findMyAccessoryIsATag() {
        assertEquals(BleKind.Tag, id("1EFF4C001219" + "10".repeat(25)).kind)
    }

    @Test fun genericAppleFrameIsOnlyAGuess() {
        val r = id("0AFF4C0010050B1C8E3D21")
        assertEquals(BleKind.Phone, r.kind)
        assertTrue(r.guessed)
        assertEquals("Apple", r.maker)
    }

    @Test fun windowsLaptopFromMicrosoftCdp() {
        val r = id("0FFF0600010F2002A1B2C3D4E5F60718")
        assertEquals(BleKind.Computer, r.kind)
        assertEquals("PC portable Windows", r.model)
    }

    @Test fun eddystoneUrlIsDecoded() {
        val r = id("0303AAFE0E16AAFE10E7036578616D706C6507")
        assertEquals(BleKind.Tag, r.kind)
        assertEquals("https://example.com", r.details.first { it.first == "URL" }.second)
    }

    @Test fun findHubTagUsesEddystoneFrame40() {
        val r = id("0303AAFE0A16AAFE40" + "00".repeat(6))
        assertEquals(BleKind.Tag, r.kind)
        assertEquals("Google", r.maker)
    }

    @Test fun trackersFromTheirServices() {
        assertEquals("Tile", id("0303EDFE").model)
        assertEquals("Galaxy SmartTag", id("03035AFD").model)
        assertEquals(BleKind.Tag, id("0303EDFE").kind)
    }

    @Test fun standardHealthServices() {
        assertEquals(BleKind.Health, id("03030D18").kind)
        assertEquals("Pèse-personne", id("03031D18").model)
        assertEquals(BleKind.Health, id("03031018").kind)
    }

    @Test fun auracastAndMeshServices() {
        assertEquals("Diffusion Auracast", id("03035218").model)
        val mesh = id("03032818")
        assertEquals(BleKind.Home, mesh.kind)
    }

    @Test fun appearanceTellsWhatItIs() {
        assertEquals("Clavier", id("0319C103").model)
        assertEquals("Manette de jeu", id("0319C403").model)
        assertEquals(BleKind.Watch, id("0319C200").kind)
        assertEquals("Écouteurs", id("03194109").model)
    }

    @Test fun classOfDevice() {
        assertEquals(BleKind.Audio, id("040D180400").kind)
        assertEquals(BleKind.Phone, id("040D0C0200").kind)
    }

    @Test fun ruuviTagShowsItsTemperature() {
        val r = id("1FFF9904" + "0510A44E20C864" + "00".repeat(19))
        assertEquals(BleKind.Home, r.kind)
        assertTrue(r.model!!.contains("21,3 °C"))
    }

    @Test fun teslaAndObdByName() {
        assertEquals(BleKind.Vehicle, byName("S0123456789abcdefC").kind)
        assertEquals(BleKind.Vehicle, byName("OBDII").kind)
    }

    @Test fun namesAreClassified() {
        assertEquals(BleKind.Audio, byName("LE_WH-1000XM4").kind)
        assertEquals(BleKind.Audio, byName("Pixel Buds Pro 2").kind)
        assertEquals(BleKind.Phone, byName("Pixel 8").kind)
        assertEquals(BleKind.Watch, byName("Galaxy Watch4 (A1B2)").kind)
        assertEquals(BleKind.Watch, byName("Mi Smart Band 8").kind)
        assertEquals(BleKind.Media, byName("[TV] Samsung Q80").kind)
        assertEquals(BleKind.Computer, byName("MacBook Air de Léa").kind)
        assertEquals(BleKind.Computer, byName("DESKTOP-ABC1234").kind)
        assertEquals("Souris", byName("MX Master 3S").model)
        assertEquals("Clavier", byName("MX Keys Mini").model)
        assertEquals("Thermomètre Govee", byName("GVH5075_1A2B").model)
        assertEquals(BleKind.Tag, byName("iTAG").kind)
        assertEquals(BleKind.Home, byName("ELK-BLEDOM").kind)
        assertEquals(BleKind.Health, byName("Polar H10 7A3B2C").kind)
        assertEquals(BleKind.Accessory, byName("Xbox Wireless Controller").kind)
    }

    @Test fun anAdvertisementWithNoCluesStaysUnknown() {
        val r = id("020106020AF4")
        assertEquals(BleKind.Unknown, r.kind)
        assertTrue(r.evidence.isNotEmpty())
        assertNull(r.model)
    }

    @Test fun aConnectionCanIdentifyWhatTheAdvertisementCannot() {
        assertEquals(BleKind.Unknown, id("020106").kind)
        val r = id("020106", gatt = GattIdentity(manufacturer = "Bose Corporation", model = "QC35 II", appearance = 0x0941))
        assertEquals(BleKind.Audio, r.kind)
        assertTrue(r.confidence >= 80)
    }

    @Test fun vendorsFollowTheIdentity() {
        val mesh = BleDevice.from("AA:BB:CC:DD:EE:01", "Node", -60, null, true, 0, ads("0303281802010 6".replace(" ", "")))
        assertTrue(BleVendor.Mesh in mesh.vendors)
        val pods = BleDevice.from("AA:BB:CC:DD:EE:02", null, -50, null, true, 0, ads("1EFF4C000719010E202B8F" + "00".repeat(20)))
        assertTrue(BleVendor.Apple in pods.vendors)
        assertFalse(BleVendor.Google in pods.vendors)
        assertEquals("AirPods Pro", pods.title)
    }
}
