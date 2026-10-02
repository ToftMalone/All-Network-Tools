package com.allnetworktools

import com.allnetworktools.data.sdr.Rs255
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrptRsTest {
    private fun codeword(rnd: Random, dual: Boolean): ByteArray {
        val data = ByteArray(Rs255.K).also { rnd.nextBytes(it) }
        return data + Rs255.encode(data, dual)
    }

    @Test fun dualBasisIsALinearBijectionWithTheCcsdsStructure() {
        for (i in 0 until 256) assertEquals(i, Rs255.fromDual[Rs255.toDual[i]])
        // Linearity over GF(2).
        for (a in listOf(3, 77, 200)) for (b in listOf(5, 129, 254)) assertEquals(Rs255.toDual[a xor b], Rs255.toDual[a] xor Rs255.toDual[b])
    }

    @Test fun cleanCodewordsHaveNoErrors() {
        val rnd = Random(1)
        for (dual in listOf(false, true)) {
            val cw = codeword(rnd, dual)
            assertEquals(0, Rs255.decode(cw.copyOf(), dual))
        }
    }

    @Test fun correctsUpToSixteenSymbolErrors() {
        val rnd = Random(2)
        for (dual in listOf(false, true)) for (errors in listOf(1, 2, 5, 11, 16)) {
            val cw = codeword(rnd, dual)
            val bad = cw.copyOf()
            val pos = (0 until Rs255.N).shuffled(rnd).take(errors)
            for (p in pos) bad[p] = (bad[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
            assertEquals("dual=$dual errors=$errors", errors, Rs255.decode(bad, dual))
            assertArrayEquals(cw, bad)
        }
    }

    @Test fun errorsInTheCheckSymbolsAreCorrectedToo() {
        val rnd = Random(3)
        val cw = codeword(rnd, true)
        val bad = cw.copyOf()
        for (p in intArrayOf(0, 100, 222, 223, 240, 254)) bad[p] = (bad[p].toInt() xor 0x55).toByte()
        assertEquals(6, Rs255.decode(bad, true))
        assertArrayEquals(cw, bad)
    }

    @Test fun refusesWhatItCannotCorrect() {
        val rnd = Random(4)
        var failed = 0
        repeat(20) {
            val cw = codeword(rnd, true)
            val bad = cw.copyOf()
            for (p in (0 until Rs255.N).shuffled(rnd).take(40)) bad[p] = (bad[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
            val r = Rs255.decode(bad.copyOf(), true)
            if (r == -1) failed++
        }
        assertTrue("$failed of 20", failed >= 19) // a 40-error word almost never falls in another codeword's sphere
    }

    @Test fun theWrongBasisDoesNotDecodeOrCorrupt() {
        val rnd = Random(5)
        val cw = codeword(rnd, true)
        assertTrue(Rs255.decode(cw.copyOf(), false) != 0) // read as conventional it is not a codeword
    }
}
