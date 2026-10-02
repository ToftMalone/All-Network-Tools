package com.allnetworktools.data.sdr

/**
 * Reed-Solomon (255, 223) of CCSDS 101.0-B: GF(2^8) with x^8+x^7+x^2+x+1, 32 check symbols whose roots are
 * (α^11)^j for j = 112…143. Symbols may be in Berlekamp's dual basis (the CCSDS transmission format) or in the
 * conventional basis.
 */
object Rs255 {
    const val N = 255
    const val K = 223
    private const val NROOTS = 32
    private const val FCR = 112
    private const val PRIM = 11

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x187
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    /** Dual-basis column vectors: dual(x) = bit (7−j) is Tr(x·β^j), β = α^117. */
    private val tal = intArrayOf(0x8d, 0xef, 0xec, 0x86, 0xfa, 0x99, 0xaf, 0x7b)

    /** Conventional → dual basis. */
    val toDual = IntArray(256) { i ->
        var v = 0
        for (j in 0 until 8) for (k in 0 until 8) if (i and (1 shl k) != 0) v = v xor (tal[7 - k] and (1 shl j))
        v
    }

    /** Dual basis → conventional. */
    val fromDual = IntArray(256).also { t -> for (i in 0 until 256) t[toDual[i]] = i }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int) = if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    private fun alphaPow(e: Int) = exp[Math.floorMod(e, 255)]

    private val roots = IntArray(NROOTS) { alphaPow(PRIM * (FCR + it)) }

    /** Generator polynomial coefficients, lowest degree first (monic). */
    private val gen: IntArray = run {
        var g = intArrayOf(1)
        for (r in roots) {
            val n = IntArray(g.size + 1)
            for (i in g.indices) { n[i + 1] = n[i + 1] xor g[i]; n[i] = n[i] xor mul(g[i], r) }
            g = n
        }
        g
    }

    /** 32 check symbols of [data] (223 symbols) in the same basis as the data. */
    fun encode(data: ByteArray, dual: Boolean): ByteArray {
        require(data.size == K)
        val rem = IntArray(NROOTS)
        for (b in data) {
            val d = if (dual) fromDual[b.toInt() and 0xFF] else b.toInt() and 0xFF
            val fb = d xor rem[0]
            for (i in 0 until NROOTS - 1) rem[i] = rem[i + 1] xor mul(fb, gen[NROOTS - 1 - i])
            rem[NROOTS - 1] = mul(fb, gen[0])
        }
        return ByteArray(NROOTS) { (if (dual) toDual[rem[it]] else rem[it]).toByte() }
    }

    /**
     * Corrects [cw] (255 symbols) in place. Returns the number of corrected symbols, or −1 when the word has more than
     * 16 errors (or is otherwise not a codeword within that distance).
     */
    fun decode(cw: ByteArray, dual: Boolean): Int {
        val c = IntArray(N) { val v = cw[it].toInt() and 0xFF; if (dual) fromDual[v] else v }
        val s = syndromes(c)
        if (s.all { it == 0 }) return 0

        // Berlekamp–Massey.
        var lambda = IntArray(NROOTS + 1).also { it[0] = 1 }
        var b = IntArray(NROOTS + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bb = 1
        for (n in 0 until NROOTS) {
            var d = s[n]
            for (i in 1..l) d = d xor mul(lambda[i], s[n - i])
            if (d == 0) { m++; continue }
            val coef = div(d, bb)
            if (2 * l <= n) {
                val t = lambda.copyOf()
                for (i in 0..NROOTS - m) lambda[i + m] = lambda[i + m] xor mul(coef, b[i])
                l = n + 1 - l
                b = t; bb = d; m = 1
            } else {
                for (i in 0..NROOTS - m) lambda[i + m] = lambda[i + m] xor mul(coef, b[i])
                m++
            }
        }
        if (l > NROOTS / 2) return -1

        // Chien search: Λ(X⁻¹) = 0 for X = (α^PRIM)^e, e being the power of x the error sits on.
        val powers = ArrayList<Int>()
        for (e in 0 until N) {
            val xinv = alphaPow(-PRIM * e)
            var acc = 0
            var xp = 1
            for (i in 0..l) { acc = acc xor mul(lambda[i], xp); xp = mul(xp, xinv) }
            if (acc == 0) powers += e
        }
        if (powers.size != l) return -1

        // Error evaluator Ω = S·Λ mod x^32, then Forney.
        val omega = IntArray(NROOTS)
        for (k in 0 until NROOTS) {
            var acc = 0
            for (i in 0..minOf(k, l)) acc = acc xor mul(lambda[i], s[k - i])
            omega[k] = acc
        }
        for (e in powers) {
            val xinv = alphaPow(-PRIM * e)
            var num = 0
            var xp = 1
            for (k in 0 until NROOTS) { num = num xor mul(omega[k], xp); xp = mul(xp, xinv) }
            var den = 0
            xp = 1 // Λ'(x) in characteristic 2 keeps the odd terms: Σ λ_i x^(i−1)
            var i = 1
            while (i <= l) { den = den xor mul(lambda[i], xp); xp = mul(xp, mul(xinv, xinv)); i += 2 }
            if (den == 0) return -1
            val y = mul(alphaPow(PRIM * e * (1 - FCR)), div(num, den))
            val j = N - 1 - e
            c[j] = c[j] xor y
        }
        if (!syndromes(c).all { it == 0 }) return -1
        for (i in 0 until N) cw[i] = (if (dual) toDual[c[i]] else c[i]).toByte()
        return powers.size
    }

    private fun syndromes(c: IntArray): IntArray = IntArray(NROOTS) { i ->
        var acc = 0
        val r = roots[i]
        for (j in 0 until N) acc = mul(acc, r) xor c[j]
        acc
    }
}
