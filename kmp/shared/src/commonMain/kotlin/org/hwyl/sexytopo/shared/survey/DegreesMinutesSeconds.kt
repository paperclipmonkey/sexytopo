package org.hwyl.sexytopo.shared.survey

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An angle as a compass reads it — degrees, minutes and seconds — rather than as a decimal.
 *
 * ## The sign comes from the text, not from the number
 *
 * Upstream takes it from the parsed value: `float sign = degrees < 0 ? -1.0f : 1.0f`. That is
 * wrong for one input and the input is not exotic — a shot *just* below horizontal. `-0` parses to
 * negative zero, `-0.0f < 0` is false by IEEE 754, so the sign comes out positive and **0° 30′
 * down is recorded as 0° 30′ up**.
 */
object DegreesMinutesSeconds {

    /**
     * The three typed fields as one angle, or null if they do not make one.
     *
     * Minutes and seconds are magnitudes — the direction is the degrees field's `-`, which is read
     * off the **text** for the reason above.
     */
    fun toDecimal(degrees: String, minutes: String, seconds: String): Float? {
        val typed = degrees.trim().replace(',', '.')
        if (typed.isEmpty() || typed == "-" || typed == "+") return null
        val magnitude = typed.removePrefix("-").removePrefix("+").toFloatOrNull() ?: return null
        if (magnitude < 0f) return null

        val minuteValue = optional(minutes) ?: return null
        val secondValue = optional(seconds) ?: return null
        if (minuteValue < 0f || secondValue < 0f) return null

        val total = magnitude + minuteValue / 60f + secondValue / 3600f
        return if (typed.startsWith("-")) -total else total
    }

    /** A blank field is zero; anything unreadable is null, so the caller can say so. */
    private fun optional(field: String): Float? {
        val trimmed = field.trim().replace(',', '.')
        return if (trimmed.isEmpty()) 0f else trimmed.toFloatOrNull()
    }

    /**
     * Rounded to whole seconds, because that is what the fields hold and because a compass cannot
     * be read to better. Rounding can carry — 0.99999 degrees is 59′ 60″, which is 1° 0′ 0″ — so
     * the carry is done here rather than left to produce a field reading "60".
     */
    fun of(value: Float): Parts {
        val negative = value < 0f
        var totalSeconds = (abs(value) * 3600f).roundToInt()
        val degrees = totalSeconds / 3600
        totalSeconds -= degrees * 3600
        val minutes = totalSeconds / 60
        val seconds = totalSeconds - minutes * 60
        return Parts(degrees = degrees, minutes = minutes, seconds = seconds, negative = negative)
    }

    /**
     * An angle split up.
     *
     * [degrees] is a magnitude and [negative] carries the direction, rather than the degrees being
     * signed — because a signed zero cannot carry it.
     */
    data class Parts(
        val degrees: Int,
        val minutes: Int,
        val seconds: Int,
        val negative: Boolean,
    ) {
        val degreesText: String
            get() = if (negative) "-$degrees" else "$degrees"

        val minutesText: String get() = "$minutes"

        val secondsText: String get() = "$seconds"
    }
}

