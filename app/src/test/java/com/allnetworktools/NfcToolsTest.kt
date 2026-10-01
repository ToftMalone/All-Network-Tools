package com.allnetworktools

import com.allnetworktools.data.LinkSample
import com.allnetworktools.data.NdefBuild
import com.allnetworktools.data.NdefDecode
import com.allnetworktools.data.NfcChip
import com.allnetworktools.data.NfcRecordKind
import com.allnetworktools.data.NfcWriteRequest
import com.allnetworktools.data.WifiTagSecurity
import com.allnetworktools.data.WscToken
import com.allnetworktools.data.testPattern
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
class NfcToolsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---- Wi-Fi Simple Configuration token ------------------------------------------------------

    @Test fun wscTokenHasTheLayoutAndroidParses() {
        val t = WscToken.encode("Maison", "motdepasse1", WifiTagSecurity.Wpa2)
        // Version attribute first, then the credential.
        assertArrayEquals(bytes(0x10, 0x4A, 0x00, 0x01, 0x10), t.copyOfRange(0, 5))
        assertArrayEquals(bytes(0x10, 0x0E), t.copyOfRange(5, 7))
        val credLen = (t[7].toInt() and 0xFF shl 8) or (t[8].toInt() and 0xFF)
        val cred = t.copyOfRange(9, 9 + credLen)
        // Network index, then SSID "Maison".
        assertArrayEquals(bytes(0x10, 0x26, 0x00, 0x01, 0x01), cred.copyOfRange(0, 5))
        assertArrayEquals(bytes(0x10, 0x45, 0x00, 0x06) + "Maison".toByteArray(), cred.copyOfRange(5, 15))
        // Auth WPA2-PSK, encryption AES.
        assertArrayEquals(bytes(0x10, 0x03, 0x00, 0x02, 0x00, 0x20), cred.copyOfRange(15, 21))
        assertArrayEquals(bytes(0x10, 0x0F, 0x00, 0x02, 0x00, 0x08), cred.copyOfRange(21, 27))
        assertArrayEquals(bytes(0x10, 0x27, 0x00, 0x0B) + "motdepasse1".toByteArray(), cred.copyOfRange(27, 42))
    }

    @Test fun wscTokenRoundTripsWithoutExposingTheKey() {
        val w = WscToken.decode(WscToken.encode("Café Wi-Fi", "12345678", WifiTagSecurity.WpaWpa2))!!
        assertEquals("Café Wi-Fi", w.ssid)
        assertEquals("WPA/WPA2-Personnel", w.security)
        assertTrue(w.hasKey)
        val open = WscToken.decode(WscToken.encode("Invités", "", WifiTagSecurity.Open))!!
        assertEquals("Ouvert", open.security)
        assertFalse(open.hasKey)
        assertNull(WscToken.decode(bytes(0x10, 0x0E, 0x00, 0x20, 0x01)))
    }

    @Test fun wifiRecordIsDescribedFromTheTag() {
        val msg = NdefBuild.message(NfcWriteRequest.Wifi("Livebox-7A21", "secretpass", WifiTagSecurity.Wpa2))
        assertEquals(WscToken.MIME, String(msg.records[0].type))
        val info = NdefDecode.describe(msg).single()
        assertEquals(NfcRecordKind.WifiHandover, info.kind)
        assertEquals("Réseau « Livebox-7A21 »", info.title)
        assertFalse(info.detail!!.contains("secretpass"))
    }

    @Test fun wifiValidation() {
        assertNotNull(WscToken.validate("", "12345678", WifiTagSecurity.Wpa2))
        assertNotNull(WscToken.validate("x", "short", WifiTagSecurity.Wpa2))
        assertNull(WscToken.validate("x", "", WifiTagSecurity.Open))
        assertNull(WscToken.validate("x", "a".repeat(64).replace('a', 'f'), WifiTagSecurity.Wpa2))
        assertNotNull(WscToken.validate("x".repeat(33), "12345678", WifiTagSecurity.Wpa2))
        assertEquals(WifiTagSecurity.Open, WscToken.securityFor("Ouvert"))
        assertEquals(WifiTagSecurity.Wpa2, WscToken.securityFor("WPA3-Personnel (SAE)"))
    }

    // ---- other templates -------------------------------------------------------------------------

    @Test fun emailSmsAndGeoTemplates() {
        val mail = NdefDecode.describe(NdefBuild.message(NfcWriteRequest.Email("a@b.fr", "Bonjour à tous", ""))).single()
        assertEquals(NfcRecordKind.Email, mail.kind)
        assertEquals("a@b.fr", mail.title)
        assertEquals("Objet : Bonjour à tous", mail.detail)

        val sms = NdefDecode.describe(NdefBuild.message(NfcWriteRequest.Sms("06 12 34 56 78", "J'arrive"))).single()
        assertEquals(NfcRecordKind.Sms, sms.kind)
        assertEquals("0612345678", sms.title)
        assertEquals("J'arrive", sms.detail)

        val geo = NdefDecode.describe(NdefBuild.message(NfcWriteRequest.Geo("48,8584", "2.2945"))).single()
        assertEquals(NfcRecordKind.Geo, geo.kind)
        assertEquals("48.8584, 2.2945", geo.title)

        assertNotNull(NdefBuild.validate(NfcWriteRequest.Email("pas-une-adresse", "", "")))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Geo("91", "0")))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Geo("abc", "0")))
        assertNull(NdefBuild.validate(NfcWriteRequest.Geo("-33.86", "151.21")))
    }

    // ---- chip identification ---------------------------------------------------------------------

    @Test fun getVersionIdentifiesNtagAndUltralight() {
        assertEquals("NTAG213", NfcChip.model(bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03))!!.name)
        assertEquals(129, NfcChip.model(bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x11, 0x03))!!.lastUserPage)
        assertEquals(888, NfcChip.model(bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x13, 0x03))!!.userBytes)
        // NTAG I²C shares the storage byte of NTAG216 but not its layout.
        assertNull(NfcChip.model(bytes(0x00, 0x04, 0x04, 0x05, 0x02, 0x00, 0x13, 0x03)))
        assertEquals("NTAG I²C", NfcChip.versionName(bytes(0x00, 0x04, 0x04, 0x05, 0x02, 0x00, 0x13, 0x03)))
        assertEquals(35, NfcChip.model(bytes(0x00, 0x04, 0x03, 0x01, 0x01, 0x00, 0x0E, 0x03))!!.lastUserPage)
        assertNull(NfcChip.model(bytes(0x00, 0x05, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03)))
    }

    @Test fun manufacturerAndFamilies() {
        assertEquals("NXP Semiconductors", NfcChip.manufacturer(bytes(0x04, 1, 2, 3, 4, 5, 6), false))
        assertNull(NfcChip.manufacturer(bytes(0x08, 1, 2, 3), false))
        // ISO 15693: LSB first, E0 then manufacturer at the end.
        assertEquals("Texas Instruments", NfcChip.manufacturer(bytes(1, 2, 3, 4, 5, 6, 0x07, 0xE0), true))
        assertEquals("MIFARE Classic 1K", NfcChip.familyFromSak(0x08))
    }

    @Test fun flagsCounterfeitCapability() {
        val ntag213 = NfcChip.model(bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03))!!
        val honest = NfcChip.capability(bytes(0xE1, 0x10, 0x12, 0x00))!!
        assertEquals(144, honest.announcedBytes)
        assertTrue(NfcChip.anomalies(bytes(0x04, 1, 2, 3, 4, 5, 6), bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03), ntag213, honest).isEmpty())
        val inflated = NfcChip.capability(bytes(0xE1, 0x10, 0x6D, 0x00))!!
        assertEquals(1, NfcChip.anomalies(bytes(0x04, 1, 2, 3, 4, 5, 6), null, ntag213, inflated).size)
        // Claims NXP in GET_VERSION but the UID manufacturer byte disagrees.
        assertEquals(1, NfcChip.anomalies(bytes(0x1D, 1, 2, 3, 4, 5, 6), bytes(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03), ntag213, honest).size)
    }

    // ---- endurance and antenna -------------------------------------------------------------------

    @Test fun testPatternIsReproducibleAndVaries() {
        assertArrayEquals(testPattern(2, 10), testPattern(2, 10))
        assertFalse(testPattern(1, 10).contentEquals(testPattern(2, 10)))
        assertFalse(testPattern(1, 10).contentEquals(testPattern(1, 11)))
    }

    @Test fun linkScore() {
        assertEquals(100, LinkSample(100, 100, 5.0).score)
        assertEquals(0, LinkSample(50, 0, null).score)
        assertTrue(LinkSample(100, 100, 40.0).score < LinkSample(100, 100, 8.0).score)
        assertTrue(LinkSample(100, 50, 8.0).score in 45..55)
    }
}
