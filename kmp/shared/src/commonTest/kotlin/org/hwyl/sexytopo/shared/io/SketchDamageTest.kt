package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Telling a damaged drawing from a drawing that is merely missing something.
 *
 * The trap is the cross-section: deleting a station does not delete the drawing cut at it, because
 * the drawing is the surveyor's work rather than a view of the graph. So a sketch file can hold a
 * cross-section whose station is gone, the reader skips it *by design*, and counting that as damage
 * would warn about a broken file on every single open of a perfectly good survey.
 */
class SketchDamageTest {

    private fun survey(): Survey =
        Survey("Swildons").also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 10f, 0f)) }

    private fun sketchWithACrossSection(survey: Survey): String {
        val sketch = org.hwyl.sexytopo.shared.model.sketch.Sketch()
        sketch.pathDetails.add(PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK))
        sketch.addCrossSection(CrossSection(survey.activeStation, 0f), Coord2D(2f, 2f))
        return SketchJson.write(sketch, survey.name)
    }

    @Test
    fun anIntactDrawingHasLostNothing() {
        val survey = survey()
        val read = SketchJson.read(sketchWithACrossSection(survey), survey)
        assertEquals(0, read.dropped)
        assertEquals(1, read.sketch.pathDetails.size)
        assertEquals(1, read.sketch.crossSectionDetails.size)
    }

    @Test
    fun aCrossSectionWhoseStationIsGoneIsNotDamage() {
        val survey = survey()
        val text = sketchWithACrossSection(survey)

        val elsewhere = Survey("Elsewhere")
        val read = SketchJson.read(text, elsewhere)

        assertEquals(0, read.dropped, "an orphaned cross-section was reported as damage")
        assertEquals(0, read.sketch.crossSectionDetails.size, "and it is still not drawn")
        assertEquals(1, read.sketch.pathDetails.size, "while the rest of the drawing came through")
    }

    @Test
    fun aStrokeThatWillNotParseIsDamage() {
        val survey = survey()
        val damaged =
            sketchWithACrossSection(survey)
                .replace("\"paths\": [", "\"paths\": [ { \"colour\": \"BLACK\" },")

        val read = SketchJson.read(damaged, survey)
        assertEquals(1, read.dropped, "the broken stroke was not counted")
        assertEquals(1, read.sketch.pathDetails.size, "the good stroke still came through")
    }

    /** And a cross-section entry with no station named at all is damage, not an orphan. */
    @Test
    fun aCrossSectionWithNoStationIsDamage() {
        val survey = survey()
        val damaged =
            sketchWithACrossSection(survey).replace("\"station-id\"", "\"not-a-station-id\"")

        assertEquals(1, SketchJson.read(damaged, survey).dropped)
    }
}
