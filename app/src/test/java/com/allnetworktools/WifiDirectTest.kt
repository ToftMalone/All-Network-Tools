package com.allnetworktools

import com.allnetworktools.data.WifiDirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectTest {
    @Test fun wifiDirectDeviceTypes() {
        assertEquals("Imprimante", WifiDirect.category("3-0050F204-1")!!.label)
        assertEquals("Téléviseur", WifiDirect.category("7-0050F204-1")!!.label)
        assertEquals("Smartphone", WifiDirect.category("10-0050F204-5")!!.label)
        assertNull(WifiDirect.category("10-00E04C01-5"))
        assertNull(WifiDirect.category(null))
        assertNull(WifiDirect.category("garbage"))
    }
}
