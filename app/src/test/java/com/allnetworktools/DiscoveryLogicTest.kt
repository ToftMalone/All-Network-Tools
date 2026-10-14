package com.allnetworktools

import com.allnetworktools.data.net.Whois
import com.allnetworktools.data.net.WhoisHop
import com.allnetworktools.data.net.WhoisResult
import com.allnetworktools.ui.pages.wifi.shortDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLogicTest {
    @Test fun parsesDomainWhois() {
        val verisign = """   Domain Name: GOOGLE.COM
   Registrar WHOIS Server: whois.markmonitor.com
   Updated Date: 2019-09-09T15:39:04Z
   Creation Date: 1997-09-15T04:00:00Z
   Registry Expiry Date: 2028-09-14T04:00:00Z
   Registrar: MarkMonitor Inc.
   Domain Status: clientDeleteProhibited https://icann.org/epp#clientDeleteProhibited
   Name Server: NS1.GOOGLE.COM
   Name Server: NS2.GOOGLE.COM
   DNSSEC: unsigned"""
        val r = WhoisResult("google.com", false, listOf(WhoisHop("whois.iana.org", "refer: whois.verisign-grs.com"), WhoisHop("whois.verisign-grs.com", verisign)))
        assertEquals("MarkMonitor Inc.", r.registrar)
        assertEquals("14/09/2028", shortDate(r.expires!!))
        assertEquals(listOf("ns1.google.com", "ns2.google.com"), r.nameServers)
        assertEquals(listOf("clientDeleteProhibited"), r.status)
    }

    @Test fun parsesAfnicAndRipe() {
        val afnic = "domain:      wikipedia.fr\nstatus:      ACTIVE\nregistrar:   MarkMonitor Inc.\nExpiry Date: 2026-01-01T00:00:00Z\ncreated:     2005-06-16T12:00:00Z\nnserver:     ns0.wikimedia.org\nnserver:     ns1.wikimedia.org"
        val r = WhoisResult("wikipedia.fr", false, listOf(WhoisHop("whois.nic.fr", afnic)))
        assertEquals("MarkMonitor Inc.", r.registrar)
        assertEquals("16/06/2005", shortDate(r.created!!))
        assertEquals(2, r.nameServers.size)

        val ripe = "inetnum:        193.0.0.0 - 193.0.7.255\nnetname:        RIPE-NCC\ndescr:          RIPE Network Coordination Centre\ncountry:        NL\norigin:         AS3333"
        val ip = WhoisResult("193.0.6.139", true, listOf(WhoisHop("whois.ripe.net", ripe)))
        assertEquals("193.0.0.0 - 193.0.7.255", ip.network)
        assertEquals("RIPE-NCC", ip.netName)
        assertEquals("NL", ip.country)
        assertEquals("AS3333", ip.asn)
    }

    @Test fun detectsIpQueries() {
        assertTrue(Whois.isIp("8.8.8.8"))
        assertTrue(Whois.isIp("2001:db8::1"))
        assertFalse(Whois.isIp("google.com"))
        assertFalse(Whois.isIp("cafe"))
    }
}
