package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.bric.Bric4Decoder
import org.hwyl.sexytopo.shared.comms.cavway.CavwayX1Protocol
import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXCalibrationDecoder
import org.hwyl.sexytopo.shared.comms.distox.DistoXBlePackets
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.comms.fcl.FclDecodeResult
import org.hwyl.sexytopo.shared.comms.fcl.FclDecoder
import org.hwyl.sexytopo.shared.comms.sap6.Sap6Protocol

/**
 * What to do with one frame from an instrument: what it means, and what to write back.
 *
 * The missing middle of this port. Every protocol here was translated and tested byte for byte
 * from the Android drivers, and every one of them was unreachable, because each `*Manager` in the
 * app fuses three jobs — Bluetooth, decoding, and deciding when to acknowledge — and only the
 * middle one had been pulled out. A transport that has a frame and a profile still had no way to
 * ask "is this a measurement, and does the instrument expect a reply".
 *
 * Acknowledgement is the part that is easy to miss and impossible to diagnose in a cave. Four of
 * these instruments will not send the next shot until the last one is acknowledged, so a decoder
 * that quietly skipped the write would take exactly one reading and then look like a flat battery.
 *
 * Stateful, because BRIC needs to be: its three characteristics are one logical stream and its
 * decoder assembles across them. One decoder per connection, discarded with it.
 */
abstract class InstrumentDecoder {

    /**
     * Which driver this is, for the connection log and for tests.
     *
     * A surveyor who can see "DistoX-BLE driver" in the log knows the app recognised what it is
     * talking to, which is the first thing to check when readings are not arriving.
     */
    abstract val driverName: String

    /** Everything the frame contained. Usually zero or one packets; BRIC can emit several. */
    abstract fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket>

    /**
     * What to write back, or null if this instrument does not expect anything.
     *
     * Called with the same frame [decode] was given, whether or not it decoded to anything: an
     * instrument waiting for an acknowledgement is waiting whether or not this port understood
     * what it sent.
     */
    open fun acknowledgementFor(channel: FrameChannel, bytes: ByteArray): ByteArray? = null

    /** Forget any part-assembled state. Called when a link drops. */
    open fun reset() = Unit

    /**
     * Whether the instrument has been put into calibration mode.
     *
     * The Android app models this by swapping protocol objects — `MeasurementProtocol` for shots,
     * `CalibrationProtocol` for calibration readings — because on the classic DistoX the *same*
     * packet types mean different things in the two modes, and nothing in a frame says which mode
     * the device is in. Setting this is the equivalent of that swap, and it discards any
     * part-assembled state, since a half-read shot is not the first half of a calibration reading.
     *
     * Most drivers ignore it: DistoX-BLE, Cavway, BRIC, SAP6 and FCL all tag their frames, so they
     * can tell a calibration reading from a shot without being told.
     */
    var calibrating: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                reset()
            }
        }

    /** Which family this driver speaks for, so a caller can ask what commands it accepts. */
    abstract val family: InstrumentFamily

    /**
     * The bytes that carry [command] to this instrument, or null if it has no such command.
     *
     * The command byte itself is the same everywhere — the DistoX defined the vocabulary and the
     * clones adopted it — but the wrapping is not, and that is the whole reason this lives on the
     * decoder rather than in a constant. The classic DistoX writes the bare byte to an RFCOMM
     * socket, SAP6 and FCL write it as a single-octet GATT value (`CaveBLE.sendCommand`), and
     * DistoX-BLE and Cavway wrap it in a `data:` frame — see the override.
     *
     * Null rather than a byte for an unsupported command, so a screen can grey out a button
     * instead of writing something the instrument will ignore. FCL, for instance, has no
     * calibration commands at all.
     */
    open fun encodeCommand(command: InstrumentCommand): ByteArray? =
        if (command.supportedBy(family)) byteArrayOf(command.byte) else null

    companion object {
        /**
         * The decoder for an instrument, by profile.
         *
         * Matched on the profile's own name prefix rather than on identity, so [InstrumentProfile]
         * copies — `BRIC5` is `BRIC4.copy`, `DiscoX` is `SAP6.copy` — resolve to the driver the
         * Android app uses for them, which is the same one.
         */
        fun forProfile(profile: InstrumentProfile): InstrumentDecoder =
            when {
                profile.namePrefix.startsWith("DistoXBLE", ignoreCase = true) -> DistoXBleDecoder()
                profile.namePrefix.startsWith("CavwayX1", ignoreCase = true) -> CavwayDecoder()
                profile.namePrefix.startsWith("BRIC", ignoreCase = true) -> BricDecoder()
                profile.namePrefix.startsWith("SAP6", ignoreCase = true) -> Sap6Decoder()
                profile.namePrefix.startsWith("DiscoX", ignoreCase = true) -> Sap6Decoder()
                profile.namePrefix.startsWith("FCL", ignoreCase = true) -> FclDecoderAdapter()
                else -> UnknownDecoder
            }

        /**
         * The original DistoX and DistoX2, which speak the classic protocol over RFCOMM.
         *
         * Not reachable by [forProfile], and deliberately: [InstrumentProfile] describes BLE
         * devices, and no phone this port runs on can open an RFCOMM socket — iOS has no public
         * API for Bluetooth Classic at all, and neither has any browser. It is here because
         * [org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument] emits genuine classic packets,
         * so the simulated instrument and a real one now decode through exactly the same layer
         * rather than through two code paths that can drift.
         */
        fun classicDistoX(): InstrumentDecoder = ClassicDistoXDecoder()

        /** [InstrumentDecoder.driverName] for a profile this port has no driver for. */
        const val UNKNOWN_DRIVER = "unknown"
    }
}

