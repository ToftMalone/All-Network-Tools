package com.allnetworktools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.sdr.MeshPreset
import com.allnetworktools.data.sdr.MeshRegion
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.pages.sdr.MeshChannel
import com.allnetworktools.ui.pages.sdr.MeshStore
import com.allnetworktools.ui.pages.sdr.MeshtasticController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class MeshChannelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun controller(store: MeshStore? = MeshStore(context)) = MeshtasticController(SdrRepository(context), CoroutineScope(Dispatchers.Unconfined), store)

    /** A text message encrypted for [channel] the way a node would send it. */
    private fun frame(channel: MeshChannel, text: String, from: Long = 0x1234abcdL, id: Long = 77, hash: Int? = null): ByteArray {
        val psk = java.util.Base64.getDecoder().decode(channel.keyBase64)
        val key = Meshtastic.expandKey(psk)
        val h = ByteArray(16)
        fun put(o: Int, v: Long) { for (k in 0 until 4) h[o + k] = (v shr (8 * k)).toByte() }
        put(0, Meshtastic.BROADCAST); put(4, from); put(8, id)
        h[12] = ((3 shl 5) or 3).toByte()
        h[13] = (hash ?: Meshtastic.channelHash(channel.name, key)).toByte()
        val body = Pb().int(1, 1).str(2, text).build()
        return h + (if (key == null) body else Meshtastic.crypt(key, from, id, body))
    }

    @Test fun eachPacketIsReadWithTheKeyOfItsOwnChannel() {
        val c = controller(null)
        val paris = MeshChannel("Paris", "1PG7OiApB1nwvP+rz05pAQ==")
        val secret = MeshChannel("Equipe", "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=") // 32 bytes
        c.addChannel(); c.setChannel(1, paris)
        c.addChannel(); c.setChannel(2, secret)
        val (s, err) = c.settings()
        assertNull(err)
        val resolved = s!!.channels
        assertEquals(3, resolved.size)
        for ((ch, text) in listOf(c.channels[0] to "sur LongFast", paris to "sur Paris", secret to "en secret")) {
            val (packet, used) = c.decodePacket(frame(ch, text), resolved, 0, 5.0)!!
            assertNotNull("$text: channel", used)
            assertEquals(ch.name.ifBlank { "LongFast" }, used!!.name)
            assertEquals(text, (packet.data!!.content as Meshtastic.Content.Text).text)
        }
        // A channel we do not have: header only, flagged as another channel.
        val (other, none) = c.decodePacket(frame(MeshChannel("Autre", "AQ=="), "inconnu"), resolved, 0, 5.0)!!
        assertNull(none)
        assertNull(other.data)
    }

    @Test fun aHashCollisionFallsBackToTheChannelWhoseKeyWorks() {
        val c = controller(null)
        val a = MeshChannel("LongFast", "AQ==")
        val b = MeshChannel("LongFast", "AQIDBAUGBwgJCgsMDQ4PEA==") // same name, other 16-byte key: the hash is forced to collide below
        c.setChannel(0, a); c.addChannel(); c.setChannel(1, b)
        val resolved = c.settings().first!!.channels
        val forced = resolved.map { com.allnetworktools.ui.pages.sdr.ResolvedChannel(it.name, it.key, 0x2A) }
        val f = frame(b, "clé du second", hash = 0x2A)
        val (packet, used) = c.decodePacket(f, forced, 0, 3.0)!!
        assertEquals("clé du second", (packet.data!!.content as Meshtastic.Content.Text).text)
        assertTrue(used === forced[1])
    }

    @Test fun configurationSurvivesARestart() {
        val store = MeshStore(context)
        val a = controller(store)
        a.selectRegion(MeshRegion.Us)
        a.selectPreset(MeshPreset.MediumFast)
        a.addChannel(); a.setChannel(1, MeshChannel("Paris", "1PG7OiApB1nwvP+rz05pAQ=="))
        a.longName = "Mon HackRF"; a.shortName = "HKRF"; a.lnaGain = 24; a.vgaGain = 40; a.amp = true
        a.persist()
        val b = controller(MeshStore(context))
        assertEquals(MeshRegion.Us, b.region)
        assertEquals(MeshPreset.MediumFast, b.preset)
        assertEquals(a.frequencyMhz, b.frequencyMhz)
        assertEquals(listOf(MeshChannel("MediumFast", "AQ=="), MeshChannel("Paris", "1PG7OiApB1nwvP+rz05pAQ==")), b.channels.toList())
        assertEquals("Mon HackRF", b.longName)
        assertEquals("HKRF", b.shortName)
        assertEquals(a.nodeNum, b.nodeNum)
        assertEquals(24, b.lnaGain); assertEquals(40, b.vgaGain); assertTrue(b.amp)
    }

    @Test fun thePrimaryChannelFollowsThePresetUnlessItWasRenamed() {
        val c = controller(null)
        c.selectPreset(MeshPreset.LongSlow)
        assertEquals("LongSlow", c.channels[0].name)
        c.setChannel(0, MeshChannel("Maison", "AQ=="))
        c.selectPreset(MeshPreset.ShortFast)
        assertEquals("Maison", c.channels[0].name)
    }

    @Test fun invalidKeysAreRefusedWithTheChannelNumber() {
        val c = controller(null)
        c.addChannel(); c.setChannel(1, MeshChannel("X", "pas du base64 !!"))
        val (s, err) = c.settings()
        assertNull(s)
        assertTrue(err!!, err.startsWith("Canal 2"))
        c.setChannel(1, MeshChannel("X", java.util.Base64.getEncoder().encodeToString(ByteArray(5)))) // 5 bytes: not a valid length
        assertTrue(c.settings().second!!.contains("1, 16 ou 32"))
    }

    @Test fun theNodeIdentityIsStableAndInRange() {
        val c = controller(null)
        assertTrue(c.nodeNum in 0x100L..0xFFFFFFFEL)
        assertEquals(Meshtastic.nodeId(c.nodeNum).takeLast(4), c.shortName)
        val before = c.nodeNum
        c.regenerateNodeId()
        assertTrue(c.nodeNum != before)
        assertTrue(c.nodeNum in 0x100L..0xFFFFFFFEL)
        assertEquals(Meshtastic.nodeId(c.nodeNum).takeLast(4), c.shortName)
    }

    @Test fun tooManyChannelsAreNotAdded() {
        val c = controller(null)
        repeat(20) { c.addChannel() }
        assertEquals(MeshtasticController.MAX_CHANNELS, c.channels.size)
        c.resetChannels()
        assertEquals(1, c.channels.size)
    }
}
