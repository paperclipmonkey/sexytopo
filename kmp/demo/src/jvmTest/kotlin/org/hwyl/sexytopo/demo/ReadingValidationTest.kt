package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the typed-reading dialogs accept, held to what [Leg] itself accepts.
 *
 * [parseReading] used to restate the bounds, and the restatement disagreed with the model three
 * ways — which matters because the model is the thing that throws. Anything this returns a leg for
 * has to be constructible, and anything the model would take has to be typable, or a reading a
 * surveyor wrote down cannot be got into the app.
 */
class ReadingValidationTest {

    private fun parsed(distance: String, azimuth: String, inclination: String) =
        parseReading(distance, azimuth, inclination)

    /**
     * An azimuth of exactly 360 passed the dialog's own check and then failed `Leg`'s, throwing
     * out of the composition that built it. A crash in a dialog whose whole job is to be the way
     * in when the radio will not play.
     */
    @Test
    fun anAzimuthOfExactly360IsRefusedRatherThanThrown() {
        val result = parsed("5", "360", "0")
        assertNull(result.leg, "Leg rejects 360, so the dialog has to as well")
        assertEquals(Strings.manualEditAzimuthError, result.problem)

        assertNotNull(parsed("5", "359.9", "0").leg)
        assertNotNull(parsed("5", "0", "0").leg)
    }

    /**
     * `isInclinationLegal` accepts 270 to 360 as well as -90 to 90: a theodolite reads a downward
     * shot as 350 rather than -10, and the Android app's importer was changed to take those.
     */
    @Test
    fun theodoliteInclinationsAreTypable() {
        for (inclination in listOf("270", "300", "350", "360")) {
            assertNotNull(parsed("5", "90", inclination).leg, inclination)
        }
        assertNull(parsed("5", "90", "200").leg, "200 is neither range")
    }

    /** `MIN_DISTANCE` is zero and `isDistanceLegal` is inclusive, so a zero-length shot is legal. */
    @Test
    fun aZeroDistanceIsLegalBecauseTheModelSaysSo() {
        assertNotNull(parsed("0", "90", "0").leg)
        assertNull(parsed("-1", "90", "0").leg)
    }

    /** Everything this returns a leg for is a leg the model will actually build. */
    @Test
    fun anythingItAcceptsCanBeConstructed() {
        val values = listOf("-1", "0", "0.5", "5", "89", "90", "91", "269", "270", "359", "360")
        for (distance in values) {
            for (azimuth in values) {
                for (inclination in values) {
                    val result = parsed(distance, azimuth, inclination)
                    if (result.leg == null) continue
                    // Would throw if the dialog's answer and the model's disagreed.
                    Leg(
                        result.leg.distance,
                        result.leg.azimuth,
                        result.leg.inclination,
                    )
                }
            }
        }
    }
}