/** The classic DistoX protocol: bare packets, and the same 0x55/0xD5 acknowledgement. */
private class ClassicDistoXDecoder : InstrumentDecoder() {

    override val family = InstrumentFamily.DISTOX

    override val driverName = "DistoX"

    /**
     * The two halves of a calibration reading, paired.
     *
     * `DistoXCalibrationDecoder` was ported with the rest of the protocol and had no caller: this
     * decoder only ever looked for measurement packets, so a classic DistoX in calibration mode
     * decoded to nothing at all. The BLE devices do not need it — they tag their frames and send
     * both halves in one notification — which is exactly why the gap was invisible.
     */
    private val calibrationDecoder = DistoXCalibrationDecoder()

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
        when {
            bytes.isEmpty() -> emptyList()

            // In calibration mode the device sends acceleration/magnetic pairs and no shots. The
            // decoder holds the first half until the second arrives, and treats a repeat of the
            // half it already has as a lost acknowledgement rather than an error.
            //
            // Its `disconnect` signal — five consecutive duplicates, where the Java closes the
            // streams — is not acted on here; the link stays up and the readings simply stop
            // arriving, which the calibration screen shows as a count that has stopped moving.
            calibrating -> listOfNotNull(calibrationDecoder.receive(bytes).packet)

            DistoXProtocol.isDataPacket(bytes) ->
                listOf(InstrumentPacket.Measurement(DistoXProtocol.parseMeasurement(bytes)))

            else -> emptyList()
        }

    override fun acknowledgementFor(channel: FrameChannel, bytes: ByteArray): ByteArray? =
        if (bytes.isEmpty()) null else DistoXProtocol.createAcknowledgementPacket(bytes)

    override fun reset() = calibrationDecoder.reset()
}

/**
 * DistoX-BLE: inbound frames carry a one-byte identifier and then a bare DistoX packet.
 *
 * Note the asymmetry, which the profile's own note records: the `data:` framing
 * ([DistoXBleFraming]) is applied on the way *out* only. Acknowledging therefore means wrapping,
 * while decoding means unwrapping one byte.
 */
private class DistoXBleDecoder : InstrumentDecoder() {

    override val family = InstrumentFamily.DISTOX_BLE

    /**
     * `DistoXBleManager.createWriteCommandPacket`: the command byte inside a `data:` frame rather
     * than written raw, because this device's write characteristic carries framed packets.
     */
    override fun encodeCommand(command: InstrumentCommand): ByteArray? =
        if (command.supportedBy(family)) {
            DistoXBleFraming.createWriteCommandPacket(command.byte)
        } else {
            null
        }


    override val driverName = "DistoX-BLE"

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
        listOfNotNull(DistoXBlePackets.decode(bytes))

    override fun acknowledgementFor(channel: FrameChannel, bytes: ByteArray): ByteArray? =
        if (bytes.size > DistoXBlePackets.EMBEDDED_ADMIN &&
            bytes[0] == DistoXBlePackets.MEASUREMENT_IDENTIFIER
        ) {
            DistoXBlePackets.acknowledgementFor(bytes)
        } else {
            null
        }
}

/** Cavway X1: the same transport as DistoX-BLE and a protocol of its own. */
private class CavwayDecoder : InstrumentDecoder() {

    override val family = InstrumentFamily.CAVWAY_X1

    /**
     * `DistoXBleManager.createWriteCommandPacket`: the command byte inside a `data:` frame rather
     * than written raw, because this device's write characteristic carries framed packets.
     */
    override fun encodeCommand(command: InstrumentCommand): ByteArray? =
        if (command.supportedBy(family)) {
            DistoXBleFraming.createWriteCommandPacket(command.byte)
        } else {
            null
        }


