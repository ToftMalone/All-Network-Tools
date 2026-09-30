package com.allnetworktools

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.allnetworktools.data.NdefBuild
import com.allnetworktools.data.NdefDecode
import com.allnetworktools.data.NfcRecordKind
import com.allnetworktools.data.NfcWriteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NfcParseTest {
    // ---- low-level decoding -------------------------------------------------------------------

    @Test fun decodesUriAbbreviations() {
        assertEquals("https://example.com/x", NdefDecode.decodeUri(NdefRecord.createUri("https://example.com/x").payload))
        assertEquals("tel:0102030405", NdefDecode.decodeUri(NdefRecord.createUri("tel:0102030405").payload))
        // No matching abbreviation: code 0x00, full URI kept verbatim.
        assertEquals("custom-scheme:abc", NdefDecode.decodeUri(NdefRecord.createUri("custom-scheme:abc").payload))
    }

    @Test fun decodesTextRecords() {
        val (lang, text) = NdefDecode.decodeText(NdefRecord.createTextRecord("fr", "Bonjour tout le monde").payload)!!
        assertEquals("fr", lang)
        assertEquals("Bonjour tout le monde", text)
        val (lang2, text2) = NdefDecode.decodeText(NdefRecord.createTextRecord("en", "héllo").payload)!!
        assertEquals("en", lang2)
        assertEquals("héllo", text2)
    }

    @Test fun decodesVCardFields() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Marie Curie\r\nTEL:+33612345678\r\nEMAIL:marie@example.com\r\nEND:VCARD\r\n"
        assertEquals("Marie Curie · Tél. +33612345678 · marie@example.com", NdefDecode.decodeVCard(vcard))
        assertEquals("Carte de contact", NdefDecode.decodeVCard("BEGIN:VCARD\r\nEND:VCARD\r\n"))
    }

    // ---- whole-record description --------------------------------------------------------------

    @Test fun describesLinkAndPhoneRecords() {
        val link = NdefDecode.describe(NdefMessage(NdefRecord.createUri("https://allnetwork.tools"))).single()
        assertEquals(NfcRecordKind.Link, link.kind)
        assertEquals("https://allnetwork.tools", link.title)

        val phone = NdefDecode.describe(NdefMessage(NdefRecord.createUri("tel:+33612345678"))).single()
        assertEquals(NfcRecordKind.Phone, phone.kind)
        assertEquals("+33612345678", phone.title)
    }

    @Test fun describesTextRecord() {
        val r = NdefDecode.describe(NdefMessage(NdefRecord.createTextRecord("fr", "Étiquette d'étagère 4B"))).single()
        assertEquals(NfcRecordKind.Text, r.kind)
        assertEquals("Étiquette d'étagère 4B", r.title)
        assertEquals("Langue : fr", r.detail)
    }

    @Test fun describesVCardMimeRecord() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada Lovelace\r\nTEL:0102030405\r\nEND:VCARD\r\n"
        val r = NdefDecode.describe(NdefMessage(NdefRecord.createMime("text/vcard", vcard.toByteArray()))).single()
        assertEquals(NfcRecordKind.Contact, r.kind)
        assertTrue(r.title.contains("Ada Lovelace"))
    }

    @Test fun describesWifiHandoverAsLabelOnly() {
        val r = NdefDecode.describe(NdefMessage(NdefRecord.createMime("application/vnd.wfa.wsc", byteArrayOf(1, 2, 3)))).single()
        assertEquals(NfcRecordKind.WifiHandover, r.kind)
    }

    @Test fun describesAndroidApplicationRecord() {
        val r = NdefDecode.describe(NdefMessage(NdefRecord.createApplicationRecord("com.allnetworktools"))).single()
        assertEquals(NfcRecordKind.App, r.kind)
        assertEquals("com.allnetworktools", r.title)
    }

    @Test fun describesSmartPosterWithNestedTitleAndUri() {
        val inner = NdefMessage(NdefRecord.createTextRecord("fr", "Boutique en ligne"), NdefRecord.createUri("https://boutique.example"))
        val sp = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_SMART_POSTER, ByteArray(0), inner.toByteArray())
        val r = NdefDecode.describe(NdefMessage(sp)).single()
        assertEquals(NfcRecordKind.SmartPoster, r.kind)
        assertEquals("Boutique en ligne", r.title)
        assertEquals("https://boutique.example", r.detail)
    }

    @Test fun unknownRecordTypeIsReportedNotCrashed() {
        val weird = NdefRecord(NdefRecord.TNF_WELL_KNOWN, byteArrayOf('Z'.code.toByte()), ByteArray(0), byteArrayOf(9, 9))
        val r = NdefDecode.describe(NdefMessage(weird)).single()
        assertEquals(NfcRecordKind.Unknown, r.kind)
    }

    // ---- writing ----------------------------------------------------------------------------------

    @Test fun buildsAndRoundTripsEveryPreset() {
        val link = NdefBuild.message(NfcWriteRequest.Link("https://allnetwork.tools"))
        assertEquals("https://allnetwork.tools", NdefDecode.describe(link).single().title)

        val text = NdefBuild.message(NfcWriteRequest.Text("Salle de réunion 2", "fr"))
        assertEquals("Salle de réunion 2", NdefDecode.describe(text).single().title)

        val phone = NdefBuild.message(NfcWriteRequest.Phone("06 12 34 56 78"))
        assertEquals(NfcRecordKind.Phone, NdefDecode.describe(phone).single().kind)
        assertEquals("0612345678", NdefDecode.describe(phone).single().title)

        val contact = NdefBuild.message(NfcWriteRequest.Contact("Grace Hopper", "0102030405", "grace@example.com"))
        val contactInfo = NdefDecode.describe(contact).single()
        assertEquals(NfcRecordKind.Contact, contactInfo.kind)
        assertTrue(contactInfo.title.contains("Grace Hopper"))

        val app = NdefBuild.message(NfcWriteRequest.App("com.example.app"))
        assertEquals("com.example.app", NdefDecode.describe(app).single().title)
    }

    @Test fun validatesFormsBeforeWriting() {
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Link("")))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Link("not a url")))
        assertNull(NdefBuild.validate(NfcWriteRequest.Link("https://ok.example")))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Text("")))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Phone("12")))
        assertNull(NdefBuild.validate(NfcWriteRequest.Phone("0102030405")))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.Contact("", null, null)))
        assertNull(NdefBuild.validate(NfcWriteRequest.Contact("A", null, null)))
        assertNotNull(NdefBuild.validate(NfcWriteRequest.App("not-a-package")))
        assertNull(NdefBuild.validate(NfcWriteRequest.App("com.example.app")))
    }
}
