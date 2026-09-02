package org.hwyl.sexytopo.shared.io.export

import kotlin.math.abs
import kotlin.math.floor

/**
 * Fixed-decimal formatting, reproducing Java's `String.format(Locale.UK, "%.Nf", value)`.
 *
 * commonMain has no `String.format`, and these numbers go into Therion and Survex files that
 * must match the Android app's output exactly.
 *
 * Two traps a naive port falls into:
 *
 *  - **Rounding mode.** `kotlin.math.round` is ties-to-even (maps to `Math.rint`); Java's
 *    `Formatter` is HALF_UP — `round(2.5)` gives 2, not 3. Hence `floor(x + 0.5)`.
 *  - **Locale.** `Locale.UK` stops a comma-decimal device writing `1,50` where a parser expects
 *    `1.50`. Building the string from integers has the same effect.
 */
fun formatFixed(value: Float, decimalPlaces: Int, alwaysSigned: Boolean = false): String =
    formatFixed(value.toDouble(), decimalPlaces, alwaysSigned)

fun formatFixed(value: Double, decimalPlaces: Int, alwaysSigned: Boolean = false): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"

    var scale = 1L
    repeat(decimalPlaces) { scale *= 10 }

    val scaled = floor(abs(value) * scale + 0.5).toLong()
    val whole = scaled / scale
    val fraction = scaled % scale

    // The sign comes from the input, not the rounded result: -0.001 at two decimal places is
    // "-0.00" in Java. Negative zero needs the reciprocal test since -0.0 < 0 is false in IEEE.
    val isNegative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    val sign =
        when {
            isNegative -> "-"
            alwaysSigned -> "+"
            else -> ""
        }

    return if (decimalPlaces == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${fraction.toString().padStart(decimalPlaces, '0')}"
    }
}

/** Distance, as `TableCol.DISTANCE` formats it: `%.3f`. */
fun formatDistance(metres: Float): String = formatFixed(metres, 3)

/** Azimuth, as `TableCol.AZIMUTH` formats it: `%.2f`. */
fun formatAzimuth(degrees: Float): String = formatFixed(degrees, 2)

/**
 * Inclination as the *exporters* write it: plain `%.2f`, not `TableCol.INCLINATION`'s signed
 * `%+.2f` (a table-display choice that would diverge from the Android app's file output).
 */
fun formatInclination(degrees: Float): String = formatFixed(degrees, 2)

/**
 * Fixed-point with trailing zeros removed, for SVG attributes: exponent notation like "1.0E-4"
 * (which `Float.toString` renders differently on the JVM and Kotlin/Wasm) is not valid inside an
 * SVG path and would silently produce a file that will not open.
 */
fun formatFixedTrimmed(value: Float, decimalPlaces: Int): String =
    trimTrailingZeroes(formatFixed(value, decimalPlaces))

/**
 * The same for a Double: the SVG legend lays itself out in Double arithmetic (matching the
 * Java's `LegendModel`), so its coordinates would otherwise round through Float on the way out.
 */
fun formatFixedTrimmed(value: Double, decimalPlaces: Int): String =
    trimTrailingZeroes(formatFixed(value, decimalPlaces))

private fun trimTrailingZeroes(fixed: String): String {
    if ('.' !in fixed) return fixed
    val trimmed = fixed.trimEnd('0').trimEnd('.')
    // "-0" reads as a mistake even though it is the same number.
    return if (trimmed == "-0") "0" else trimmed.ifEmpty { "0" }
}