    override val driverName = "Cavway X1"

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
        listOfNotNull(CavwayX1Protocol.decode(bytes))

    override fun acknowledgementFor(channel: FrameChannel, bytes: ByteArray): ByteArray? =
        if (bytes.size > CavwayX1Protocol.FLAGS_INDEX) {
            DistoXBleFraming.createWriteCommandPacket(
                CavwayX1Protocol.acknowledgementByte(bytes[CavwayX1Protocol.FLAGS_INDEX]),
            )
        } else {
            null
        }
}

/**
 * BRIC4 and BRIC5: three notify characteristics that are one logical stream.
 *
 * The channel matters here and nowhere else. Android cannot tell BRIC's three indications apart,
 * so `Bric4Manager` cycles blindly through the roles and its own comment admits the desync risk;
 * CoreBluetooth and Web Bluetooth both report which characteristic fired, so passing the channel
 * through means this port simply does not have that bug.
 */
private class BricDecoder : InstrumentDecoder() {

    override val family = InstrumentFamily.BRIC4

    override val driverName = "BRIC"

    private val decoder = Bric4Decoder()

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
        // Short frames are dropped rather than fed: the parsers read fixed offsets, and a
        // truncated indication on a marginal link would otherwise index past the end.
        if (bytes.size < MINIMUM_FRAME) emptyList() else decoder.feed(channel, bytes)

    override fun reset() = decoder.reset()

    private companion object {
        /** Every BRIC parser reads within the first 20 bytes of its indication. */
        const val MINIMUM_FRAME = 20
    }
}

/**
 * SAP6 and DiscoX, which share a driver in the Android app and so share one here.
 *
 * The length is checked rather than the exception caught. That is not style: an out-of-bounds read
 * is an `IllegalArgumentException` on the JVM and something a `runCatching` does not always get
 * hold of on Kotlin/Wasm — and Kotlin/Native, which is the iOS build, is the same family. A
 * truncated BLE notification is an ordinary event on a marginal link, and it must not be able to
 * take the app down in a cave.
 */
private class Sap6Decoder : InstrumentDecoder() {

    override val family = InstrumentFamily.SAP6

    override val driverName = "SAP6"

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
        if (bytes.size < Sap6Protocol.PACKET_SIZE) {
            emptyList()
        } else {
            listOfNotNull(Sap6Protocol.decodeToPacket(bytes))
        }

    override fun acknowledgementFor(channel: FrameChannel, bytes: ByteArray): ByteArray? =
        if (bytes.size < Sap6Protocol.PACKET_SIZE) null else Sap6Protocol.acknowledgementFor(bytes)
}

/**
 * FCL: genuinely two inbound streams, told apart by characteristic, which is why channels exist.
 *
 * A shot is only complete — and only acknowledged — once both halves have arrived, so the frame is
 * fed once and both the packet and the acknowledgement come out of that one call. Feeding it again
 * to work out the reply would consume the held primary a second time and cost the shot, which is
 * why this decoder holds its last result rather than re-running.
 */
private class FclDecoderAdapter : InstrumentDecoder() {

    override val family = InstrumentFamily.FCL

    override val driverName = "FCL"

    private val decoder = FclDecoder()
    private var lastAcknowledgement: ByteArray? = null

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> {
        lastAcknowledgement = null
        return when (val result = decoder.feed(channel, bytes)) {
            is FclDecodeResult.Complete -> {
                lastAcknowledgement = result.acknowledgement
                result.leg.toLegOrNull()
                    ?.let { listOf(InstrumentPacket.Measurement(it)) }
                    ?: emptyList()
            }
            // A held primary waiting for its extended half, or a frame that made no sense. The
            // Java logs and moves on in both cases, and an unacknowledged shot is resent by the
            // instrument, which is the behaviour that recovers it.
            else -> emptyList()
        }
    }

    override fun acknowledgementFor(channel: FrameChannel, bytes: ByteArray): ByteArray? =
        lastAcknowledgement

    override fun reset() {
        lastAcknowledgement = null
        decoder.reset()
    }
}

/** A profile with no driver: frames are surfaced as-is rather than silently dropped. */
private object UnknownDecoder : InstrumentDecoder() {

    override val family = InstrumentFamily.DISTOX

    override val driverName = UNKNOWN_DRIVER

    override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
        listOf(InstrumentPacket.Unrecognised(bytes))
}

/** Every [Leg] in a batch of packets, which is what the survey engine wants. */
fun List<InstrumentPacket>.measurements(): List<org.hwyl.sexytopo.shared.model.survey.Leg> =
    filterIsInstance<InstrumentPacket.Measurement>().map { it.leg }
