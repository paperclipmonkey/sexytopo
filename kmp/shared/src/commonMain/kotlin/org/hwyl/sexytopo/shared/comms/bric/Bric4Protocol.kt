package org.hwyl.sexytopo.shared.comms.bric

import org.hwyl.sexytopo.shared.comms.DeviceTimestamp
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.ShotDetail
import org.hwyl.sexytopo.shared.comms.floatLE
import org.hwyl.sexytopo.shared.comms.int16LE
import org.hwyl.sexytopo.shared.comms.int32LE
import org.hwyl.sexytopo.shared.comms.uint8
import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * Errors the BRIC4/BRIC5 reports over its 58d3 characteristic. Ported from `comms/bric4/Bric4Error`.
 *
 * Two faults in the Java table are preserved: code 10 appears twice (COMMUNICATION_ERROR and
 * TIMEOUT), and code 11 is missing. The Java builds its lookup with a HashMap in declaration
 * order, so TIMEOUT wins code 10; [fromCode] uses `associateBy`, which resolves ties the same way.
 * Code 11 falls through to [UNRECOGNISED_ERROR].
 */
enum class Bric4Error(val code: Int, val description: String) {
    NO_ERROR(0, "no error"),
    ACCELEROMETER_1_HIGH_MAGNITUDE(1, "accelerometer 1 high magnitude"),
    ACCELEROMETER_2_HIGH_MAGNITUDE(2, "accelerometer 2 high magnitude"),
    MAGNETOMETER_1_HIGH_MAGNITUDE(3, "magnetometer 1 high magnitude"),
    MAGNETOMETER_2_HIGH_MAGNITUDE(4, "magnetometer 2 high magnitude"),
    ACCELEROMETER_DISPARITY(5, "accelerometer disparity"),
    MAGNETOMETER_DISPARITY(6, "magnetometer disparity"),
    TOO_FAST(7, "target moved too fast"),
    TOO_WEAK(8, "target didn't reflect"),
    TOO_REFLECTIVE(9, "target too reflective"),
    COMMUNICATION_ERROR(10, "communication error"),
    TIMEOUT(10, "message timeout"),
    UNRECOGNISED_ERROR(12, "unrecognised error"),
    WRONG_MESSAGE(13, "wrong message received"),
    INCLINATION_ERROR(14, "inclination calculation problem"),
    AZIMUTH_ERROR(15, "azimuth calculation problem"),
    ;

    override fun toString(): String = description

    companion object {
        private val byCode: Map<Int, Bric4Error> = entries.associateBy { it.code }

        fun fromCode(code: Int): Bric4Error = byCode[code] ?: UNRECOGNISED_ERROR
    }
}

/** One shot as reported on the 58d1 primary characteristic. */
data class Bric4Measurement(
    val timestamp: DeviceTimestamp,
    /** Zeroed when the reading is out of range — see [isLegal]. */
    val leg: Leg,
    val rawDistance: Float,
    val rawAzimuth: Float,
    val rawInclination: Float,
    /** False when the raw reading could not be made into a [Leg]. */
    val isLegal: Boolean,
)

/** Shot metadata as reported on the 58d2 characteristic. */
data class Bric4Metadata(
    /** The instrument's own shot counter. Signed, because the Java renders it with `toString`. */
    val reference: Int,
    val dip: Float,
    val roll: Float,
    val temperatureCelsius: Float,
    val samplesMean: Int,
    val measurementType: Int,
)

/** One of the two error slots on the 58d3 characteristic. */
/** [slot] is 0 for the first error field in the frame and 1 for the second; see showToUser. */
data class Bric4ErrorReport(
    val code: Int,
    val error: Bric4Error,
    val data1: Float,
    val data2: Float,
    val slot: Int,
)

/** The 58d3 characteristic: up to two error slots, of which only non-zero codes count. */
data class Bric4Errors(val reports: List<Bric4ErrorReport>) {
    val hasErrors: Boolean get() = reports.isNotEmpty()
}

/**
 * BRIC4/BRIC5 packet decoding. Ported from `comms/bric4/Bric4Manager`.
 *
 * Everything is little-endian, and distances and angles are IEEE-754 floats in their final units
 * (metres and degrees) — no fixed-point scaling at all, unlike the DistoX family.
 */
object Bric4Protocol {

    const val PRIMARY_PACKET_SIZE = 20
    const val METADATA_PACKET_SIZE = 19
    const val ERRORS_PACKET_SIZE = 18

    /** A leg of all zeros, standing in for `Leg.EMPTY_LEG` when a reading is unusable. */
    val EMPTY_LEG = Leg(0f, 0f, 0f)

