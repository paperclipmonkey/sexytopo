package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.SurvexTherionWriter
import org.hwyl.sexytopo.shared.model.survey.StationFix
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where in the world a survey is, written the way Survex and Therion read it.
 *
 * Every fault this looks for produces a *valid file*. A longitude written where a latitude
 * belongs is a cave four hundred miles out to sea, on a line both programs accept without a
 * murmur; a comma decimal separator on a French phone is a coordinate that reads as something
 * else entirely or not at all; a missing coordinate system is a fix in degrees interpreted as
 * metres. None of them is a crash and none of them is visible in the app, which is the argument
 * for stating each as an expected string here.
 */
class SurvexTherionFixTest {

    /** A survey with one station and a position on it: a hillside in the Yorkshire Dales. */
    private fun fixedSurvey(
        station: String = "1",
        latitude: Double = 54.123456,
        longitude: Double = -2.345678,
        altitude: Double = 312.0,
        horizontalAccuracy: Double? = 8.0,
        verticalAccuracy: Double? = 15.0,
        source: StationFix.Source = StationFix.Source.MEASURED,
    ): Survey =
        Survey("test").also {
            it.origin = Station(station)
            it.activeStation = it.origin
            it.fix =
                StationFix(
                    station = station,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    horizontalAccuracy = horizontalAccuracy,
                    verticalAccuracy = verticalAccuracy,
                    source = source,
                )
        }

    private fun linesOf(text: String) = text.split("\n").filter { it.isNotBlank() }

    /**
     * The whole block, stated rather than described, for both dialects.
     *
     * **Longitude comes first.** That is the assertion this file exists for: the numbers are in
     * the order x, y, z, and for a geographic system x is the longitude — which is why Survex
     * spells its coordinate system `LONG-LAT` and why the reversed version of this test would
     * have put a Yorkshire cave in the sea off Cornwall. It is checked as an exact string because
     * "the coordinates are there" is exactly what a wrong one also satisfies.
     */
    @Test
    fun theFixIsWrittenLongitudeFirstInBothDialects() {
        val survey = fixedSurvey()

        val survex = linesOf(SurvexTherionWriter.georeference(survey, SurveyFormat.SURVEX))
        assertEquals("*cs LONG-LAT", survex[1])
        assertEquals("*cs out UTM30N", survex[2])
        assertEquals("*fix 1 -2.345678 54.123456 312.0 8.0 8.0 15.0", survex[3])

        val therion = linesOf(SurvexTherionWriter.georeference(survey, SurveyFormat.THERION))
        assertEquals("cs long-lat", therion[1])
        assertEquals("fix 1 -2.345678 54.123456 312.0 8.0 8.0 15.0", therion[2])
    }

    /**
     * Therion is not told what to compute in, and Survex is.
     *
     * A fix in degrees gives a survey program no metres to work in. Survex takes an output system
     * in the same file, so it is given one — the zone the cave is actually in. Therion takes its
     * output system from the `thconfig` that builds the project, which this file does not write,
     * so a `cs out` here would be a line Therion has no use for.
     */
    @Test
    fun onlySurvexIsGivenSomethingToComputeIn() {
        val survey = fixedSurvey()

        assertTrue("cs out" in SurvexTherionWriter.georeference(survey, SurveyFormat.SURVEX))
        assertFalse("cs out" in SurvexTherionWriter.georeference(survey, SurveyFormat.THERION))
    }

    /**
     * A survey nobody has fixed writes nothing at all, rather than an empty or zeroed fix.
     *
     * The ordinary case, and the one that must not change a single byte of what this app exported
     * before the feature existed: a fix at zero would put every unfixed survey in the Atlantic.
     */
    @Test
    fun aSurveyWithNoPositionWritesNothing() {
        val survey = Survey("test")

        assertEquals("", SurvexTherionWriter.georeference(survey, SurveyFormat.SURVEX))
        assertEquals("", SurvexTherionWriter.georeference(survey, SurveyFormat.THERION))
    }

