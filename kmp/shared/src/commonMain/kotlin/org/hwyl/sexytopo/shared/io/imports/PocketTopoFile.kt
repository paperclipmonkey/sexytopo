package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.survey.SurveyDate

/** What a `.top` file being unreadable looks like. */
class PocketTopoFormatException(message: String) : Exception(message)

/**
 * A cursor over a byte array, reading the primitives PocketTopo's `.top` format is made of.
 *
 * An array with a position rather than the Java's `InputStream`, since `commonMain` has none and
 * a `.top` file is a few hundred kilobytes — nothing to stream. Everything is little-endian,
 * because the format is a .NET `BinaryWriter` dump.
 */
internal class ByteReader(private val bytes: ByteArray) {

    var position: Int = 0
        private set

    val remaining: Int get() = bytes.size - position

    fun readByte(): Int {
        if (position >= bytes.size) throw PocketTopoFormatException("Unexpected end of file")
        return bytes[position++].toInt() and 0xFF
    }

    fun readInt16(): Short {
        val low = readByte()
        val high = readByte()
        return (low or (high shl 8)).toShort()
    }

    fun readInt32(): Int {
        var value = 0
        for (shift in 0 until 4) {
            value = value or (readByte() shl (shift * 8))
        }
        return value
    }

    fun readInt64(): Long {
        val low = readInt32().toLong() and 0xFFFFFFFFL
        val high = readInt32().toLong() and 0xFFFFFFFFL
        return low or (high shl 32)
    }

    /**
     * A .NET `BinaryWriter` string: a 7-bit-encoded length, then that many bytes of UTF-8.
     *
     * The length is written seven bits at a time, least significant first, with the top bit set on
     * every byte but the last.
     */
    fun readString(): String {
        var length = 0
        var shift = 0
        while (true) {
            val b = readByte()
            length = length or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            // Five 7-bit groups is the most a 32-bit length needs; more means a corrupt file, and
            // without this guard the shift runs past Int's width into a plausible-looking number.
            if (shift > 28) throw PocketTopoFormatException("String length is not a valid length")
        }
        if (length == 0) return ""
        if (length < 0 || length > remaining) {
            throw PocketTopoFormatException("String of $length bytes runs past the end of the file")
        }
        val text = bytes.decodeToString(position, position + length)
        position += length
        return text
    }

    /** A station id, or null for the undefined id a splay's far end carries. */
    fun readId(): String? = PocketTopoFile.idToName(readInt32())

    fun skip(count: Int) {
        if (count > remaining) throw PocketTopoFormatException("Unexpected end of file")
        position += count
    }
}

/**
 * The `.top` format's constants and unit conversions.
 *
 * Ported from `PocketTopoFile`. The interesting ones are the angles: PocketTopo stores a full
 * circle as 2^16, and reads the *same* sixteen bits as unsigned for a bearing (0 to 360) and signed
 * for an inclination (-180 to +180), which is why there are two functions rather than one.
 */
object PocketTopoFile {

    const val FULL_CIRCLE = 65536
    const val UNDEFINED_ID = -0x80000000

    /** .NET ticks — hundreds of nanoseconds since 1 Jan 0001 — at the Unix epoch. */
    const val TICKS_AT_EPOCH = 621355968000000000L
    const val TICKS_PER_MILLISECOND = 10000L
    private const val MILLISECONDS_PER_DAY = 86400000L

    /**
     * A raw station id as a name: the undefined id is a splay's missing far end, a negative value
     * is a plain number offset by `0x80000001`, and anything else is `major.minor` packed into
     * the two halves of the word.
     */
    fun idToName(value: Int): String? =
        when {
            value == UNDEFINED_ID -> null
            value < 0 -> ((value.toLong() and 0xFFFFFFFFL) - 0x80000001L).toString()
            else -> "${(value shr 16) and 0xFFFF}.${value and 0xFFFF}"
        }

    /** North is 0 and east is 0x4000. Unsigned, so the range is 0 to 360. */
    fun azimuthToDegrees(raw: Short): Float =
        ((raw.toInt() and 0xFFFF) * 360.0f) / FULL_CIRCLE

    /** Up is 0x4000 and down is 0xC000. Signed, so the range is -180 to +180. */
    fun inclinationToDegrees(raw: Short): Float = (raw * 360.0f) / FULL_CIRCLE

    fun distanceToMetres(millimetres: Int): Float = millimetres / 1000.0f

    /**
     * A .NET `DateTime` as a calendar date: no platform `Date` here, so this is civil-from-days
     * written out by hand. Proleptic Gregorian, matching .NET ticks.
     */
    fun ticksToDate(ticks: Long): SurveyDate {
        val millis = (ticks - TICKS_AT_EPOCH) / TICKS_PER_MILLISECOND
        // Floor division, so that a date before 1970 does not round towards zero and land a day out.
        val days = if (millis >= 0) {
            millis / MILLISECONDS_PER_DAY
        } else {
            -((-millis + MILLISECONDS_PER_DAY - 1) / MILLISECONDS_PER_DAY)
        }
        return civilFromDays(days)
    }

    /**
     * Howard Hinnant's `civil_from_days`: days since 1970-01-01 to year/month/day with no lookup
     * tables. The trick is shifting the era so March is the first month, so leap days fall at the
     * end of the year and every 400-year era has exactly 146097 days.
     */
    private fun civilFromDays(days: Long): SurveyDate {
        val shifted = days + 719468
        val era = (if (shifted >= 0) shifted else shifted - 146096) / 146097
        val dayOfEra = shifted - era * 146097
        val yearOfEra =
            (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthPrime = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
        val month = if (monthPrime < 10) monthPrime + 3 else monthPrime - 9
        return SurveyDate((if (month <= 2) year + 1 else year).toInt(), month.toInt(), day.toInt())
    }

    /** PocketTopo's seven pen colours. Anything else draws black rather than refusing the file. */
    fun topoColourToColour(colourByte: Int): Colour =
        when (colourByte) {
            1 -> Colour.BLACK
            2 -> Colour.GREY
            3 -> Colour.BROWN
            4 -> Colour.BLUE
            5 -> Colour.RED
            6 -> Colour.GREEN
            7 -> Colour.ORANGE
            else -> Colour.BLACK
        }
}
