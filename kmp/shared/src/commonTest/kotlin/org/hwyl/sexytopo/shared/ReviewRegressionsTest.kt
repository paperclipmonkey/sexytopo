package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.bric.Bric4Decoder
import org.hwyl.sexytopo.shared.comms.fcl.FclDecodeResult
import org.hwyl.sexytopo.shared.comms.fcl.FclDecoder
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regressions from the adversarial review of this port against the Java original. Each of these
 * was a real divergence, found by comparing the two sides rather than by a test failing.
 */
class ReviewRegressionsTest {

    // -------------------------------------------------------------------------------------
    // Survey names are used as folder names, so the Java strips path characters
    // -------------------------------------------------------------------------------------

    @Test
    fun surveyNamesAreSanitised() {
        assertEquals("CaveSystem", Survey("Cave:System").name)
        assertEquals("Cave20", Survey("Cave 2.0".replace(" ", "")).name)
        assertEquals("ab", Survey("a/b").name)
        assertEquals("ab", Survey("a\\b").name)
        assertEquals("ab", Survey("a\nb").name)
    }

    @Test
    fun aNameLeftEmptyByStrippingFallsBack() {
        assertEquals("blank", Survey("...").name)
        assertEquals("blank", Survey("").name)
    }

    @Test
    fun theSetterSanitisesToo() {
        val survey = Survey("Fine")
        survey.name = "bad:name"
        assertEquals("badname", survey.name)
    }

    @Test
    fun theDefaultNameMatchesTheJava() {
        assertEquals("Unsaved Survey", Survey.DEFAULT_NAME)
        assertEquals("Unsaved Survey", Survey().name)
    }

    @Test
    fun aNameLoadedFromFileIsSanitisedOnTheWayIn() {
        // An imported .data.json could carry anything.
        val survey = SurveyJson.parse("""{"name": "bad/name", "stations": []}""")
        assertEquals("badname", survey.name)
    }

    // -------------------------------------------------------------------------------------
    // BRIC: "tell the surveyor" is bound to the slot, not to list position
    // -------------------------------------------------------------------------------------

    /** An errors frame: code at offset 0 (slot 1) and offset 9 (slot 2), floats between. */
    private fun errorsFrame(firstCode: Int, secondCode: Int): ByteArray {
        val bytes = ByteArray(18)
        bytes[0] = firstCode.toByte()
        bytes[9] = secondCode.toByte()
        return bytes
    }

    private fun failuresFrom(frame: ByteArray): List<InstrumentPacket.DeviceFailure> {
        val decoder = Bric4Decoder()
        return decoder.feed(FrameChannel.TERTIARY, frame)
            .filterIsInstance<InstrumentPacket.DeviceFailure>()
    }

    @Test
    fun anErrorInTheFirstSlotIsShownToTheSurveyor() {
        val failures = failuresFrom(errorsFrame(firstCode = 3, secondCode = 0))
        assertEquals(1, failures.size)
        assertTrue(failures.single().showToUser)
    }

    @Test
    fun anErrorOnlyInTheSecondSlotIsNotShown() {
        // The Java binds showToUser to WHICH SLOT the error came from. Indexing the filtered list
        // instead would toast this one, which the Android app only logs.
        val failures = failuresFrom(errorsFrame(firstCode = 0, secondCode = 5))
        assertEquals(1, failures.size)
        assertTrue(!failures.single().showToUser, "slot 2 alone must stay silent")
    }

    @Test
    fun withBothSlotsFilledOnlyTheFirstIsShown() {
        val failures = failuresFrom(errorsFrame(firstCode = 3, secondCode = 5))
        assertEquals(2, failures.size)
        assertTrue(failures[0].showToUser)
        assertTrue(!failures[1].showToUser)
    }

    // -------------------------------------------------------------------------------------
    // BRIC: routing by role rather than cycling blindly
    // -------------------------------------------------------------------------------------

    @Test
    fun routingByRoleSurvivesADroppedIndication() {
        // Android cycles measurement -> metadata -> errors and desynchronises if one is lost.
        // Routing by characteristic cannot: an errors frame is an errors frame whenever it lands.
        val decoder = Bric4Decoder()
        val measurement = ByteArray(32)
        // Deliberately skip the metadata indication.
        decoder.feed(FrameChannel.PRIMARY, measurement)
        val out = decoder.feed(FrameChannel.TERTIARY, errorsFrame(0, 0))

        assertEquals(1, out.size, "a clean errors frame should still complete the shot")
        assertIs<InstrumentPacket.Measurement>(out.single())
    }

    // -------------------------------------------------------------------------------------
    // FCL: an unexpected channel must not be guessed at
    // -------------------------------------------------------------------------------------

    @Test
    fun anFclFrameOnAnUnknownChannelIsRejectedNotGuessed() {
        // Treating it as a primary packet would discard a held primary and cost the shot.
        val decoder = FclDecoder()
        val result = decoder.feed(FrameChannel.DEFAULT, ByteArray(20))
        assertIs<FclDecodeResult.Error>(result)
        assertTrue(!decoder.isAwaitingExtended, "no primary should have been taken from it")
    }
}
