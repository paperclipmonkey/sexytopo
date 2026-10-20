package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.imports.EspecParser
import org.hwyl.sexytopo.shared.io.imports.SurveyImporter
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection.LEFT
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection.RIGHT
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection.VERTICAL
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading a `.espec` back onto a survey, ported from `EspecParserTest`.
 *
 * Every survey here is `TestSurveys.createStraightNorth`: 1 -> 2 -> 3 -> 4, with 4 the active
 * station. Nothing in the file can throw, so the cases about bad input check only that the survey
 * came through it unchanged.
 */
class EspecImportTest {

    private fun straightNorth(): Survey = TestSurveys.createStraightNorth()

    private fun Survey.directionOf(name: String) =
        getStationByName(name)!!.extendedElevationDirection

    private fun Survey.directions() = listOf("1", "2", "3", "4").map { directionOf(it) }

    @Test
    fun eleftTurnsAStationLeft() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\n*eleft 4\n", survey)
        assertEquals(LEFT, survey.directionOf("4"))
    }

    @Test
    fun erightTurnsAStationRight() {
        val survey = straightNorth()
        survey.activeStation.extendedElevationDirection = LEFT

        EspecParser.apply("*start 1\n*eright 4\n", survey)

        assertEquals(RIGHT, survey.directionOf("4"))
    }

    /** `*eleft` carries on down the passage, as a direction set in the app does. */
    @Test
    fun eleftCarriesDownThePassage() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\n*eleft 2\n", survey)
        assertEquals(listOf(RIGHT, LEFT, LEFT, LEFT), survey.directions())
    }

    @Test
    fun aStartLineChangesNothing() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\n", survey)
        assertEquals(RIGHT, survey.directionOf("1"))
    }

    @Test
    fun anUnknownStationIsSkipped() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\n*eleft nonexistent\n", survey)
        assertEquals(RIGHT, survey.directionOf("1"))
    }

    /** What a lone `.svx` comes in as: no file, so nothing to apply. */
    @Test
    fun anEmptyEspecLeavesEverythingRight() {
        val survey = straightNorth()
        EspecParser.apply("", survey)
        assertEquals(RIGHT, survey.directionOf("1"))
        assertEquals(RIGHT, survey.directionOf("4"))
    }

    @Test
    fun aMalformedLineIsSkipped() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\nthis line is malformed extra tokens here\n", survey)
        assertEquals(RIGHT, survey.directionOf("1"))
    }

    @Test
    fun commentLinesAreIgnored() {
        val survey = straightNorth()
        EspecParser.apply("; this is a comment\n*start 1\n*eleft 4\n", survey)
        assertEquals(LEFT, survey.directionOf("4"))
    }

    @Test
    fun anUnknownCommandIsSkipped() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\n*unknown 2\n", survey)
        assertEquals(RIGHT, survey.directionOf("1"))
    }

    /**
     * The commented-out `*evertical` the exporter writes is read back: the leg into 3 is the pitch,
     * so 3 is vertical, the station it hangs from is not, and the passage below it is not either.
     */
    @Test
    fun aCommentedEverticalAppliesToItsLegAlone() {
        val survey = straightNorth()

        EspecParser.apply("*start 1\n; *evertical 2 3\n", survey)

        assertEquals(VERTICAL, survey.directionOf("3"), "the destination")
        assertEquals(RIGHT, survey.directionOf("2"), "the station above the pitch")
        assertEquals(RIGHT, survey.directionOf("4"), "the station below; vertical must not spread")
    }

    /** Any other commented-out command was commented out by somebody, for a reason. */
    @Test
    fun anyOtherCommentedCommandIsStillSkipped() {
        val survey = straightNorth()
        EspecParser.apply("*start 1\n; *eleft 4\n", survey)
        assertEquals(RIGHT, survey.directionOf("4"))
    }

    /**
     * The whole of it, the way the demo does it: a `.svx` and the `.espec` beside it, read together.
     * Left, then a pitch, then back right, all come back where they were.
     */
    @Test
    fun aSurvexExportAndItsEspecComeBackTogether() {
        val original = straightNorth()
        original.getStationByName("2")!!.extendedElevationDirection = LEFT
        original.getStationByName("3")!!.extendedElevationDirection = VERTICAL

        val imported =
            SurveyImporter.read(
                SurvexExporter.export(original),
                SurveyFormat.SURVEX,
                "T",
                espec = SurvexExporter.espec(original),
            )

        assertEquals(listOf(RIGHT, LEFT, VERTICAL, RIGHT), imported.directions())
    }
}