    /**
     * The 58d1 primary characteristic:
     * ```
     * 0-1   year          uint16
     * 2     month         uint8
     * 3     day           uint8
     * 4     hour          uint8
     * 5     minute        uint8
     * 6     second        uint8
     * 7     centisecond   uint8   (read but discarded by the Java)
     * 8-11  distance      float, metres
     * 12-15 azimuth       float, degrees
     * 16-19 inclination   float, degrees
     * ```
     * A reading the survey model rejects — azimuth outside 0..360, inclination outside +-90,
     * negative distance — becomes [EMPTY_LEG] with [Bric4Measurement.isLegal] false, matching the
     * Java's `catch (IllegalArgumentException)`. The comment there explains why nothing else
     * happens: a bad shot will also be flagged on the errors characteristic, which suppresses the
     * whole measurement.
     */
    fun parsePrimary(bytes: ByteArray): Bric4Measurement {
        val timestamp =
            DeviceTimestamp(
                year = bytes.int16LE(0),
                month = bytes.uint8(2),
                day = bytes.uint8(3),
                hour = bytes.uint8(4),
                minute = bytes.uint8(5),
                second = bytes.uint8(6),
                centisecond = bytes.uint8(7),
            )

        val distance = bytes.floatLE(8)
        val azimuth = bytes.floatLE(12)
        val inclination = bytes.floatLE(16)

        val leg =
            try {
                Leg(distance, azimuth, inclination)
            } catch (exception: IllegalArgumentException) {
                null
            }

        return Bric4Measurement(
            timestamp = timestamp,
            leg = leg ?: EMPTY_LEG,
            rawDistance = distance,
            rawAzimuth = azimuth,
            rawInclination = inclination,
            isLegal = leg != null,
        )
    }

    /**
     * The 58d2 metadata characteristic:
     * ```
     * 0-3   reference     int32 (SexyTopo renders it as a signed decimal)
     * 4-7   dip           float   (unused)
     * 8-11  roll          float   (unused)
     * 12-15 temperature   float, Celsius (unused)
     * 16-17 samplesMean   int16   (unused)
     * 18    measurement type uint8 (unused)
     * ```
     * Everything but the reference is commented out in the Java; it is decoded here because it
     * costs nothing and the fields are documented in the BRIC protocol.
     */
    fun parseMetadata(bytes: ByteArray): Bric4Metadata =
        Bric4Metadata(
            reference = bytes.int32LE(0),
            dip = bytes.floatLE(4),
            roll = bytes.floatLE(8),
            temperatureCelsius = bytes.floatLE(12),
            samplesMean = bytes.int16LE(16),
            measurementType = bytes.uint8(18),
        )

    /**
     * The 58d3 errors characteristic: two slots of `uint8 code, float data1, float data2`, at
     * offsets 0 and 9. A code of zero means "no error in this slot".
     */
    fun parseErrors(bytes: ByteArray): Bric4Errors {
        val reports = mutableListOf<Bric4ErrorReport>()
        val firstCode = bytes.uint8(0)
        if (firstCode > 0) {
            reports += Bric4ErrorReport(
                firstCode,
                Bric4Error.fromCode(firstCode),
                bytes.floatLE(1),
                bytes.floatLE(5),
                slot = 0,
            )
        }
        val secondCode = bytes.uint8(9)
        if (secondCode > 0) {
            reports += Bric4ErrorReport(
                secondCode,
                Bric4Error.fromCode(secondCode),
                bytes.floatLE(10),
                bytes.floatLE(14),
                slot = 1,
            )
        }
        return Bric4Errors(reports)
    }
}

/**
 * Reassembles a BRIC shot from its three notifications.
 *
 * The BRIC sends each shot as three indications on three different characteristics, in a fixed
 * order, and — as the Java comment says — "there doesn't seem to be any way to figure out which
 * characteristic we are currently receiving, so we just cycle between them". So this decoder
 * counts rather than inspects, and one dropped indication desynchronises it until the connection
 * is remade. That risk is inherited, not introduced.
 *
 * The shot is only emitted on the third notification, and only if no errors were reported: a shot
 * the instrument is unhappy with never reaches the survey.
 */
class Bric4Decoder {

    private enum class State { MEASUREMENT, METADATA, ERRORS }

    private var state = State.MEASUREMENT

    /**
     * Held across notifications, exactly as the Java holds `current` and `currentRef` — including
     * their initial values, so a desynchronised decoder that reaches the errors slot before it has
     * ever seen a measurement submits a zero leg referenced "?" rather than nothing at all.
     */
    private var currentMeasurement: Bric4Measurement = NO_MEASUREMENT
    private var currentReference: String = UNKNOWN_REFERENCE

    /** Which of the three notifications is expected next, for diagnostics. */
    val expecting: String get() = state.name

    /**
     * Feeds one indication and returns whatever it completed — a [InstrumentPacket.Measurement]
     * for a clean shot, one or two [InstrumentPacket.DeviceFailure]s for a rejected one, or
     * nothing for the first two notifications of a shot.
     *
     * A null [bytes] models the Java's `data.getValue() == null` guard, which returns *before*
     * advancing the state machine — so a null frame is not merely skipped, it holds the cycle in
     * place. That is very likely a bug in the original (a genuinely empty indication would still
     * consume one of the three roles on the device side), but it is reproduced here rather than
     * quietly fixed.
     */
    fun feed(bytes: ByteArray?): List<InstrumentPacket> {
        if (bytes == null) return emptyList()
        val result = handle(state, bytes)
        state = when (state) {
            State.MEASUREMENT -> State.METADATA
            State.METADATA -> State.ERRORS
            State.ERRORS -> State.MEASUREMENT
        }
        return result
    }

