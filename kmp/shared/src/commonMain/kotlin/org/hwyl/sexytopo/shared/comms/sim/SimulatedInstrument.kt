package org.hwyl.sexytopo.shared.comms.sim

import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentCommand
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * A DistoX that lives entirely in software: it replays a scripted list of shots as real
 * eight-byte DistoX packets, so everything downstream runs exactly the code that runs against
 * real hardware.
 *
 * Deliberately, boringly deterministic: no clocks, no randomness, no threads.
 *
 * Commands are understood both bare (classic DistoX over RFCOMM) and wrapped in the `data:` frame
 * DistoX BLE uses. Outbound packets are always raw eight-byte DistoX packets; wrap them with
 * [DistoXBleFraming] if a BLE-shaped stream is wanted.
 */
class SimulatedInstrument(
    val script: List<Leg> = demoScript(),
    val loop: Boolean = false,
) : BaseInstrumentTransport() {

    private var connected = false
    private var nextShotIndex = 0
    private var sequenceBit = false

    private val sentCommands = mutableListOf<Byte>()

    val commandsReceived: List<Byte> get() = sentCommands.toList()

    var isLaserOn: Boolean = false
        private set

    var isCalibrating: Boolean = false
        private set

    val shotsEmitted: Int get() = nextShotIndex

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

    /** Returns false when the script is exhausted and [loop] is off. */
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

    /** Acceleration first, magnetic second, as raw sensor counts. */
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

    fun repeatLastShot(): Boolean {
        if (nextShotIndex == 0) return false
        val leg = script[(nextShotIndex - 1) % script.size]
        // The sequence bit is deliberately *not* toggled: a retransmission carries the same bit.
        emitFrame(DistoXProtocol.encodeMeasurement(leg, !sequenceBit))
        return true
    }

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

        fun demoScript(): List<Leg> = listOf(
            Leg(5.42f, 12.5f, -3.0f),
            Leg(8.13f, 15.0f, 1.5f),
            Leg(3.97f, 88.0f, -12.0f),
            Leg(12.60f, 91.5f, 0.0f),
            Leg(6.04f, 175.0f, 22.5f),
        )
    }
}
