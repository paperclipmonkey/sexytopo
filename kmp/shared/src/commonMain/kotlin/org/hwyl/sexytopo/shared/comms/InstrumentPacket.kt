package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * What a decoder makes of one inbound frame, whatever the instrument.
 *
 * Every protocol in this package ultimately produces one of these, so the app layer can consume
 * shots and calibration readings without knowing which device is on the other end — which is what
 * the Android app's `SurveyManager.updateSurvey(Leg)` / `addCalibrationReading` pair does today,
 * only there each `*Manager` reaches for the manager directly.
 */
sealed interface InstrumentPacket {

    /** A completed survey shot. */
    class Measurement(
        val leg: Leg,
        val detail: ShotDetail = ShotDetail.NONE,
    ) : InstrumentPacket {
        override fun toString(): String = "Measurement($leg, $detail)"
    }

    /**
     * Half of a DistoX calibration reading: raw accelerometer counts.
     *
     * These are unscaled 16-bit sensor counts, not physical units; the calibration algorithm
     * normalises them itself, which is why the Java model (`model.calibration.CalibrationReading`)
     * stores plain ints.
     */
    data class Acceleration(val gx: Int, val gy: Int, val gz: Int) : InstrumentPacket

    /** The other half of a DistoX calibration reading: raw magnetometer counts. */
    data class Magnetic(val mx: Int, val my: Int, val mz: Int) : InstrumentPacket

    /**
     * A matched acceleration + magnetic pair — one usable calibration reading.
     *
     * The DistoX always sends the acceleration packet first and the magnetic packet second; a
     * reading is only complete once both have arrived.
     */
    data class CalibrationReading(
        val gx: Int,
        val gy: Int,
        val gz: Int,
        val mx: Int,
        val my: Int,
        val mz: Int,
    ) : InstrumentPacket

    /** A DistoX memory read/write reply (packet type 0x38). */
    class ReadReply(val address: Int, val payload: ByteArray) : InstrumentPacket {
        override fun toString(): String = "ReadReply(0x${address.toString(16)}, ${payload.toHex()})"

        override fun equals(other: Any?): Boolean =
            other is ReadReply && address == other.address && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * address + payload.contentHashCode()
    }

    /**
     * The device reported a problem with a shot rather than a reading.
     *
     * Only BRIC4/BRIC5 do this (over the 58d3 errors characteristic). [showToUser] mirrors
     * `Bric4Manager.reportError`, which toasts the first error of a pair but only logs the second.
     */
    data class DeviceFailure(
        val code: Int,
        val description: String,
        val data1: Float,
        val data2: Float,
        val showToUser: Boolean,
    ) : InstrumentPacket

    /** A frame whose type byte matched nothing known; kept whole so it can be logged. */
    class Unrecognised(val raw: ByteArray) : InstrumentPacket {
        override fun toString(): String = "Unrecognised(${raw.toHex()})"

        override fun equals(other: Any?): Boolean =
            other is Unrecognised && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }
}

/**
 * Per-instrument extras that ride along with a shot. All optional: the classic DistoX supplies
 * none of them, BRIC4 supplies a reference number and timestamp, SAP6 and Cavway a roll angle,
 * FCL the lot.
 */
data class ShotDetail(
    /** The instrument's own sequence number for this shot, as text (BRIC4's 32-bit counter). */
    val reference: String? = null,
    /** Rotation about the shot axis, in degrees. Not used by the survey model, but logged. */
    val roll: Float? = null,
    /** When the instrument says it took the shot. Only BRIC4/BRIC5 report this. */
    val timestamp: DeviceTimestamp? = null,
    /** FCL's self-assessed shot quality, 0.0..1.0. */
    val shotQuality: Float? = null,
    /** Battery charge, 0..100 percent (FCL). */
    val batteryPercent: Int? = null,
    /** Instrument temperature in degrees Celsius (FCL). */
    val temperatureCelsius: Float? = null,
) {
    companion object {
        val NONE = ShotDetail()
    }
}

/**
 * A wall-clock reading straight off the instrument, kept as raw fields.
 *
 * `Bric4Manager` builds a `java.time.LocalDateTime` here, which is unavailable in common code and
 * — more importantly — **throws** on a corrupt packet (month 0, day 40 and so on) from inside the
 * BLE callback. Keeping the fields raw and offering [isValidDate] means a garbled frame degrades
 * to a flagged timestamp instead of an exception thrown across a callback boundary. The Java also
 * discards the centisecond field; it is preserved here because it costs nothing.
 */
data class DeviceTimestamp(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val centisecond: Int = 0,
) {
    /** The range check `LocalDateTime.of` would apply, minus the days-in-month refinement. */
    fun isValidDate(): Boolean =
        month in 1..12 &&
            day in 1..31 &&
            hour in 0..23 &&
            minute in 0..59 &&
            second in 0..59

    /** ISO-8601-ish, hand-rolled because `String.format` is JVM-only. */
    override fun toString(): String =
        "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T" +
            "${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}"

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
