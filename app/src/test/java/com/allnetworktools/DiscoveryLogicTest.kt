package com.allnetworktools

import com.allnetworktools.data.net.Upnp
import com.allnetworktools.data.net.UpnpDevice
import com.allnetworktools.data.net.Whois
import com.allnetworktools.data.net.WhoisHop
import com.allnetworktools.data.net.WhoisResult
import com.allnetworktools.ui.pages.wifi.shortDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLogicTest {
    @Test fun parsesUpnpDescription() {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://192.168.1.254:5678/</URLBase>
            <device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:2</deviceType><friendlyName>Freebox Server</friendlyName>
            <manufacturer>Free &amp; Co</manufacturer><modelName>Freebox</modelName><modelNumber>v7</modelNumber>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType></service></serviceList>
            <deviceList><device><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:2</serviceType></service></serviceList></device></deviceList>
            <presentationURL>/admin</presentationURL></device></root>"""
        val d = Upnp.describe(UpnpDevice("192.168.1.254", "http://192.168.1.254:5678/desc.xml", "Linux UPnP/1.0"), xml)
        assertEquals("Freebox Server", d.name)
        assertEquals("Free & Co", d.manufacturer)
        assertEquals("Freebox v7", d.model)
        assertEquals("InternetGatewayDevice", d.typeShort)
        assertEquals(listOf("Layer3Forwarding", "WANIPConnection"), d.services)
        assertEquals("http://192.168.1.254:5678/admin", d.presentationUrl)
    }

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
