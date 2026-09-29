package com.allnetworktools.data.orbit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * SGP4/SDP4 orbit propagator for NORAD two-line element sets.
 *
 * Kotlin port of the reference implementation by David Vallado ("Revisiting Spacetrack Report #3",
 * AIAA 2006-6753), following the Python port by Brandon Rhodes (sgp4 2.27, MIT licence), in
 * "improved" operation mode with the WGS-72 constants TLEs are generated with. GNSS satellites all
 * use the deep-space branch (period over 225 min), including the 24 h resonance for GEO / IGSO.
 */

private const val TWO_PI = 2.0 * PI
private const val X2O3 = 2.0 / 3.0

/** Python's modulo: the result takes the sign of the divisor. */
private fun pmod(x: Double, m: Double = TWO_PI): Double {
    val r = x % m
    return if (r < 0) r + m else r
}

/** Parsed two-line element set. */
data class Tle(val name: String, val line1: String, val line2: String) {
    val catalog: Int get() = line1.substring(2, 7).trim().toIntOrNull() ?: 0

    companion object {
        /** Parses a 3-line file (name, line 1, line 2 per satellite). Malformed entries are skipped. */
        fun parseAll(text: String): List<Tle> {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = mutableListOf<Tle>()
            var i = 0
            while (i + 2 <= lines.lastIndex) {
                val n = lines[i]
                val l1 = lines[i + 1]
                val l2 = lines[i + 2]
                if (l1.startsWith("1 ") && l2.startsWith("2 ") && l1.length >= 64 && l2.length >= 63) {
                    out += Tle(n.trim(), l1, l2)
                    i += 3
                } else {
                    i += 1
                }
            }
            return out
        }
    }
}

class Sgp4(tle: Tle) {
    // WGS-72
    private val radiusearthkm = 6378.135
    private val mu = 398600.8
    private val xke = 60.0 / sqrt(radiusearthkm * radiusearthkm * radiusearthkm / mu)
    private val tumin = 1.0 / xke
    private val j2 = 0.001082616
    private val j3 = -0.00000253881
    private val j4 = -0.00000165597
    private val j3oj2 = j3 / j2

    /** Julian date of the element set epoch. */
    val jdEpoch: Double
    var error = 0
        private set

    private var bstar = 0.0
    private var ecco = 0.0
    private var argpo = 0.0
    private var inclo = 0.0
    private var mo = 0.0
    private var noKozai = 0.0
    private var nodeo = 0.0

    private var isimp = 0
    private var method = 'n'
    private var aycof = 0.0; private var con41 = 0.0; private var cc1 = 0.0; private var cc4 = 0.0
    private var cc5 = 0.0; private var d2 = 0.0; private var d3 = 0.0; private var d4 = 0.0
    private var delmo = 0.0; private var eta = 0.0; private var argpdot = 0.0; private var omgcof = 0.0
    private var sinmao = 0.0; private var t = 0.0; private var t2cof = 0.0; private var t3cof = 0.0
    private var t4cof = 0.0; private var t5cof = 0.0; private var x1mth2 = 0.0; private var x7thm1 = 0.0
    private var mdot = 0.0; private var nodedot = 0.0; private var xlcof = 0.0; private var xmcof = 0.0
    private var nodecf = 0.0
    private var irez = 0; private var d2201 = 0.0; private var d2211 = 0.0; private var d3210 = 0.0
    private var d3222 = 0.0; private var d4410 = 0.0; private var d4422 = 0.0; private var d5220 = 0.0
    private var d5232 = 0.0; private var d5421 = 0.0; private var d5433 = 0.0; private var dedt = 0.0
    private var del1 = 0.0; private var del2 = 0.0; private var del3 = 0.0; private var didt = 0.0
    private var dmdt = 0.0; private var dnodt = 0.0; private var domdt = 0.0; private var e3 = 0.0
    private var ee2 = 0.0; private var peo = 0.0; private var pgho = 0.0; private var pho = 0.0
    private var pinco = 0.0; private var plo = 0.0; private var se2 = 0.0; private var se3 = 0.0
    private var sgh2 = 0.0; private var sgh3 = 0.0; private var sgh4 = 0.0; private var sh2 = 0.0
    private var sh3 = 0.0; private var si2 = 0.0; private var si3 = 0.0; private var sl2 = 0.0
    private var sl3 = 0.0; private var sl4 = 0.0; private var gsto = 0.0; private var xfact = 0.0
    private var xgh2 = 0.0; private var xgh3 = 0.0; private var xgh4 = 0.0; private var xh2 = 0.0
    private var xh3 = 0.0; private var xi2 = 0.0; private var xi3 = 0.0; private var xl2 = 0.0
    private var xl3 = 0.0; private var xl4 = 0.0; private var xlamo = 0.0; private var zmol = 0.0
    private var zmos = 0.0; private var atime = 0.0; private var xli = 0.0; private var xni = 0.0
    private var noUnkozai = 0.0

