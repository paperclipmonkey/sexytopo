package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.TransportSubscription
import org.hwyl.sexytopo.shared.comms.fcl.FclProtocol
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The telemetry an FCL shot carries has somewhere to land once it survives the decoder (see
 * `InstrumentDecoderTest.aCompleteFclShotCarriesItsTelemetry`): the log line
 * `FCLCommunicator.enhancedLegCallback` always writes, and the retake recommendation it writes
 * when quality drops below half.
 */
class FclTelemetryTest {

    private class ListenerCapturingTransport : InstrumentTransport {
        var listener: InstrumentTransportListener? = null
        override val isConnected = true
        override fun connect() {}
        override fun disconnect() {}
        override fun send(bytes: ByteArray) {}
        override fun observe(listener: InstrumentTransportListener): TransportSubscription {
            this.listener = listener
            return TransportSubscription {}
        }
    }

    private fun sessionOnFcl(): Pair<SurveySession, ListenerCapturingTransport> {
        val session = SurveySession(Survey("FCL"))
        session.connect()
        val transport = ListenerCapturingTransport()
        session.attachForTest(
            transport,
            InstrumentDecoder.forProfile(InstrumentProfile.FCL),
            InstrumentProfile.FCL,
        )
        return session to transport
    }

    private fun feedShot(transport: ListenerCapturingTransport, quality: Float, battery: Int) {
        val listener = requireNotNull(transport.listener)
        listener.onFrame(
            FrameChannel.PRIMARY,
            FclProtocol.encodePrimary(
                sequenceNumber = 1,
                statusFlags = 0,
                batteryLevel = battery,
                azimuth = 90f,
                inclination = 0f,
                distance = 5f,
                shotQuality = quality,
            ),
        )
        listener.onFrame(
            FrameChannel.EXTENDED,
            FclProtocol.encodeExtended(
                currentMagneticField = 48f,
                expectedMagneticField = 48f,
                currentMagneticDip = 66f,
                expectedMagneticDip = 66f,
                temperature = 21.5f,
                rollAngle = 3.2f,
                measurementId = 7,
            ),
        )
    }

    /** A good shot's quality and battery reach the log, without any manufactured warning. */
    @Test
    fun aGoodShotLogsItsQualityAndBattery() {
        val (session, transport) = sessionOnFcl()

        feedShot(transport, quality = 0.95f, battery = 42)

        assertTrue(
            session.log.any { it.contains("Excellent") && it.contains("42%") },
            "expected a telemetry line with the quality and battery in it, got: ${session.log}",
        )
        assertTrue(session.log.none { it.contains("retak", ignoreCase = true) })
    }

    /** A poor shot gets the same retake recommendation `enhancedLegCallback` always logs. */
    @Test
    fun aPoorShotRecommendsARetake() {
        val (session, transport) = sessionOnFcl()

        feedShot(transport, quality = 0.3f, battery = 42)

        assertTrue(
            session.log.any { it.contains("retak", ignoreCase = true) },
            "expected a retake recommendation, got: ${session.log}",
        )
    }
}
