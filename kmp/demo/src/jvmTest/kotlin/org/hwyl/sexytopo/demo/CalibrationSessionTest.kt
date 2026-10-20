package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationPositions
import org.hwyl.sexytopo.shared.calibration.CalibrationRun
import org.hwyl.sexytopo.shared.calibration.CalibrationWrite
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentCommand
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.LinkState
import org.hwyl.sexytopo.shared.comms.TransportSubscription
import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Calibrating, driven end to end against the simulated instrument.
 *
 * The last of the app's big screens to be reachable, and the one whose absence mattered most: an
 * uncalibrated DistoX can be several degrees out, a survey is a chain of bearings, and the error
 * accumulates along the passage. The cave comes back the wrong shape and nothing in the numbers
 * says so.
 *
 * Everything below this was ported and tested long before anything could reach it. What these
 * cover is the part that was missing — the command going out, the two frames of each shot being
 * paired into a reading, calibration readings being kept out of the survey, and the coefficients
 * going back.
 */
class CalibrationSessionTest {

    private fun session(): SurveySession {
        val session = SurveySession(Survey("Cal"))
        session.connect()
        return session
    }

    /** Starting sends the command the instrument is waiting for, not just a flag. */
    @Test
    fun startingSendsTheCommandAndStoppingSendsTheOther() {
        val session = session()

        assertTrue(session.startCalibration())
        assertTrue(session.calibrating)
        assertTrue(session.simulator.isCalibrating, "the instrument was never told")

        session.stopCalibration()
        assertFalse(session.calibrating)
        assertFalse(session.simulator.isCalibrating)
    }

    /** The two frames of a shot become one reading, in the order the instrument sends them. */
    @Test
    fun eachShotArrivesAsTwoFramesAndBecomesOneReading() {
        val session = session()
        session.startCalibration()

        repeat(3) { assertTrue(session.simulateCalibrationReading()) }

        assertEquals(3, session.calibration.count)
        assertEquals(ExampleCalibration.READINGS[2], session.calibration.last)
    }

    /**
     * A calibration reading must never reach the survey.
     *
     * They are raw sensor counts, not a shot; letting one through would put a leg of nonsense
     * length and bearing into the cave, and the surveyor is not looking at the plan while
     * calibrating.
     */
    @Test
    fun calibrationReadingsNeverBecomeSurveyLegs() {
        val session = session()
        session.startCalibration()

        repeat(10) { session.simulateCalibrationReading() }

        assertEquals(10, session.calibration.count)
        assertEquals(0, session.readingsTaken)
        assertEquals(1, session.survey.getAllStations().size, "only the origin should exist")
    }

    /** The checklist advances as shots arrive, so the screen always says what to do next. */
    @Test
    fun theNextPositionFollowsTheReadings() {
        val session = session()
        session.startCalibration()

        assertEquals(CalibrationPositions.ALL[0], session.calibration.next)
        session.simulateCalibrationReading()
        assertEquals(CalibrationPositions.ALL[1], session.calibration.next)
    }

    @Test
    fun awholeCalibrationEndsWithCoefficientsOnTheInstrument() {
        val session = session()
        session.startCalibration()

        while (session.simulateCalibrationReading()) {
            // the 56 readings of a real calibration
        }

        assertTrue(session.calibration.isComplete)
        val result = session.calibration.solve()

        val before = session.simulator.commandsReceived.size
        val write = session.writeCalibration(result)

        assertEquals(13, write.commands.size)
        // The simulator speaks the classic protocol, whose per-block replies this port cannot
        // receive, so it is sent and never said to have worked.
        assertEquals(CalibrationWrite.Status.SENT, session.calibrationWriteStatus)
        assertTrue(
            session.simulator.commandsReceived.size > before,
            "the coefficients never left the phone",
        )
    }

    /** Undo and clear reach the instrument's readings, not just the screen. */
    @Test
    fun aShotCanBeUndoneAndTheRunCleared() {
        val session = session()
        session.startCalibration()
        repeat(4) { session.simulateCalibrationReading() }

        session.deleteLastCalibrationReading()
        assertEquals(3, session.calibration.count)

        session.clearCalibration()
        assertEquals(0, session.calibration.count)
    }

