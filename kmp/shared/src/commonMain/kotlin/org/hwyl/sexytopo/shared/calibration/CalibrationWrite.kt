package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.distox.DistoXMemoryRange

/**
 * One upload of calibration coefficients to an instrument, which may finish later.
 *
 * Ported from `comms/distox/CalibrationWrite` and the DistoX-BLE's `BleCalibrationWrite`. A
 * DistoX-BLE answers the write with a memory reply echoing the bytes it stored, and the write only
 * counts as successful once that echo matches what was sent; until 1.14.0 nothing listened for it,
 * so the Android app never reported success, and this port reported it the moment the bytes left
 * the phone. The classic protocol's own per-block replies belong to an RFCOMM transport this port
 * cannot open, and nothing is known to answer a Cavway X1's write, so those are [Status.SENT] and
 * stay so.
 */
class CalibrationWrite(
    /** The coefficient block, as the instrument should hold it afterwards. */
    val coefficients: ByteArray,
    /** What goes on the wire, in order. */
    val commands: List<ByteArray>,
    /** Whether the instrument is expected to confirm the write. */
    awaitsConfirmation: Boolean,
) {

    enum class Status {
        /** Sent, and nothing will say whether it landed. */
        SENT,
        AWAITING_CONFIRMATION,

        /** `device_distox_calibration_write_success`. */
        CONFIRMED,

        /** The instrument stored something other than what was sent. */
        REJECTED,

        /** The link failed before the instrument answered. */
        FAILED,
    }

    var status: Status = if (awaitsConfirmation) Status.AWAITING_CONFIRMATION else Status.SENT
        private set

    val isFinished: Boolean
        get() = status != Status.AWAITING_CONFIRMATION

    /**
     * Settles the write from the instrument's reply. False if the reply is not this write's: it has
     * already been settled, or the reply is for some other address.
     */
    fun replyReceived(reply: InstrumentPacket.MemoryReply): Boolean {
        if (isFinished || reply.address != DistoXMemoryRange.CALIBRATION_COEFFICIENTS.start) {
            return false
        }
        status = if (coefficients.contentEquals(reply.stored)) Status.CONFIRMED else Status.REJECTED
        return true
    }

    /** The link failed while waiting. False if the write had already been settled. */
    fun linkFailed(): Boolean {
        if (isFinished) return false
        status = Status.FAILED
        return true
    }
}
