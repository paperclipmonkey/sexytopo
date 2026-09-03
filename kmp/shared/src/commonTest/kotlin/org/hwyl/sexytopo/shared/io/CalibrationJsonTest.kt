package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.calibration.CalibrationReading
import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calibration readings on disk.
 *
 * Losing a part-finished calibration to a flat battery means redoing it in the same cave, so the
 * format is the Android app's: a calibration saved by one and loaded by the other is the same one.
 */
class CalibrationJsonTest {

    @Test
    fun aCalibrationSurvivesARoundTrip() {
        val written = CalibrationJson.write(ExampleCalibration.READINGS)

        assertEquals(ExampleCalibration.READINGS, CalibrationJson.read(written))
    }

    /** The tags are `CalibrationJsonTranslater`'s, which is what makes the files interchangeable. */
    @Test
    fun theTagsAreTheAndroidApps() {
        val one = CalibrationReading(1, 2, 3, 4, 5, 6)

        val text = CalibrationJson.write(listOf(one))

        for (tag in listOf("gx", "gy", "gz", "mx", "my", "mz")) {
            assertTrue("\"$tag\"" in text, "missing tag $tag in $text")
        }
    }

    @Test
    fun aFileFromTheAndroidAppLoads() {
        val fromTheApp =
            """
            [
              {"gx": 12545, "gy": 155, "gz": 1529, "mx": 17916, "my": 5305, "mz": 5435},
              {"gx": -15265, "gy": -256, "gz": 1275, "mx": -15908, "my": -7485, "mz": 3364}
            ]
            """.trimIndent()

        val readings = CalibrationJson.read(fromTheApp)

        assertEquals(2, readings.size)
        assertEquals(CalibrationReading(12545, 155, 1529, 17916, 5305, 5435), readings.first())
    }

    @Test
    fun anEmptyRunRoundTrips() {
        assertEquals(emptyList(), CalibrationJson.read(CalibrationJson.write(emptyList())))
    }

    /**
     * A corrupt file leaves the screen empty rather than stopping the app.
     *
     * Deliberately more forgiving than the Java, which throws. This is a convenience file, not the
     * survey: refusing to open because a saved calibration is damaged would lose something that
     * matters to protect something that does not.
     */
    @Test
    fun aCorruptFileIsEmptyRatherThanFatal() {
        assertEquals(emptyList(), CalibrationJson.read("not json at all"))
        assertEquals(emptyList(), CalibrationJson.read(""))
        assertEquals(emptyList(), CalibrationJson.read("{\"gx\": 1}"))
    }

    /**
     * A reading missing a field is skipped, not defaulted.
     *
     * A zero is a plausible-looking sensor count: defaulting one would leave a reading that looks
     * real and quietly spoils the fit, which is worse than losing the reading.
     */
    @Test
    fun anIncompleteReadingIsSkippedRatherThanDefaulted() {
        val text =
            """[{"gx": 1, "gy": 2, "gz": 3, "mx": 4, "my": 5},
                {"gx": 1, "gy": 2, "gz": 3, "mx": 4, "my": 5, "mz": 6}]"""

        val readings = CalibrationJson.read(text)

        assertEquals(listOf(CalibrationReading(1, 2, 3, 4, 5, 6)), readings)
    }
}
