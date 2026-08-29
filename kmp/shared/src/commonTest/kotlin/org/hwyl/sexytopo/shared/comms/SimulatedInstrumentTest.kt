package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXCalibrationDecoder
import org.hwyl.sexytopo.shared.comms.distox.DistoXMeasurementDecoder
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The simulated instrument is the end-to-end check on this package: a scripted list of legs goes
 * in as real DistoX packets and has to come back out of the real decoder unchanged.
 */
class SimulatedInstrumentTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    /** Collects everything a transport emits, and decodes measurement frames on the way past. */
    private class Recorder(private val transport: InstrumentTransport) :
        InstrumentTransportListener {

        private val decoder = DistoXMeasurementDecoder()
        val legs = mutableListOf<Leg>()
        val frames = mutableListOf<ByteArray>()
        var connections = 0
        var disconnections = 0

        override fun onConnected() {
            connections++
        }

        override fun onDisconnected(reason: String?) {
            disconnections++
        }

        override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
            frames += bytes
            val result = decoder.receive(bytes)
            result.acknowledgement?.let { transport.send(it) }
            (result.packet as? InstrumentPacket.Measurement)?.let { legs += it.leg }
        }
    }

    private val script = listOf(
        Leg(5.42f, 12.5f, -3.0f),
        Leg(8.13f, 15.0f, 1.5f),
        Leg(3.97f, 88.0f, -12.0f),
    )

    @Test
    fun scriptedShotsSurviveTheWholeRoundTrip() {
        val instrument = SimulatedInstrument(script)
        val recorder = Recorder(instrument)
        instrument.observe(recorder)

        instrument.connect()
        repeat(script.size) { instrument.send(byteArrayOf(InstrumentCommand.TAKE_SHOT.byte)) }

        assertEquals(1, recorder.connections)
        assertEquals(script.size, recorder.legs.size)
        script.forEachIndexed { index, expected ->
            val actual = recorder.legs[index]
            assertClose(expected.distance, actual.distance, 0.001f)
            assertClose(expected.azimuth, actual.azimuth)
            assertClose(expected.inclination, actual.inclination)
        }
    }

    @Test
    fun theSequenceBitAlternatesSoAcknowledgementsAlternateToo() {
        val instrument = SimulatedInstrument(script)
        val recorder = Recorder(instrument)
        instrument.observe(recorder)
        instrument.connect()

        repeat(3) { instrument.emitNextShot() }

        // Each frame's acknowledgement was written straight back, interleaved with the 0x38s we
        // never sent — so the recorded commands are exactly the three acknowledgements.
        assertContentEquals(
            listOf(0x55.toByte(), 0xD5.toByte(), 0x55.toByte()),
            instrument.commandsReceived,
        )
    }

    @Test
    fun aRetransmissionIsSwallowedByTheDecoder() {
        val instrument = SimulatedInstrument(script)
        val recorder = Recorder(instrument)
        instrument.observe(recorder)
        instrument.connect()

        instrument.emitNextShot()
        assertTrue(instrument.repeatLastShot())

        assertEquals(2, recorder.frames.size, "both packets reached the listener")
        assertEquals(1, recorder.legs.size, "but only one shot was recorded")
    }

    @Test
    fun theScriptRunsOutUnlessLoopingIsAsked() {
        val once = SimulatedInstrument(script)
        repeat(script.size) { assertTrue(once.emitNextShot()) }
        assertFalse(once.emitNextShot())
        assertFalse(once.hasMoreShots)

        val looping = SimulatedInstrument(script, loop = true)
        repeat(script.size * 2 + 1) { assertTrue(looping.emitNextShot()) }
        assertTrue(looping.hasMoreShots)
    }

    @Test
    fun commandsAreUnderstoodBareOrDataFramed() {
        val instrument = SimulatedInstrument(script)
        instrument.connect()

        instrument.send(byteArrayOf(InstrumentCommand.LASER_ON.byte))
        assertTrue(instrument.isLaserOn)

        instrument.send(DistoXBleFraming.createWriteCommandPacket(InstrumentCommand.LASER_OFF.byte))
        assertFalse(instrument.isLaserOn)

        instrument.send(DistoXBleFraming.createWriteCommandPacket(InstrumentCommand.TAKE_SHOT.byte))
        assertEquals(1, instrument.shotsEmitted)

        instrument.send(byteArrayOf(InstrumentCommand.DEVICE_OFF.byte))
        assertFalse(instrument.isConnected)
    }

    @Test
    fun calibrationModeIsTracked() {
        val instrument = SimulatedInstrument(script)
        instrument.connect()
        assertFalse(instrument.isCalibrating)
        instrument.send(byteArrayOf(InstrumentCommand.START_CALIBRATION.byte))
        assertTrue(instrument.isCalibrating)
        instrument.send(byteArrayOf(InstrumentCommand.STOP_CALIBRATION.byte))
        assertFalse(instrument.isCalibrating)
    }

    @Test
    fun simulatedCalibrationReadingsPairUpInTheDecoder() {
        val instrument = SimulatedInstrument(emptyList())
        val decoder = DistoXCalibrationDecoder()
        val readings = mutableListOf<InstrumentPacket.CalibrationReading>()

        instrument.observe(
            object : InstrumentTransportListener {
                override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
                    (decoder.receive(bytes).packet as? InstrumentPacket.CalibrationReading)
                        ?.let { readings += it }
                }
            },
        )

        instrument.connect()
        instrument.emitCalibrationReading(
            InstrumentPacket.Acceleration(-102, -682, 24780),
            InstrumentPacket.Magnetic(7984, -1579, 16072),
        )

        assertEquals(
            listOf(InstrumentPacket.CalibrationReading(-102, -682, 24780, 7984, -1579, 16072)),
            readings,
        )
    }

    @Test
    fun aCancelledSubscriptionStopsReceiving() {
        val instrument = SimulatedInstrument(script)
        val recorder = Recorder(instrument)
        val subscription = instrument.observe(recorder)
        instrument.connect()

        instrument.emitNextShot()
        assertEquals(1, recorder.legs.size)

        subscription.cancel()
        instrument.emitNextShot()
        assertEquals(1, recorder.legs.size, "no frames after cancelling")
        subscription.cancel() // idempotent
    }

    @Test
    fun connectionEventsAreReportedOnce() {
        val instrument = SimulatedInstrument(script)
        val recorder = Recorder(instrument)
        instrument.observe(recorder)

        instrument.connect()
        instrument.connect()
        assertEquals(1, recorder.connections)

        instrument.disconnect()
        instrument.disconnect()
        assertEquals(1, recorder.disconnections)
    }

    @Test
    fun theDefaultDemoScriptDecodesCleanly() {
        val instrument = SimulatedInstrument()
        val recorder = Recorder(instrument)
        instrument.observe(recorder)
        instrument.connect()

        while (instrument.emitNextShot()) { /* replay the lot */ }

        assertEquals(SimulatedInstrument.demoScript().size, recorder.legs.size)
        assertClose(5.42f, recorder.legs.first().distance, 0.001f)
        assertClose(175.0f, recorder.legs.last().azimuth)
    }
}
