package com.allnetworktools

import com.allnetworktools.data.sdr.Gps
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A GPS L1 C/A signal simulator for tests: navigation message laid out per IS-GPS-200 (TLM, HOW, parity), satellites
 * on Keplerian orbits, signal travel time with Earth rotation, satellite clocks, and a receiver whose crystal is off by
 * [ppm] (both its local oscillator and its sample clock), producing 8-bit I/Q like a HackRF.
 */
class GpsSim(
    val receiver: DoubleArray,
    /** True GPS time of week at the first sample (whole seconds). */
    val t0: Double,
    val week: Int,
    val ephemerides: List<Gps.Ephemeris>,
    private val ppm: Double = 0.0,
    private val cn0: Double = 45.0,
    private val noise: Double = 20.0,
    seed: Long = 1,
) {
    private val fs = 2_048_000.0
    private val rnd = Random(seed)
    private val amp = sqrt(Math.pow(10.0, cn0 / 10) / fs * 2 * noise * noise)
    private val codes = ephemerides.associate { it.prn to Gps.caBits(it.prn) }
    private val frames = HashMap<Pair<Int, Long>, IntArray>()
    private var n = 0L
    private val cosT = DoubleArray(TABLE) { cos(2 * PI * it / TABLE) }
    private val sinT = DoubleArray(TABLE) { sin(2 * PI * it / TABLE) }

    /** Next [count] samples as interleaved bytes. */
    fun next(count: Int): ByteArray {
        val re = DoubleArray(count) { noise * rnd.nextGaussian() }
        val im = DoubleArray(count) { noise * rnd.nextGaussian() }
        val rate = fs * (1 + ppm * 1e-6)
        for (e in ephemerides) {
            val code = codes[e.prn]!!
            var k = 0
            while (k < count) {
                // Piecewise linear over 1 ms of the receiver's samples.
                val len = minOf(2048, count - k)
                val ta = (n + k) / rate
                val tb = (n + k + len) / rate
                val da = delta(e, t0 + ta)
                val db = delta(e, t0 + tb)
                // Phase and chip advance linearly within the millisecond.
                val tsvA = ta + da
                val tsvB = tb + db
                val dTsv = (tsvB - tsvA) / len
                val phA = Gps.L1_HZ * da - ppm * 1e-6 * Gps.L1_HZ * ta
                val phB = Gps.L1_HZ * db - ppm * 1e-6 * Gps.L1_HZ * tb
                val dPh = (phB - phA) / len
                var ph = phA - floor(phA)
                val chipA = tsvA * Gps.CHIP_RATE
                var chip = chipA - floor(chipA / Gps.CODE_LENGTH) * Gps.CODE_LENGTH
                val dChip = dTsv * Gps.CHIP_RATE
                var tsv = tsvA
                val base = floor(t0 / 0.02).toLong()
                for (j in 0 until len) {
                    val bit = bit(e.prn, base + floor(tsv / 0.02).toLong())
                    val sign = if ((code[chip.toInt()] xor bit) == 0) amp else -amp
                    val ti = ((ph - floor(ph)) * TABLE).toInt() and (TABLE - 1)
                    re[k + j] += sign * cosT[ti]
                    im[k + j] += sign * sinT[ti]
                    ph += dPh
                    chip += dChip
                    if (chip >= Gps.CODE_LENGTH) chip -= Gps.CODE_LENGTH
                    tsv += dTsv
                }
                k += len
            }
        }
        n += count
        return ByteArray(2 * count) { i -> (if (i % 2 == 0) re[i / 2] else im[i / 2]).roundToInt().coerceIn(-127, 127).toByte() }
    }

    /** Satellite clock time minus true receive time: −flight time + satellite clock offset. */
    private fun delta(e: Gps.Ephemeris, t: Double): Double {
        var tau = 0.07
        repeat(4) {
            val p = Gps.satPosition(e, t - tau)
            val th = Gps.OMEGA_E * tau
            val x = cos(th) * p[0] + sin(th) * p[1]
            val y = -sin(th) * p[0] + cos(th) * p[1]
            tau = sqrt((x - receiver[0]) * (x - receiver[0]) + (y - receiver[1]) * (y - receiver[1]) + (p[2] - receiver[2]) * (p[2] - receiver[2])) / Gps.C
        }
        // The troposphere, with the same simple model the receiver removes.
        val el = Math.toRadians(Gps.elevationAzimuth(receiver, Gps.satPosition(e, t - tau))[0]).coerceAtLeast(0.05)
        val tropo = 2.47 / (sin(el) + 0.0121) / Gps.C
        return -tau - tropo + Gps.satClock(e, t - tau)
    }

    /** Navigation bit number [b] (counted from the start of the week, 20 ms each). */
    private fun bit(prn: Int, b: Long): Int {
        val sf = b / 300
        val bits = frames.getOrPut(prn to sf) { subframeBits(ephemerides.first { it.prn == prn }, sf) }
        return bits[(b - sf * 300).toInt()]
    }

    /** The 300 transmitted bits of subframe number [k] of the week (it starts at TOW 6k). */
    fun subframeBits(e: Gps.Ephemeris, k: Long): IntArray {
        // Fields not simulated carry random bits, as real messages carry other data there.
        val fill = Random(k * 131 + e.prn)
        val d = IntArray(240) { fill.nextInt(2) }
        val id = (k % 5 + 1).toInt()
        put(d, 1, 8, 0x8B)
        put(d, 31, 17, k + 1)
        put(d, 50, 3, id.toLong())
        val c = e.clock
        val o = e.orbit
        when (id) {
            1 -> {
                put(d, 61, 10, (week % 1024).toLong()); put(d, 77, 6, c.health.toLong())
                put(d, 83, 2, (c.iodc shr 8).toLong()); put(d, 211, 8, (c.iodc and 0xFF).toLong())
                put(d, 197, 8, q(c.tgd, -31)); put(d, 219, 16, (c.toc / 16).toLong())
                put(d, 241, 8, q(c.af2, -55)); put(d, 249, 16, q(c.af1, -43)); put(d, 271, 22, q(c.af0, -31))
            }
            2 -> {
                put(d, 61, 8, o.iode.toLong()); put(d, 69, 16, q(o.crs, -5)); put(d, 91, 16, q(o.dn / PI, -43))
                put2(d, 107, 8, 121, 24, q(o.m0 / PI, -31)); put(d, 151, 16, q(o.cuc, -29))
                put2(d, 167, 8, 181, 24, q(o.e, -33)); put(d, 211, 16, q(o.cus, -29))
                put2(d, 227, 8, 241, 24, q(o.sqrtA, -19)); put(d, 271, 16, (o.toe / 16).toLong())
            }
            3 -> {
                put(d, 61, 16, q(o.cic, -29)); put2(d, 77, 8, 91, 24, q(o.omega0 / PI, -31)); put(d, 121, 16, q(o.cis, -29))
                put2(d, 137, 8, 151, 24, q(o.i0 / PI, -31)); put(d, 181, 16, q(o.crc, -5))
                put2(d, 197, 8, 211, 24, q(o.w / PI, -31)); put(d, 241, 24, q(o.omegaDot / PI, -43))
                put(d, 271, 8, o.iode.toLong()); put(d, 279, 14, q(o.idot / PI, -43))
            }
        }
        return encode(d)
    }

    companion object {
        private const val TABLE = 8192

        private fun q(v: Double, e: Int): Long = Math.round(Math.scalb(v, -e))

        private fun idx(icd: Int) = (icd - 1) / 30 * 24 + (icd - 1) % 30

        fun put(d: IntArray, icd: Int, n: Int, v: Long) {
            for (i in 0 until n) d[idx(icd) + i] = ((v shr (n - 1 - i)) and 1).toInt()
        }

        fun put2(d: IntArray, a: Int, n1: Int, b: Int, n2: Int, v: Long) {
            put(d, a, n1, v shr n2)
            put(d, b, n2, v and ((1L shl n2) - 1))
        }

        /** 240 data bits to 300 transmitted bits: parity per word, with words 2 and 10 ending in D29 = D30 = 0. */
        fun encode(d: IntArray, prev29: Int = 0, prev30: Int = 0): IntArray {
            val out = IntArray(300)
            var p29 = prev29
            var p30 = prev30
            for (w in 0 until 10) {
                val data = d.copyOfRange(24 * w, 24 * w + 24)
                val choices = if (w == 1 || w == 9) (0 until 4).toList() else listOf(-1)
                for (ch in choices) {
                    if (ch >= 0) { data[22] = ch shr 1; data[23] = ch and 1 }
                    val word = word(data, p29, p30)
                    if (ch < 0 || (word[28] == 0 && word[29] == 0)) {
                        word.copyInto(out, 30 * w)
                        break
                    }
                }
                p29 = out[30 * w + 28]; p30 = out[30 * w + 29]
            }
            return out
        }

        private fun word(data: IntArray, d29: Int, d30: Int): IntArray {
            val d = IntArray(25)
            for (i in 1..24) d[i] = data[i - 1]
            fun x(vararg idx: Int): Int { var v = 0; for (i in idx) v = v xor d[i]; return v }
            val p = intArrayOf(
                d29 xor x(1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23),
                d30 xor x(2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24),
                d29 xor x(1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22),
                d30 xor x(2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23),
                d30 xor x(1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24),
                d29 xor x(3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24),
            )
            return IntArray(30) { i -> if (i < 24) d[i + 1] xor d30 else p[i - 24] }
        }

        /** A plausible orbit for each PRN, already quantized as the message carries it. */
        fun constellation(toe: Double): List<Gps.Ephemeris> = (1..32).map { prn ->
            val plane = (prn - 1) % 6
            val slot = (prn - 1) / 6
            val o = Gps.Orbit(
                iode = 40 + prn, crs = 12.5 + prn, dn = 4.5e-9, m0 = -PI + 2 * PI * ((slot * 0.19 + plane * 0.07) % 1.0),
                cuc = 1.2e-6, e = 0.004 + 0.0007 * prn, cus = 8.5e-6, sqrtA = 5153.6 + prn * 0.05, toe = toe,
                cic = -5e-8, omega0 = -PI + plane * PI / 3 + 0.1, cis = 7e-8, i0 = 0.96 + 0.001 * plane, crc = 210.0 + prn,
                w = 0.5 + 0.1 * plane, omegaDot = -8.1e-9, idot = 2e-10,
            )
            val c = Gps.Clock(week = 0, health = 0, iodc = 40 + prn, tgd = -1.1e-8, toc = toe, af0 = 1.5e-5 * (prn % 5 - 2), af1 = 2e-12, af2 = 0.0)
            quantize(Gps.Ephemeris(prn, c, o))
        }

        /** Round trip through the message's resolution so the simulator and the receiver use the same numbers. */
        fun quantize(e: Gps.Ephemeris): Gps.Ephemeris {
            fun r(v: Double, ex: Int) = Math.scalb(Math.round(Math.scalb(v, -ex)).toDouble(), ex)
            val o = e.orbit
            val c = e.clock
            return Gps.Ephemeris(
                e.prn,
                Gps.Clock(c.week, c.health, c.iodc, r(c.tgd, -31), c.toc, r(c.af0, -31), r(c.af1, -43), r(c.af2, -55)),
                Gps.Orbit(
                    o.iode, r(o.crs, -5), r(o.dn / PI, -43) * PI, r(o.m0 / PI, -31) * PI, r(o.cuc, -29), r(o.e, -33), r(o.cus, -29),
                    r(o.sqrtA, -19), o.toe, r(o.cic, -29), r(o.omega0 / PI, -31) * PI, r(o.cis, -29), r(o.i0 / PI, -31) * PI,
                    r(o.crc, -5), r(o.w / PI, -31) * PI, r(o.omegaDot / PI, -43) * PI, r(o.idot / PI, -43) * PI,
                ),
            )
        }
    }
}
