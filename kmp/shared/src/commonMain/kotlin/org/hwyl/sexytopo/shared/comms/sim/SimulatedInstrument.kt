package org.hwyl.sexytopo.shared.comms.sim

import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentCommand
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * A DistoX that lives entirely in software.
 *
 * It replays a scripted list of shots as real eight-byte DistoX packets, so everything downstream
 * — [org.hwyl.sexytopo.shared.comms.distox.DistoXMeasurementDecoder], acknowledgement handling,
 * duplicate suppression, the survey engine, the UI — runs exactly the code that runs against real
 * hardware. That makes it useful for three things at once: demos without a cave, deterministic
 * tests, and reproducing a reported bug from a captured shot list.
 *
 * It is deliberately, boringly deterministic: no clocks, no randomness, no threads. A shot is
 * emitted only when something asks for one, either by writing [InstrumentCommand.TAKE_SHOT] or by
 * calling [emitNextShot]. The sequence bit alternates from `false`, as a real device's does, so
 * the acknowledgements a client sends back alternate 0x55, 0xD5, 0x55...
 *
 * Commands are understood both bare (classic DistoX over RFCOMM) and wrapped in the `data:` frame
 * DistoX BLE uses, so the same simulator can stand in for either. Outbound packets are always raw
 * eight-byte DistoX packets; wrap them with [DistoXBleFraming] if a BLE-shaped stream is wanted.
 */
class SimulatedInstrument(
    /** The shots to replay, in order. */
    val script: List<Leg> = demoScript(),
    /** Whether to start again from the first shot once the script runs out. */
    val loop: Boolean = false,
) : BaseInstrumentTransport() {

    private var connected = false
    private var nextShotIndex = 0
    private var sequenceBit = false

    private val sentCommands = mutableListOf<Byte>()

    /** Every command byte written to this instrument, oldest first — handy in assertions. */
    val commandsReceived: List<Byte> get() = sentCommands.toList()

    /** Whether the laser has been asked to switch on. */
    var isLaserOn: Boolean = false
        private set

    /** Whether the instrument has been put into calibration mode. */
    var isCalibrating: Boolean = false
        private set

    /** How many shots of [script] have been replayed. */
    val shotsEmitted: Int get() = nextShotIndex

    /** Whether there is another shot to emit. */
    val hasMoreShots: Boolean
        get() = (loop && script.isNotEmpty()) || nextShotIndex < script.size

    override val isConnected: Boolean get() = connected

    override fun connect() {
        if (connected) return
        connected = true
        emitConnected()
    }

    override fun disconnect() {
        if (!connected) return
        connected = false
        emitDisconnected("simulated disconnect")
    }

    override fun send(bytes: ByteArray) {
        val payload = DistoXBleFraming.payloadOrNull(bytes) ?: bytes
        for (byte in payload) {
            sentCommands += byte
            handleCommandByte(byte)
        }
    }

    /**
     * Emits the next scripted shot, returning false when the script is exhausted and [loop] is
     * off. Public so a demo can step through shots on a button press.
     */
    fun emitNextShot(): Boolean {
        if (script.isEmpty()) return false
        if (nextShotIndex >= script.size) {
            if (!loop) return false
            nextShotIndex = 0
        }
        val leg = script[nextShotIndex]
        nextShotIndex++
        emitFrame(DistoXProtocol.encodeMeasurement(leg, sequenceBit))
        sequenceBit = !sequenceBit
        return true
    }

    /**
     * Emits one calibration reading as the two packets a real DistoX sends: acceleration first,
     * magnetic second. Values are raw sensor counts, not physical units.
     */
    fun emitCalibrationReading(
        acceleration: InstrumentPacket.Acceleration,
        magnetic: InstrumentPacket.Magnetic,
    ) {
        val (accelerationPacket, magneticPacket) =
            DistoXProtocol.encodeCalibration(acceleration, magnetic, sequenceBit)
        emitFrame(accelerationPacket)
        sequenceBit = !sequenceBit
        emitFrame(magneticPacket)
        sequenceBit = !sequenceBit
    }

    /**
     * Re-sends the packet a real device would resend when its acknowledgement goes astray. Useful
     * for exercising duplicate suppression: the decoder should swallow the repeat.
     */
    fun repeatLastShot(): Boolean {
        if (nextShotIndex == 0) return false
        val leg = script[(nextShotIndex - 1) % script.size]
        // The sequence bit is deliberately *not* toggled: a retransmission carries the same bit,
        // and the decoder compares the whole packet, not the bit.
        emitFrame(DistoXProtocol.encodeMeasurement(leg, !sequenceBit))
        return true
    }

    /** Rewinds the script and clears the recorded commands. */
    fun reset() {
        nextShotIndex = 0
        sequenceBit = false
        sentCommands.clear()
        isLaserOn = false
        isCalibrating = false
    }

    private fun handleCommandByte(byte: Byte) {
        when (InstrumentCommand.fromCode(byte.toInt() and 0xFF)) {
            InstrumentCommand.TAKE_SHOT -> emitNextShot()
            InstrumentCommand.LASER_ON -> isLaserOn = true
            InstrumentCommand.LASER_OFF -> isLaserOn = false
            InstrumentCommand.START_CALIBRATION -> isCalibrating = true
            InstrumentCommand.STOP_CALIBRATION -> isCalibrating = false
            InstrumentCommand.DEVICE_OFF -> disconnect()
            // Acknowledgements (0x55 / 0xD5) and silent-mode commands need no response.
            else -> Unit
        }
    }

    companion object {

        /**
         * A short passage with a side lead: five shots that make a recognisable shape when drawn,
         * with the fourth branching left. Distances are metres, azimuths degrees clockwise from
         * north, inclinations degrees above horizontal.
         */
        fun demoScript(): List<Leg> = listOf(
            Leg(5.42f, 12.5f, -3.0f),
            Leg(8.13f, 15.0f, 1.5f),
            Leg(3.97f, 88.0f, -12.0f),
            Leg(12.60f, 91.5f, 0.0f),
            Leg(6.04f, 175.0f, 22.5f),
        )
    }
}