    /**
     * The simulate button is for the simulator.
     *
     * With a real instrument attached it does nothing, because a button that invented readings
     * while a DistoX was connected would be putting somebody else's calibration on their device.
     */
    @Test
    fun theSimulatedShotButtonDoesNothingWithARealInstrumentAttached() {
        val session = session()
        session.attachForTest(SimulatedInstrument(), InstrumentDecoder.classicDistoX())
        session.connect()
        session.startCalibration()

        // A different transport from `session.simulator`, so this is the "not the demo" path.
        assertFalse(session.simulateCalibrationReading())
        assertEquals(0, session.calibration.count)
    }

    /**
     * A recording transport, so a test can inspect the exact bytes `writeCalibration` put on the
     * wire rather than only how many chunks it split them into - `session.simulator.send` unwraps
     * BLE framing and re-decodes each byte as a single-byte instrument command, which is the right
     * thing for the commands it is built to test and the wrong thing for reading back a memory
     * write's own bytes.
     */
    private class RecordingTransport : InstrumentTransport {
        val sent = mutableListOf<ByteArray>()
        private var listener: InstrumentTransportListener? = null
        override val isConnected = true
        override val linkState = LinkState.CONNECTED
        override fun connect() {}
        override fun disconnect() {}
        override fun send(bytes: ByteArray) { sent += bytes }
        override fun observe(listener: InstrumentTransportListener): TransportSubscription {
            this.listener = listener
            return TransportSubscription { this.listener = null }
        }

        /** As the instrument would: a notification arriving. */
        fun receive(bytes: ByteArray) = listener?.onFrame(FrameChannel.DEFAULT, bytes)

        fun fail(reason: String) = listener?.onFailure(reason)
    }