    /**
     * Feeds one indication whose *role is known*, which is the whole reason [FrameChannel] carries
     * BRIC's three characteristics separately.
     *
     * Android cannot do this — `Bric4Manager`'s own comment says there is no way to tell which
     * characteristic an indication came from, so it cycles blindly and can desynchronise if one is
     * dropped. CoreBluetooth reports the characteristic on every callback, so a transport that maps
     * the profile's [FrameChannel]s can call this instead and the failure mode disappears.
     *
     * [FrameChannel.DEFAULT] means the transport could not tell them apart, so it falls back to the
     * blind cycle.
     */
    fun feed(channel: FrameChannel, bytes: ByteArray?): List<InstrumentPacket> {
        if (bytes == null) return emptyList()
        return when (channel) {
            FrameChannel.PRIMARY -> handle(State.MEASUREMENT, bytes)
            FrameChannel.EXTENDED -> handle(State.METADATA, bytes)
            FrameChannel.TERTIARY -> handle(State.ERRORS, bytes)
            FrameChannel.DEFAULT -> feed(bytes)
        }
    }

    private fun handle(state: State, bytes: ByteArray): List<InstrumentPacket> {
        return when (state) {
                State.MEASUREMENT -> {
                    currentMeasurement = Bric4Protocol.parsePrimary(bytes)
                    measurementPending = true
                    emptyList()
                }

                State.METADATA -> {
                    currentReference = Bric4Protocol.parseMetadata(bytes).reference.toString()
                    emptyList()
                }

                State.ERRORS -> {
                    val errors = Bric4Protocol.parseErrors(bytes)
                    if (errors.hasErrors) {
                        // The Java binds "show this to the surveyor" to WHICH SLOT the error came
                        // from, not to its position in the list: slot 1 is toasted, slot 2 is only
                        // logged. An errors frame carrying a code only in slot 2 must therefore
                        // stay silent, which indexing the filtered list would get wrong.
                        errors.reports.map { report ->
                            InstrumentPacket.DeviceFailure(
                                code = report.code,
                                description = report.error.description,
                                data1 = report.data1,
                                data2 = report.data2,
                                showToUser = report.slot == 0,
                            )
                        }
                    } else if (!measurementPending) {
                        // An all-clear with nothing to clear. See [measurementPending]: this is
                        // the frame that would otherwise invent a leg.
                        emptyList()
                    } else {
                        measurementPending = false
                        listOf(
                            InstrumentPacket.Measurement(
                                currentMeasurement.leg,
                                ShotDetail(
                                    reference = currentReference,
                                    timestamp = currentMeasurement.timestamp,
                                ),
                            ),
                        )
                    }
                }
        }
    }

    /**
     * Whether a measurement frame has arrived that no measurement packet has been emitted for yet.
     *
     * Without this the errors characteristic alone decides whether a shot happened: an all-zero
     * errors frame means "that one was fine", and this decoder answered it by emitting whatever
     * was last in [currentMeasurement]. Two ways that goes wrong, and both are ordinary events on
     * a real link rather than hypotheticals:
     *
     *  - **Nothing measured yet.** [NO_MEASUREMENT] is a leg of all zeros, and a zero distance is
     *    a *legal* [Leg] — so a stray all-clear before the first shot records a 0.00 m splay at
     *    the surveyor's feet. It is a wall measurement that was never taken, in a real survey,
     *    indistinguishable afterwards from one that was.
     *  - **The same shot twice.** A repeated all-clear re-emitted the previous measurement. With
     *    the triple-shot promotion rule that is worse than a stray splay: one real shot plus two
     *    fabricated repeats of itself *agree perfectly*, so they promote to a station that nothing
     *    ever cross-checked. The whole point of shooting three times is defeated by the app.
     *
     * So an all-clear only produces a measurement when there is one waiting. The Android app has
     * the same hole and reaches it differently — it cannot tell the three characteristics apart at
     * all and cycles blindly, so a dropped indication desynchronises the cycle instead.
     */
    private var measurementPending = false

    /** Puts the cycle back to the start; use after reconnecting. */
    fun reset() {
        state = State.MEASUREMENT
        currentMeasurement = NO_MEASUREMENT
        currentReference = UNKNOWN_REFERENCE
        measurementPending = false
    }

    companion object {
        private const val UNKNOWN_REFERENCE = "?"

        private val NO_MEASUREMENT =
            Bric4Measurement(
                timestamp = DeviceTimestamp(0, 0, 0, 0, 0, 0),
                leg = Bric4Protocol.EMPTY_LEG,
                rawDistance = 0f,
                rawAzimuth = 0f,
                rawInclination = 0f,
                isLegal = false,
            )
    }
}