    init {
        val l1 = tle.line1
        val l2 = tle.line2
        val xpdotp = 1440.0 / TWO_PI
        val epochyr = l1.substring(18, 20).trim().toInt()
        val epochdays = l1.substring(20, 32).trim().toDouble()
        bstar = expField(l1.substring(53, 61))
        inclo = l2.substring(8, 16).trim().toDouble() * PI / 180
        nodeo = l2.substring(17, 25).trim().toDouble() * PI / 180
        ecco = ("0." + l2.substring(26, 33).trim().replace(' ', '0')).toDouble()
        argpo = l2.substring(34, 42).trim().toDouble() * PI / 180
        mo = l2.substring(43, 51).trim().toDouble() * PI / 180
        noKozai = l2.substring(52, 63).trim().toDouble() / xpdotp
        val year = if (epochyr < 57) epochyr + 2000 else epochyr + 1900
        jdEpoch = jday(year, 1, 1) - 1.0 + epochdays
        sgp4init(jday(year, 1, 1) - 1.0 - 2433281.5 + epochdays)
    }

    /** "12345-3" style field: mantissa with an implied leading decimal point and a power of ten. */
    private fun expField(s: String): Double {
        val f = s.trim()
        if (f.isEmpty()) return 0.0
        val sign = if (f.startsWith("-")) -1.0 else 1.0
        val body = f.trimStart('-', '+')
        val expSign = body.indexOfLast { it == '-' || it == '+' }
        if (expSign <= 0) return sign * ("0.$body").toDouble()
        val mant = ("0." + body.substring(0, expSign).trim()).toDouble()
        val exp = body.substring(expSign).toInt()
        return sign * mant * 10.0.pow(exp)
    }

    /** Position (km, TEME frame) [minutesSinceEpoch] after the element set epoch; null if the model fails. */
    fun position(minutesSinceEpoch: Double): DoubleArray? = propagate(minutesSinceEpoch)?.first

