package com.allnetworktools.data.mesh

import java.io.ByteArrayOutputStream

/** Minimal protobuf writer: enough to build the ToRadio messages of the Meshtastic client API. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun raw(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    private fun tag(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())

    /** A varint field, written even when zero (a later field of the same number overrides an earlier one). */
    fun varint(field: Int, v: Long): ProtoWriter { tag(field, 0); raw(v); return this }
    fun int(field: Int, v: Int): ProtoWriter = varint(field, v.toLong())
    fun bool(field: Int, v: Boolean): ProtoWriter = varint(field, if (v) 1 else 0)

    fun fixed32(field: Int, v: Long): ProtoWriter {
        tag(field, 5)
        for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun float(field: Int, v: Float): ProtoWriter = fixed32(field, java.lang.Float.floatToIntBits(v).toLong() and 0xFFFFFFFFL)

    fun bytes(field: Int, b: ByteArray): ProtoWriter { tag(field, 2); raw(b.size.toLong()); out.write(b); return this }
    fun string(field: Int, s: String): ProtoWriter = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    /** Copies already-encoded fields (unknown ones included), to be overridden by the fields written after. */
    fun rawBytes(b: ByteArray): ProtoWriter { out.write(b); return this }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal protobuf reader: every field of a message by number, the last value winning for single fields. */
class ProtoMsg private constructor(val raw: ByteArray, private val fields: Map<Int, List<Any>>) {
    private fun last(f: Int): Any? = fields[f]?.lastOrNull()

    fun has(f: Int) = fields.containsKey(f)
    fun long(f: Int): Long = (last(f) as? Long) ?: 0L
    fun int(f: Int): Int = long(f).toInt()
    fun uint(f: Int): Long = long(f) and 0xFFFFFFFFL
    fun bool(f: Int): Boolean = long(f) != 0L
    fun float(f: Int): Float = java.lang.Float.intBitsToFloat(long(f).toInt())
    fun bytes(f: Int): ByteArray? = last(f) as? ByteArray
    fun str(f: Int): String? = bytes(f)?.toString(Charsets.UTF_8)
    fun msg(f: Int): ProtoMsg? = bytes(f)?.let(::parse)
    fun msgs(f: Int): List<ProtoMsg> = fields[f].orEmpty().mapNotNull { (it as? ByteArray)?.let(::parse) }

    /** Signed 32-bit value of a varint, fixed32 or sfixed32 field. */
    fun int32(f: Int): Int = long(f).toInt()

    companion object {
        fun parse(b: ByteArray): ProtoMsg? {
            val map = HashMap<Int, MutableList<Any>>()
            var i = 0
            fun varint(): Long? {
                var r = 0L
                var shift = 0
                while (i < b.size && shift < 64) {
                    val x = b[i++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                }
                return null
            }
            while (i < b.size) {
                val key = varint() ?: return null
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint() ?: return null
                    1 -> { if (i + 8 > b.size) return null; var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; v }
                    2 -> {
                        val len = varint()?.toInt() ?: return null
                        if (len < 0 || i + len > b.size) return null
                        b.copyOfRange(i, i + len).also { i += len }
                    }
                    5 -> { if (i + 4 > b.size) return null; var v = 0L; for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; v }
                    else -> return null
                }
                map.getOrPut(field) { ArrayList() } += value
            }
            return ProtoMsg(b, map)
        }
    }
}
