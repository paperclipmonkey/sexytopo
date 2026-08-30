package org.hwyl.sexytopo.shared.io.export

import kotlin.math.abs
import kotlin.math.floor

/**
 * Fixed-decimal formatting, reproducing Java's `String.format(Locale.UK, "%.Nf", value)`.
 *
 * commonMain has no `String.format`, and this is not a detail that can be approximated: these
 * numbers go into Therion and Survex files that must match what the Android app writes, or a
 * survey exported from one and re-imported into the other will disagree.
 *
 * Two traps, both of which a naive port falls into:
 *
 *  - **Rounding mode.** `kotlin.math.round` is ties-to-even (it maps to `Math.rint`), while Java's
 *    `Formatter` is HALF_UP. `round(2.5)` gives 2, not 3. Hence `floor(x + 0.5)` on the magnitude.
 *  - **Locale.** The Java pins `Locale.UK` precisely so a device set to a comma-decimal locale does
 *    not write `1,50` into a file a parser expects `1.50` in. Building the string by hand from
 *    integers has the same effect.
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

    // The sign comes from the input, not from the rounded result: -0.001 at two decimal places
    // is "-0.00" in Java, not "0.00". Negative zero needs the reciprocal test, because -0.0 < 0
    // is false in IEEE arithmetic while Java's Formatter still writes it with a minus sign.
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
 * Inclination as the *exporters* write it: plain `%.2f`.
 *
 * Note this is NOT `TableCol.INCLINATION`'s `%+.2f`. The leading plus is a table-display choice;
 * `SurvexTherionUtil.formatInclination` deliberately omits it, and putting it in the file would be
 * a difference from the Android app's output.
 */
fun formatInclination(degrees: Float): String = formatFixed(degrees, 2)

/**
 * Fixed-point with the trailing zeros taken off, for SVG attributes.
 *
 * SVG is verbose enough without "100.000" everywhere, and a coordinate written as "1.0E-4" — which
 * is what `Float.toString` produces for small values, differently on the JVM and on Kotlin/Wasm —
 * is not valid inside a path and makes a file that silently will not open.
 */
fun formatFixedTrimmed(value: Float, decimalPlaces: Int): String =
    trimTrailingZeroes(formatFixed(value, decimalPlaces))

/**
 * The same for a Double.
 *
 * The SVG legend lays itself out in Double arithmetic — the Java's `LegendModel` does, and the
 * layout is reproduced exactly — so its coordinates would otherwise round through Float on the way
 * out.
 */
fun formatFixedTrimmed(value: Double, decimalPlaces: Int): String =
    trimTrailingZeroes(formatFixed(value, decimalPlaces))

private fun trimTrailingZeroes(fixed: String): String {
    if ('.' !in fixed) return fixed
    val trimmed = fixed.trimEnd('0').trimEnd('.')
    // "-0" reads as a mistake even though it is the same number.
    return if (trimmed == "-0") "0" else trimmed.ifEmpty { "0" }
}