    fun propagate(tsince: Double): Pair<DoubleArray, DoubleArray>? {
        var mrt: Double
        val temp4 = 1.5e-12
        val vkmpersec = radiusearthkm * xke / 60.0
        t = tsince
        error = 0
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (isimp != 1) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = noUnkozai
        var em = ecco
        var inclm = inclo
        if (method == 'd') {
            val r = dspace(t, em, argpm, inclm, mm, nodem, nm)
            em = r[0]; argpm = r[1]; inclm = r[2]; mm = r[3]; nodem = r[4]; nm = r[5]
        }
        if (nm <= 0.0) { error = 2; return null }
        val am = (xke / nm).pow(X2O3) * tempa * tempa
        nm = xke / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) { error = 1; return null }
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem
        nodem = if (nodem >= 0.0) nodem % TWO_PI else -(-nodem % TWO_PI)
        argpm = pmod(argpm)
        xlm = pmod(xlm)
        mm = pmod(xlm - argpm - nodem)
        val sinim = sin(inclm)
        val cosim = cos(inclm)
        var ep = em
        var xincp = inclm
        var argpp = argpm
        var nodep = nodem
        var mp = mm
        var sinip = sinim
        var cosip = cosim
        if (method == 'd') {
            val r = dpper(false, ep, xincp, nodep, argpp, mp)
            ep = r[0]; xincp = r[1]; nodep = r[2]; argpp = r[3]; mp = r[4]
            if (xincp < 0.0) {
                xincp = -xincp
                nodep += PI
                argpp -= PI
            }
            if (ep < 0.0 || ep > 1.0) { error = 3; return null }
            sinip = sin(xincp)
            cosip = cos(xincp)
            aycof = -0.5 * j3oj2 * sinip
            xlcof = if (abs(cosip + 1.0) > 1.5e-12) -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip)
            else -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / temp4
        }
        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl
        val u = pmod(xl - nodep)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) { error = 4; return null }
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * j2 * temp
        val temp2 = temp1 * temp
        if (method == 'd') {
            val cosisq = cosip * cosip
            con41 = 3.0 * cosisq - 1.0
            x1mth2 = 1.0 - cosisq
            x7thm1 = 7.0 * cosisq - 1.0
        }
        mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu
        val mr = mrt * radiusearthkm
        val r = doubleArrayOf(mr * ux, mr * uy, mr * uz)
        val v = doubleArrayOf((mvt * ux + rvdot * vx) * vkmpersec, (mvt * uy + rvdot * vy) * vkmpersec, (mvt * uz + rvdot * vz) * vkmpersec)
        if (mrt < 1.0) { error = 6; return null }
        return r to v
    }

    // ---- initialisation ---------------------------------------------------------------------------

    private fun sgp4init(epoch: Double) {
        val temp4 = 1.5e-12
        val ss = 78.0 / radiusearthkm + 1.0
        val qzms2ttemp = (120.0 - 78.0) / radiusearthkm
        val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp
        t = 0.0

        // initl
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (xke / noKozai).pow(X2O3)
        val d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = noKozai / (1.0 + del)
        val ao = (xke / noUnkozai).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)
        gsto = gstime(epoch + 2433281.5)

        if (omeosq >= 0.0 || noUnkozai >= 0.0) {
            isimp = 0
            if (rp < 220.0 / radiusearthkm + 1.0) isimp = 1
            var sfour = ss
            var qzms24 = qzms2t
            val perige = (rp - 1.0) * radiusearthkm
            if (perige < 156.0) {
                sfour = perige - 78.0
                if (perige < 98.0) sfour = 20.0
                val qzms24temp = (120.0 - sfour) / radiusearthkm
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
                sfour = sfour / radiusearthkm + 1.0
            }
            val pinvsq = 1.0 / posq
            val tsi = 1.0 / (ao - sfour)
            eta = ao * ecco * tsi
            val etasq = eta * eta
            val eeta = ecco * eta
            val psisq = abs(1.0 - etasq)
            val coef = qzms24 * tsi.pow(4.0)
            val coef1 = coef / psisq.pow(3.5)
            val cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
            cc1 = bstar * cc2
            var cc3 = 0.0
            if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * j3oj2 * noUnkozai * sinio / ecco
            x1mth2 = 1.0 - cosio2
            cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq *
                (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) - j2 * tsi / (ao * psisq) *
                    (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                        0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
            cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
            val cosio4 = cosio2 * cosio2
            val temp1 = 1.5 * j2 * pinvsq * noUnkozai
            val temp2 = 0.5 * temp1 * j2 * pinvsq
            val temp3 = -0.46875 * j4 * pinvsq * pinvsq * noUnkozai
            mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
            argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
            val xhdot1 = -temp1 * cosio
            nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
            val xpidot = argpdot + nodedot
            omgcof = bstar * cc3 * cos(argpo)
            xmcof = 0.0
            if (ecco > 1.0e-4) xmcof = -X2O3 * coef * bstar / eeta
            nodecf = 3.5 * omeosq * xhdot1 * cc1
            t2cof = 1.5 * cc1
            xlcof = if (abs(cosio + 1.0) > 1.5e-12) -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
            else -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / temp4
            aycof = -0.5 * j3oj2 * sinio
            val delmotemp = 1.0 + eta * cos(mo)
            delmo = delmotemp * delmotemp * delmotemp
            sinmao = sin(mo)
            x7thm1 = 7.0 * cosio2 - 1.0

            if (TWO_PI / noUnkozai >= 225.0) {
                method = 'd'
                isimp = 1
                val tc = 0.0
                val c = dscom(epoch, ecco, argpo, tc, inclo, nodeo, noUnkozai)
                val r = dpper(true, ecco, inclo, nodeo, argpo, mo)
                ecco = r[0]; inclo = r[1]; nodeo = r[2]; argpo = r[3]; mo = r[4]
                dsinit(c, tc, xpidot, eccsq)
            }
            if (isimp != 1) {
                val cc1sq = cc1 * cc1
                d2 = 4.0 * ao * tsi * cc1sq
                val temp = d2 * tsi * cc1 / 3.0
                d3 = (17.0 * ao + sfour) * temp
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
                t3cof = d2 + 2.0 * cc1sq
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
            }
        }
        propagate(0.0)
    }

    /** Values computed by dscom and needed by dsinit. */
    private class Dscom {
        var sinim = 0.0; var cosim = 0.0; var em = 0.0; var emsq = 0.0; var nm = 0.0
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0
        var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0
        var sz1 = 0.0; var sz3 = 0.0; var sz11 = 0.0; var sz13 = 0.0; var sz21 = 0.0; var sz23 = 0.0; var sz31 = 0.0; var sz33 = 0.0
        var z1 = 0.0; var z3 = 0.0; var z11 = 0.0; var z13 = 0.0; var z21 = 0.0; var z23 = 0.0; var z31 = 0.0; var z33 = 0.0
    }

    private fun dscom(epoch: Double, ep: Double, argpp: Double, tc: Double, inclp: Double, nodep: Double, np: Double): Dscom {
        val out = Dscom()
        val zes = 0.01675
        val zel = 0.05490
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458
        val nm = np
        val em = ep
        val snodm = sin(nodep)
        val cnodm = cos(nodep)
        val sinomm = sin(argpp)
        val cosomm = cos(argpp)
        val sinim = sin(inclp)
        val cosim = cos(inclp)
        val emsq = em * em
        val betasq = 1.0 - emsq
        val rtemsq = sqrt(betasq)
        peo = 0.0; pinco = 0.0; plo = 0.0; pgho = 0.0; pho = 0.0
        val day = epoch + 18261.5 + tc / 1440.0
        val xnodce = pmod(4.5236020 - 9.2422029e-4 * day)
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        val gam = 5.8351514 + 0.0019443680 * day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = gam + zx - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)
        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = cnodm
        var zsinh = snodm
        var cc = c1ss
        val xnoi = 1.0 / nm
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0; var s6 = 0.0; var s7 = 0.0
        var z1 = 0.0; var z2 = 0.0; var z3 = 0.0; var z11 = 0.0; var z12 = 0.0; var z13 = 0.0
        var z21 = 0.0; var z22 = 0.0; var z23 = 0.0; var z31 = 0.0; var z32 = 0.0; var z33 = 0.0
        var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0; var ss6 = 0.0; var ss7 = 0.0
        var sz1 = 0.0; var sz2 = 0.0; var sz3 = 0.0; var sz11 = 0.0; var sz12 = 0.0; var sz13 = 0.0
        var sz21 = 0.0; var sz22 = 0.0; var sz23 = 0.0; var sz31 = 0.0; var sz32 = 0.0; var sz33 = 0.0
        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10
            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm
            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = -0.5 * s3 / rtemsq
            s4 = s3 * rtemsq
            s1 = -15.0 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3
            if (lsflg == 1) {
                ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7
                sz1 = z1; sz2 = z2; sz3 = z3; sz11 = z11; sz12 = z12; sz13 = z13
                sz21 = z21; sz22 = z22; sz23 = z23; sz31 = z31; sz32 = z32; sz33 = z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }
        zmol = pmod(4.7199672 + 0.22997150 * day - gam)
        zmos = pmod(6.2565837 + 0.017201977 * day)
        se2 = 2.0 * ss1 * ss6
        se3 = 2.0 * ss1 * ss7
        si2 = 2.0 * ss2 * sz12
        si3 = 2.0 * ss2 * (sz13 - sz11)
        sl2 = -2.0 * ss3 * sz2
        sl3 = -2.0 * ss3 * (sz3 - sz1)
        sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes
        sgh2 = 2.0 * ss4 * sz32
        sgh3 = 2.0 * ss4 * (sz33 - sz31)
        sgh4 = -18.0 * ss4 * zes
        sh2 = -2.0 * ss2 * sz22
        sh3 = -2.0 * ss2 * (sz23 - sz21)
        ee2 = 2.0 * s1 * s6
        e3 = 2.0 * s1 * s7
        xi2 = 2.0 * s2 * z12
        xi3 = 2.0 * s2 * (z13 - z11)
        xl2 = -2.0 * s3 * z2
        xl3 = -2.0 * s3 * (z3 - z1)
        xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel
        xgh2 = 2.0 * s4 * z32
        xgh3 = 2.0 * s4 * (z33 - z31)
        xgh4 = -18.0 * s4 * zel
        xh2 = -2.0 * s2 * z22
        xh3 = -2.0 * s2 * (z23 - z21)
        return out.apply {
            this.sinim = sinim; this.cosim = cosim; this.em = em; this.emsq = emsq; this.nm = nm
            this.s1 = s1; this.s2 = s2; this.s3 = s3; this.s4 = s4; this.s5 = s5
            this.ss1 = ss1; this.ss2 = ss2; this.ss3 = ss3; this.ss4 = ss4; this.ss5 = ss5
            this.sz1 = sz1; this.sz3 = sz3; this.sz11 = sz11; this.sz13 = sz13; this.sz21 = sz21; this.sz23 = sz23; this.sz31 = sz31; this.sz33 = sz33
            this.z1 = z1; this.z3 = z3; this.z11 = z11; this.z13 = z13; this.z21 = z21; this.z23 = z23; this.z31 = z31; this.z33 = z33
        }
    }

    /** Deep-space long-period periodics; [init] computes the epoch values without applying them. */
    private fun dpper(init: Boolean, ep0: Double, inclp0: Double, nodep0: Double, argpp0: Double, mp0: Double): DoubleArray {
        var ep = ep0; var inclp = inclp0; var nodep = nodep0; var argpp = argpp0; var mp = mp0
        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.05490
        var zm = if (init) zmos else zmos + zns * t
        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = se2 * f2 + se3 * f3
        val sis = si2 * f2 + si3 * f3
        val sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf
        val sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf
        val shs = sh2 * f2 + sh3 * f3
        zm = if (init) zmol else zmol + znl * t
        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = ee2 * f2 + e3 * f3
        val sil = xi2 * f2 + xi3 * f3
        val sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf
        val sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf
        val shll = xh2 * f2 + xh3 * f3
        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll
        if (!init) {
            pe -= peo
            pinc -= pinco
            pl -= plo
            pgh -= pgho
            ph -= pho
            inclp += pinc
            ep += pe
            val sinip = sin(inclp)
            val cosip = cos(inclp)
            if (inclp >= 0.2) {
                ph /= sinip
                pgh -= cosip * ph
                argpp += pgh
                nodep += ph
                mp += pl
            } else {
                val sinop = sin(nodep)
                val cosop = cos(nodep)
                var alfdp = sinip * sinop
                var betdp = sinip * cosop
                val dalf = ph * cosop + pinc * cosip * sinop
                val dbet = -ph * sinop + pinc * cosip * cosop
                alfdp += dalf
                betdp += dbet
                nodep = if (nodep >= 0.0) nodep % TWO_PI else -(-nodep % TWO_PI)
                val xls = mp + argpp + pl + pgh + (cosip - pinc * sinip) * nodep
                val xnoh = nodep
                nodep = atan2(alfdp, betdp)
                if (abs(xnoh - nodep) > PI) {
                    nodep = if (nodep < xnoh) nodep + TWO_PI else nodep - TWO_PI
                }
                mp += pl
                argpp = xls - mp - cosip * nodep
            }
        }
        return doubleArrayOf(ep, inclp, nodep, argpp, mp)
    }

    private fun dsinit(c: Dscom, tc: Double, xpidot: Double, eccsq: Double) {
        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val znl = 1.5835218e-4
        val zns = 1.19459e-5
        val cosim = c.cosim
        val sinim = c.sinim
        var emsq = c.emsq
        var em = c.em
        val nm = c.nm
        val inclm = inclo
        irez = 0
        if (nm > 0.0034906585 && nm < 0.0052359877) irez = 1
        if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2
        val ses = c.ss1 * zns * c.ss5
        val sis = c.ss2 * zns * (c.sz11 + c.sz13)
        val sls = -zns * c.ss3 * (c.sz1 + c.sz3 - 14.0 - 6.0 * emsq)
        val sghs = c.ss4 * zns * (c.sz31 + c.sz33 - 6.0)
        var shs = -zns * c.ss2 * (c.sz21 + c.sz23)
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs
        dedt = ses + c.s1 * znl * c.s5
        didt = sis + c.s2 * znl * (c.z11 + c.z13)
        dmdt = sls - znl * c.s3 * (c.z1 + c.z3 - 14.0 - 6.0 * emsq)
        val sghl = c.s4 * znl * (c.z31 + c.z33 - 6.0)
        var shll = -znl * c.s2 * (c.z21 + c.z23)
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0
        domdt = sgs + sghl
        dnodt = shs
        if (sinim != 0.0) {
            domdt -= cosim / sinim * shll
            dnodt += shll / sinim
        }
        val theta = pmod(gsto + tc * rptim)
        // em, inclm, argpm, nodem and mm are advanced by t = 0 here: nothing to do.
        if (irez != 0) {
            val aonv = (nm / xke).pow(X2O3)
            if (irez == 2) {
                val cosisq = cosim * cosim
                val emo = em
                em = ecco
                val emsqo = emsq
                emsq = eccsq
                val eoc = em * emsq
                val g201 = -0.306 - (em - 0.64) * 0.440
                val g211: Double; val g310: Double; val g322: Double; val g410: Double; val g422: Double; val g520: Double
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc
                    g520 = if (em > 0.715) -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                    else 1464.74 - 4664.75 * em + 3763.64 * emsq
                }
                val g533: Double; val g521: Double; val g532: Double
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) + 0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq))
                val f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) + 6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq))
                val f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
                val f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))
                val xno2 = nm * nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = pmod(mo + nodeo + nodeo - theta - theta)
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - noUnkozai
                em = emo
                emsq = emsqo
            }
            if (irez == 1) {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 = 1.875 * f330 * f330 * f330
                del1 = 3.0 * nm * nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = pmod(mo + nodeo + argpo - theta)
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - noUnkozai
            }
            xli = xlamo
            xni = noUnkozai
            atime = 0.0
        }
    }

    /** Deep-space secular effects and resonance integration. Returns em, argpm, inclm, mm, nodem, nm. */
    private fun dspace(t: Double, em0: Double, argpm0: Double, inclm0: Double, mm0: Double, nodem0: Double, nm0: Double): DoubleArray {
        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0
        val theta = pmod(gsto + t * rptim)
        val em = em0 + dedt * t
        val inclm = inclm0 + didt * t
        val argpm = argpm0 + domdt * t
        val nodem = nodem0 + dnodt * t
        var mm = mm0 + dmdt * t
        var nm = nm0
        if (irez != 0) {
            // Integrated from the epoch on each call (like the Python port) so results never depend on call order.
            var atime = 0.0
            var xni = noUnkozai
            var xli = xlamo
            val delt = if (t > 0.0) stepp else stepn
            var ft = 0.0
            var xndt: Double
            var xldot: Double
            var xnddt: Double
            while (true) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - fasx2) + del2 * sin(2.0 * (xli - fasx4)) + del3 * sin(3.0 * (xli - fasx6))
                    xldot = xni + xfact
                    xnddt = del1 * cos(xli - fasx2) + 2.0 * del2 * cos(2.0 * (xli - fasx4)) + 3.0 * del3 * cos(3.0 * (xli - fasx6))
                    xnddt *= xldot
                } else {
                    val xomi = argpo + argpdot * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22) +
                        d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32) +
                        d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44) +
                        d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52) +
                        d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54)
                    xldot = xni + xfact
                    xnddt = d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22) +
                        d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32) +
                        d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52) +
                        2.0 * (d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44) +
                            d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54))
                    xnddt *= xldot
                }
                if (abs(t - atime) >= stepp) {
                    xli = xli + xldot * delt + xndt * step2
                    xni = xni + xndt * delt + xnddt * step2
                    atime += delt
                } else {
                    ft = t - atime
                    break
                }
            }
            nm = xni + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xli + xldot * ft + xndt * ft * ft * 0.5
            mm = if (irez != 1) xl - 2.0 * nodem + 2.0 * theta else xl - nodem - argpm + theta
            val dndt = nm - noUnkozai
            nm = noUnkozai + dndt
        }
        return doubleArrayOf(em, argpm, inclm, mm, nodem, nm)
    }

    companion object {
        fun jday(year: Int, mon: Int, day: Int, hr: Int = 0, minute: Int = 0, sec: Double = 0.0): Double =
            367.0 * year - floor(7 * (year + floor((mon + 9) / 12.0)) * 0.25) + floor(275 * mon / 9.0) + day + 1721013.5 +
                ((sec / 60.0 + minute) / 60.0 + hr) / 24.0

        /** Greenwich mean sidereal time (rad), IAU-82, as used by SGP4. */
        fun gstime(jdut1: Double): Double {
            val tut1 = (jdut1 - 2451545.0) / 36525.0
            var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
            temp = pmod(temp * (PI / 180.0) / 240.0)
            return temp
        }

        fun jdFromUnixMs(ms: Long): Double = ms / 86_400_000.0 + 2440587.5
    }
}
