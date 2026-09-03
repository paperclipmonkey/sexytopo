package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.PocketTopoExporter
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PocketTopo text export.
 *
 * The DATA section is pinned byte-for-byte against the Android app's own output, captured by
 * running `PocketTopoTxtExporter.exportData` rather than by reading it. The station sections cannot
 * be pinned that way, because the Java's are not reproducible even against themselves - see
 * [PocketTopoExporter]. They are checked for the properties that matter instead: a defined order,
 * every station present, and stability across repeated exports.
 */
class PocketTopoExportTest {

    private fun survey(): Survey {
        val survey = Survey("Unsaved Survey")
        val origin = survey.activeStation
        val two = SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 10f))
        SurveyBuilder.addSplay(survey, two, Leg(1.5f, 180f, -3.5f))
        val three = Station(SurveyBuilder.nextStationName(survey, two))
        three.comment = "wet\ncrawl"
        SurveyBuilder.addLegFromStation(survey, two, Leg(7.25f, 45f, 0f, three))
        require(origin.name == "1")
        return survey
    }

    private val goldenData =
            "DATA\n" +
            "1\t2\t5.000\t90.00\t+10.00\t\n" +
            "2\t\t1.500\t180.00\t-3.50\t\n" +
            "2\t3\t7.250\t45.00\t+0.00\t\t;  wet\\ncrawl\n"

    @Test
    fun theDataSectionMatchesTheAndroidAppByteForByte() {
        assertEquals(goldenData, PocketTopoExporter.exportData(survey()))
    }

    /**
     * Inclination is signed here and unsigned in Survex and Therion.
     *
     * PocketTopo goes through `TableCol.INCLINATION`, which is `"%+.2f"`, where the other
     * exporters use `SurvexTherionUtil.formatInclination` and its plain `"%.2f"`. Easy to
     * normalise by accident, and the golden above would not obviously look wrong if it were.
     */
    @Test
    fun inclinationIsSignedUnlikeTheOtherExporters() {
        val data = PocketTopoExporter.exportData(survey())
        assertTrue(data.contains("\t+10.00\t"), "positive inclination should carry a +")
        assertTrue(data.contains("\t-3.50\t"), "negative inclination keeps its -")
        assertTrue(data.contains("\t+0.00\t"), "zero is written +0.00")
    }

    @Test
    fun aSplayHasNoDestinationName() {
        assertTrue(PocketTopoExporter.exportData(survey()).contains("\n2\t\t1.500\t"))
    }

    @Test
    fun aMultiLineCommentIsFlattened() {
        val data = PocketTopoExporter.exportData(survey())
        assertTrue(data.contains("wet\\ncrawl"), "newlines collapse to a literal backslash-n")
        assertTrue(!data.contains("wet\ncrawl"), "a raw newline would break the one-line record")
    }

    /**
     * The Java shuffles these; this port must not.
     *
     * `Space` keys its maps on `Station` and `Leg`, neither of which overrides `hashCode`, so the
     * Java's iteration order follows identity hashes and changes between runs. Exporting the same
     * survey twice there produces different files. Here it must not.
     */
    @Test
    fun stationOrderIsStableAcrossExports() {
        val first = PocketTopoExporter.exportPlan(survey())
        val second = PocketTopoExporter.exportPlan(survey())
        assertEquals(first, second)
    }

    @Test
    fun stationsAppearInTheOrderTheyWereSurveyed() {
        val survey = survey()
        val section =
            PocketTopoExporter.exportStationCoords(survey, Projection2D.PLAN.project(survey))
        val names =
            section
                .lines()
                .filter { it.count { c -> c == '\t' } == 2 }
                .map { it.substringAfterLast('\t') }
        assertEquals(listOf("1", "2", "3"), names)
    }

    @Test
    fun everySectionIsPresent() {
        val content = PocketTopoExporter.export(survey())
        for (heading in listOf("TRIP", "DATE ", "DECLINATION", "DATA", "PLAN", "ELEVATION")) {
            assertTrue(content.contains(heading), "missing $heading")
        }
        assertEquals(2, Regex("STATIONS").findAll(content).count(), "plan and elevation")
        assertEquals(2, Regex("SHOTS").findAll(content).count())
    }

    /**
     * With a trip there is one newline after the date; without one there are two.
     *
     * Not a tidy-up candidate: the Java's no-trip branch supplies a newline of its own and then
     * also gets the shared one, so the two paths genuinely differ. Pinned so a later reader does
     * not "fix" the asymmetry and silently change the format.
     */
    @Test
    fun aTripDateIsWrittenAsIsoWithOneNewline() {
        val survey = survey()
        survey.trip = Trip(SurveyDate(2024, 3, 7))
        assertTrue(
            PocketTopoExporter.export(survey).startsWith("TRIP\nDATE 2024-03-07\nDECLINATION"),
            "a dated survey has no blank line after the date",
        )
    }

    @Test
    fun noTripFallsBackToTheEpochAndLeavesABlankLine() {
        assertTrue(
            PocketTopoExporter.export(survey()).startsWith("TRIP\nDATE 1970-01-01\n\nDECLINATION"),
            "an undated survey does have one",
        )
    }
}