    /**
     * A position typed off a map writes no accuracies rather than making some up.
     *
     * Both formats take the three standard deviations together or not at all, and a number read
     * off a map carries whatever accuracy the map had — which this has no way to know. Writing
     * nothing says "unknown"; writing a zero would say "exact", which is the one thing it is not.
     */
    @Test
    fun aTypedPositionSaysNothingAboutItsAccuracy() {
        val survey =
            fixedSurvey(
                horizontalAccuracy = null,
                verticalAccuracy = null,
                source = StationFix.Source.ENTERED,
            )

        val line = linesOf(SurvexTherionWriter.georeference(survey, SurveyFormat.THERION))[2]

        assertEquals("fix 1 -2.345678 54.123456 312.0", line)
    }

    /**
     * An unknown vertical accuracy borrows the horizontal one rather than dropping both.
     *
     * The receiver that reports how well it knows its position sideways and not vertically is
     * common enough — iOS says so with a negative number — and the alternative reading of the
     * format is to write no accuracy at all, which throws away the good half to avoid guessing at
     * the bad. Borrowing understates the vertical error, which is the direction that is honest
     * about being a guess rather than the direction that hides one.
     */
    @Test
    fun anUnknownVerticalAccuracyBorrowsTheHorizontal() {
        val survey = fixedSurvey(verticalAccuracy = null)

        val line = linesOf(SurvexTherionWriter.georeference(survey, SurveyFormat.THERION))[2]

        assertEquals("fix 1 -2.345678 54.123456 312.0 8.0 8.0 8.0", line)
    }

    /**
     * A fix whose station has been renamed away is written as a comment, not as a command.
     *
     * Both programs stop with an error on a fix naming a station that is not in the file, which
     * would turn a renamed station into a survey that will not process at all — a bad trade for a
     * position that is only ever a convenience. So the block is commented out, the reason is
     * written above it, and the coordinates stay in the file for somebody to move by hand.
     */
    @Test
    fun aFixForAStationThatHasGoneIsCommentedOutAndSaysWhy() {
        val survey = fixedSurvey()
        survey.origin = Station("renamed")
        survey.activeStation = survey.origin

        val therion = SurvexTherionWriter.georeference(survey, SurveyFormat.THERION)
        val survex = SurvexTherionWriter.georeference(survey, SurveyFormat.SURVEX)

        assertTrue("NOT APPLIED" in therion, "nothing said why the fix was dropped: $therion")
        assertTrue(
            linesOf(therion).none { it.startsWith("fix ") || it.startsWith("cs ") },
            "a fix for a station that does not exist was written as a command: $therion",
        )
        assertTrue(
            linesOf(survex).none { it.startsWith("*") },
            "a fix for a station that does not exist was written as a command: $survex",
        )
        // Still in the file, so that a surveyor can rescue it.
        assertTrue("54.123456" in therion, "the position was thrown away rather than commented")
    }

    /**
     * A comma-decimal device writes dots, because that is what both parsers read.
     *
     * The same trap `Formatting.kt` exists for, arriving from a different direction: nothing here
     * uses the platform's number formatting, so this is a statement that nothing ever starts to.
     */
    @Test
    fun coordinatesAreWrittenWithDotsWhateverThePhoneWouldSay() {
        val written = SurvexTherionWriter.georeference(fixedSurvey(), SurveyFormat.THERION)

        assertFalse("," in written.substringAfter("fix "), "a comma reached the fix line: $written")
    }

    /**
     * Six decimal places, always, so a coordinate is not truncated by looking round.
     *
     * A whole number of degrees is the case that catches a formatter written with string
     * concatenation: 54 degrees exactly has to come out as `54.000000` rather than as `54`, which
     * both programs would read as the same number and no human would recognise as a coordinate.
     */
    @Test
    fun aWholeNumberOfDegreesIsStillWrittenAsACoordinate() {
        val survey = fixedSurvey(latitude = 54.0, longitude = 0.0, altitude = 100.0)

        val line = linesOf(SurvexTherionWriter.georeference(survey, SurveyFormat.THERION))[2]

        assertEquals("fix 1 0.000000 54.000000 100.0 8.0 8.0 15.0", line)
    }
}
