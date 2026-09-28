package com.allnetworktools.util

import kotlin.math.abs
import kotlin.math.roundToLong

/** French number formatting: narrow no-break space thousands, decimal comma, true minus sign. */
fun fmt(value: Number, decimals: Int = 0): String {
    val v = value.toDouble()
    if (v.isNaN()) return "—"
    val a = abs(v)
    val fixed = if (decimals == 0) a.roundToLong().toString() else String.format(java.util.Locale.ROOT, "%.${decimals}f", a)
    val parts = fixed.split('.')
    val int = parts[0].reversed().chunked(3).joinToString(" ").reversed()
    val out = if (parts.size > 1) "$int,${parts[1]}" else int
    val isZero = out.all { it == '0' || it == ',' || it == ' ' }
    return if (v < 0 && !isZero) "−$out" else out
}

fun plural(n: Int, singular: String, plural: String = singular + "s") = if (n > 1) plural else singular

fun durationFr(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "moins d'une minute"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60} min"
    }
}
