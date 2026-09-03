package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.CompassExporter
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compass `.dat` export, pinned byte-for-byte against the Android app's own output.
 *
 * The expected string here is not a reading of the Java: it was produced by *running*
 * `CompassExporter.getContent` from `app/` on this exact survey, under the Android module's JVM
 * unit-test variant, and copying what came out.
 */
class CompassExportTest {

    /**
     * 1 -> 2, then two splays off 2.
     *
     * The survey is deliberately unnamed, because `Survey.setName` is private in the Java and its
     * default is what the oracle run produced.
     */
    private fun survey(): Survey {
        val survey = Survey("Unsaved Survey")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(1.5f, 180f, 0f))
        SurveyBuilder.addSplay(survey, survey.activeStation, Leg(2.25f, 200f, -5f))
        survey.trip = Trip(SurveyDate(2024, 3, 7))
        return survey
    }

    private val golden =
            "SexyTopo Export\r\n" +
            "SURVEY NAME: Unsaved Survey\r\n" +
            "SURVEY DATE: 03 07 2024\tCOMMENT: \r\n" +
            "SURVEY TEAM:\r\n" +
            "\r\n" +
            "DECLINATION: 0.00\tFORMAT: DMMDLRUDLADNF\tCORRECTIONS: 0.00 0.00 0.00\r\n" +
            "\r\n" +
            "FROM\tTO\tLENGTH\tBEARING\tINC\tLEFT\tUP\tDOWN\tRIGHT\tFLAGS\tCOMMENTS\r\n" +
            "\r\n" +
            "1\t2\t16.40\t90.00\t10.00\t-9.99\t-9.99\t-9.99\t-9.99\t\t\t\r\n" +
            "2\t2ss000\t4.92\t180.00\t0.00\t-9.99\t-9.99\t-9.99\t-9.99\t#|L#\t\t\r\n" +
            "2\t2ss001\t7.38\t200.00\t-5.00\t-9.99\t-9.99\t-9.99\t-9.99\t#|L#\t\t\r\n" +
            "\u000C"

    @Test
    fun exportMatchesTheAndroidAppByteForByte() {
        assertEquals(golden, CompassExporter.export(survey()))
    }

    @Test
    fun everyLineEndsWithCrlf() {
        val content = CompassExporter.export(survey())
        val parts = content.split("\n")
        for (part in parts.dropLast(1)) {
            assertTrue(part.endsWith("\r"), "line without CR: ${part.replace("\r", "<CR>")}")
        }
        assertEquals("\u000C", parts.last())
    }

    @Test
    fun lengthsAreInDecimalFeet() {
        // 5 m is 16.40 ft, not 5.00. A metres-for-feet slip is silent and out by 3.28x.
        assertTrue(CompassExporter.export(survey()).contains("\t16.40\t"))
        assertEquals(3.28084, CompassExporter.METRES_TO_FEET, 1e-9)
    }

    @Test
    fun splaysAreNumberedPerFromStationAndFlaggedOutOfLength() {
        val content = CompassExporter.export(survey())
        assertTrue(content.contains("2ss000"), "first splay off station 2")
        assertTrue(content.contains("2ss001"), "second splay off station 2")
        val legLine = content.lines().first { it.startsWith("1\t2\t") }
        assertTrue(!legLine.contains("#|L#"), "a real leg must count towards cave length")
    }

    @Test
    fun theSurveyEndsWithAFormFeed() {
        assertTrue(CompassExporter.export(survey()).endsWith("\u000C"))
    }

    @Test
    fun aSurveyWithNoTripHasAnEmptyDateField() {
        val survey = Survey("Unsaved Survey")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        val content = CompassExporter.export(survey)
        assertTrue(content.contains("SURVEY DATE: \tCOMMENT: "), "date should be blank, not today")
    }
}