    private fun bleSession(): Pair<SurveySession, RecordingTransport> {
        val session = session()
        val transport = RecordingTransport()
        session.attachForTest(
            transport,
            InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE),
            InstrumentProfile.DISTOX_BLE,
        )
        return session to transport
    }

    private fun exampleFit() =
        CalibrationRun().apply { ExampleCalibration.READINGS.forEach(::add) }.solve()

    /** The DistoX-BLE's 0x3d reply to a memory write at 0x8010. */
    private fun memoryReply(stored: ByteArray) =
        byteArrayOf(0x3d, 0x10, 0x80.toByte(), stored.size.toByte()) + stored

    /**
     * f8095628: a DistoX-BLE echoes what it stored, and only a matching echo is success. Until
     * then the upload is waiting, rather than reported as done the moment it left the phone.
     */
    @Test
    fun aBleUploadIsConfirmedByTheInstrumentsEcho() {
        val (session, transport) = bleSession()
        val result = exampleFit()

        val write = session.writeCalibration(result)
        assertEquals(CalibrationWrite.Status.AWAITING_CONFIRMATION, session.calibrationWriteStatus)

        transport.receive(memoryReply(result.toBytes()))

        assertEquals(CalibrationWrite.Status.CONFIRMED, write.status)
        assertEquals(CalibrationWrite.Status.CONFIRMED, session.calibrationWriteStatus)
        assertTrue(Strings.deviceDistoxCalibrationWriteSuccess in session.log.first())
        assertTrue(session.log.none { it.startsWith("Unknown packet") }, "the reply was not understood")
    }

    @Test
    fun aBleUploadEchoedWrongIsRejected() {
        val (session, transport) = bleSession()
        val result = exampleFit()
        session.writeCalibration(result)

        transport.receive(memoryReply(ByteArray(52)))

        assertEquals(CalibrationWrite.Status.REJECTED, session.calibrationWriteStatus)
        assertEquals(Strings.deviceDistoxCalibrationWriteRejected, session.log.first())
    }

    /** Upstream fails an upload whose Bluetooth write fails; here that arrives as a failure. */
    @Test
    fun aBleUploadThatFailsOnTheWayIsReported() {
        val (session, transport) = bleSession()
        session.writeCalibration(exampleFit())

        transport.fail("write failed")

        assertEquals(CalibrationWrite.Status.FAILED, session.calibrationWriteStatus)
        assertTrue(session.log.any { it.startsWith("Error writing calibration") })
    }

    /** 9fa47c3d: a packet nobody understood goes in the log as its length and its bytes. */
    @Test
    fun anUnknownPacketIsLoggedWithItsBytes() {
        val (session, transport) = bleSession()

        transport.receive(byteArrayOf(0x7f, 0x01, 0xab.toByte()))

        assertEquals("Unknown packet (3 bytes): 7f 01 ab", session.log.first())
    }

    /** A DistoX-BLE calibration reading is acknowledged, as the Java acknowledges it. */
    @Test
    fun aBleCalibrationReadingIsAcknowledged() {
        val (session, transport) = bleSession()
        session.startCalibration()
        val before = transport.sent.size

        transport.receive(ByteArray(17).also { it[0] = 0x02 })

        assertEquals(1, session.calibration.count)
        assertEquals(before + 1, transport.sent.size, "the reading was never acknowledged")
    }

    /**
     * A replaced reading is retaken in its own place, and the simulator replays the dataset's own
     * reading for that place, so the run still ends in the fit it is known to produce.
     */
    @Test
    fun aReplacedReadingIsRetakenInPlace() {
        val session = session()
        session.startCalibration()
        while (session.simulateCalibrationReading()) {
            // the 56 readings of a real calibration
        }

        session.replaceCalibrationReading(10)
        assertFalse(session.calibration.isComplete)
        assertTrue(session.simulateCalibrationReading())

        assertTrue(session.calibration.isComplete)
        assertEquals(ExampleCalibration.READINGS, session.calibration.readings)
        assertEquals(43, session.calibration.solve().iterations)

        session.deleteCalibrationReading(0)
        assertEquals(55, session.calibration.count)
        assertEquals(ExampleCalibration.READINGS[1], session.calibration.readings.first())
    }

    /**
     * The actual bug: a DistoX-BLE instrument does not speak the classic per-four-byte protocol at
     * all, and sending it that shape means twelve packets its firmware has no reason to recognise
     * as a calibration write - nothing would reject them, so the app reported success while the
     * instrument's coefficients never changed. This exercises the whole path `writeCalibration`
     * takes, not just `CalibrationRun.writeCommands` in isolation, so a future change to how the
     * decoder's family reaches that call cannot silently reintroduce the bug at the seam.
     */
    @Test
    fun aBleInstrumentGetsOneFramedCalibrationWrite() {
        val session = session()
        val transport = RecordingTransport()
        session.attachForTest(
            transport,
            InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE),
            InstrumentProfile.DISTOX_BLE,
        )
        // A different transport is attached, so simulateCalibrationReading would refuse - this
        // is the "a real calibration screen already has a solved result to write" case, not the
        // "walk 56 shots through the simulator" one those other tests cover.
        val result = exampleFit()

        val blocks = session.writeCalibration(result).commands.size

        assertEquals(1, blocks, "a BLE instrument should get one frame, not a dribble")
        assertEquals(1, transport.sent.size)
        assertTrue(
            DistoXBleFraming.payloadOrNull(transport.sent.single()) != null,
            "the single write should be a real data: frame, not raw bytes",
        )
    }

    /** FCL exposes no calibration commands at all, and the screen has to be able to say so. */
    @Test
    fun anInstrumentWithNoCalibrationCommandsSaysSo() {
        val fcl =
            InstrumentDecoder.forProfile(
                org.hwyl.sexytopo.shared.comms.InstrumentProfile.ALL.first {
                    it.namePrefix.startsWith("FCL")
                },
            )
        assertEquals(null, fcl.encodeCommand(InstrumentCommand.START_CALIBRATION))
        assertTrue(
            InstrumentDecoder.classicDistoX()
                .encodeCommand(InstrumentCommand.START_CALIBRATION)!!
                .isNotEmpty(),
        )
    }
}
