package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `x-sections` array of the native sketch format. Ported from `SketchJsonTranslaterTest`, plus
 * a full write/read round trip through the file the app actually saves.
 */
class SketchCrossSectionJsonTest {

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 0.0001f, "expected $expected but was $actual")

    private fun surveyWithTwoStations(): Survey {
        val survey = Survey("Test Cave")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        return survey
    }

    private fun drawnSubSketch(): Sketch {
        val sketch = Sketch()
        sketch.pathDetails.add(PathDetail(listOf(Coord2D.ORIGIN, Coord2D(1f, 2f)), Colour.BLACK))
        return sketch
    }

    @Test
    fun anEmptySubSketchOmitsTheSketchKey() {
        val survey = Survey("X")
        val detail = CrossSectionDetail(Coord2D(3f, 4f), CrossSection(survey.origin, 0f))
        val json = SketchJson.toJson(detail)
        assertFalse(
            json.containsKey(SketchJson.SKETCH_TAG),
            "an empty sub-sketch should not write a sketch key",
        )
    }

    @Test
    fun aDrawnSubSketchIsWrittenAndReadBack() {
        val survey = Survey("X")
        val detail =
            CrossSectionDetail(Coord2D(3f, 4f), CrossSection(survey.origin, 0f), drawnSubSketch())

        val json = SketchJson.toJson(detail)
        assertTrue(json.containsKey(SketchJson.SKETCH_TAG))

        val restored = SketchJson.toCrossSectionDetail(json, survey)!!
        assertEquals(1, restored.sketch.pathDetails.size)
        assertEquals(2, restored.sketch.pathDetails.single().path.size)
    }

    @Test
    fun noSketchKeyYieldsAnEmptySubSketch() {
        val survey = Survey("X")
        val detail = CrossSectionDetail(Coord2D(3f, 4f), CrossSection(survey.origin, 0f))
        val restored = SketchJson.toCrossSectionDetail(SketchJson.toJson(detail), survey)!!
        assertTrue(restored.sketch.pathDetails.isEmpty())
    }

    @Test
    fun crossSectionsSurviveAFullSketchRoundTrip() {
        val survey = surveyWithTwoStations()
        val station2 = survey.getStationByName("2")!!
        val plan = survey.planSketch
        plan.crossSectionScale = 4f
        plan.crossSectionDetails.add(
            CrossSectionDetail(Coord2D(12f, -3.5f), CrossSection(station2, 137.5f), drawnSubSketch())
        )

        val restored = SketchJson.parse(SketchJson.write(plan, survey.name), survey)

        val detail = restored.crossSectionDetails.single()
        assertEquals("2", detail.station.name)
        assertTrue(detail.station === station2, "the section must hang off the survey's own station")
        assertEquals(Coord2D(12f, -3.5f), detail.position)
        assertClose(137.5f, detail.crossSection.angle)
        assertEquals(1, detail.sketch.pathDetails.size)
        assertClose(4f, restored.crossSectionScale)
    }

    @Test
    fun aSectionNamingAnUnknownStationIsDropped() {
        // The Java stores a null station here and crashes later; dropping it is the safe
        // equivalent, reached when a station has been renamed or deleted since the sketch was saved.
        val survey = surveyWithTwoStations()
        val plan = Sketch()
        plan.crossSectionDetails.add(
            CrossSectionDetail(Coord2D.ORIGIN, CrossSection(org.hwyl.sexytopo.shared.model.survey.Station("99"), 0f))
        )
        val restored = SketchJson.parse(SketchJson.write(plan, survey.name), survey)
        assertTrue(restored.crossSectionDetails.isEmpty())
    }

    @Test
    fun parsingWithoutASurveyKeepsEverythingElse() {
        val survey = surveyWithTwoStations()
        val plan = Sketch()
        plan.pathDetails.add(PathDetail(listOf(Coord2D.ORIGIN, Coord2D(1f, 1f)), Colour.BLACK))
        plan.crossSectionDetails.add(
            CrossSectionDetail(Coord2D.ORIGIN, CrossSection(survey.getStationByName("2")!!, 0f))
        )

        val restored = SketchJson.parse(SketchJson.write(plan, survey.name))
        assertEquals(1, restored.pathDetails.size)
        assertTrue(restored.crossSectionDetails.isEmpty(), "no survey, no station to attach to")
    }
}
