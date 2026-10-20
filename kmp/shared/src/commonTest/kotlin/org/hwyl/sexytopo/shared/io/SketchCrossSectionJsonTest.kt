package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
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

    // The cases `SketchJsonTranslaterTest` gained in upstream 1.13.0, when the elevation got
    // cross-sections of its own and horizontal ones joined them.

    private fun roundTrip(survey: Survey, sketch: Sketch): Sketch =
        SketchJson.parse(SketchJson.write(sketch, survey.name), survey)

    @Test
    fun theElevationsSectionsAndScaleRoundTrip() {
        val survey = Survey("X")
        val station = survey.origin
        val elevation = survey.elevationSketch
        elevation.crossSectionScale = 2.5f
        elevation.addCrossSection(CrossSection(station, 90f), Coord2D(3f, 4f))

        val restored = roundTrip(survey, elevation)

        assertEquals(2.5f, restored.crossSectionScale)
        val detail = restored.crossSectionDetails.single()
        assertTrue(detail.station === station)
        assertEquals(90f, detail.crossSection.angle)
        assertEquals(Coord2D(3f, 4f), detail.position)
    }

    @Test
    fun theSameStationOnThePlanAndTheElevationRoundTripsSeparately() {
        val survey = Survey("X")
        val station = survey.origin
        survey.planSketch.addCrossSection(CrossSection(station, 10f), Coord2D(1f, 1f))
        survey.elevationSketch.addCrossSection(CrossSection(station, 20f), Coord2D(9f, 9f))

        val plan = roundTrip(survey, survey.planSketch).crossSectionDetails.single()
        val elevation = roundTrip(survey, survey.elevationSketch).crossSectionDetails.single()

        assertEquals(Coord2D(1f, 1f), plan.position)
        assertEquals(10f, plan.crossSection.angle)
        assertEquals(Coord2D(9f, 9f), elevation.position)
        assertEquals(20f, elevation.crossSection.angle)
    }

    @Test
    fun aHorizontalSectionRoundTripsWithItsOrientation() {
        val survey = Survey("X")
        survey.elevationSketch.addCrossSection(CrossSection.horizontal(survey.origin), Coord2D(3f, 4f))

        val detail = roundTrip(survey, survey.elevationSketch).crossSectionDetails.single()

        assertEquals(CrossSection.Orientation.HORIZONTAL, detail.crossSection.orientation)
        assertEquals(Coord2D(3f, 4f), detail.position)
    }

    @Test
    fun aHorizontalSectionStillWritesAnAngleForOlderVersions() {
        val survey = Survey("X")
        val detail = CrossSectionDetail(Coord2D.ORIGIN, CrossSection.horizontal(survey.origin))

        val json = SketchJson.toJson(detail)

        assertEquals(
            SketchJson.ORIENTATION_HORIZONTAL,
            json[SketchJson.ORIENTATION_TAG]?.jsonPrimitive?.content,
        )
        assertEquals(0.0, json[SketchJson.ANGLE_TAG]?.jsonPrimitive?.double)
    }

    @Test
    fun aVerticalSectionIsWrittenWithoutAnOrientation() {
        val survey = Survey("X")
        val detail = CrossSectionDetail(Coord2D.ORIGIN, CrossSection(survey.origin, 30f))

        assertFalse(SketchJson.toJson(detail).containsKey(SketchJson.ORIENTATION_TAG))
    }

    @Test
    fun aSectionWithoutAnOrientationLoadsAsVertical() {
        // As written before horizontal sections existed
        val survey = Survey("X")
        val json =
            SketchJson.toJson(CrossSectionDetail(Coord2D.ORIGIN, CrossSection(survey.origin, 30f)))

        val loaded = SketchJson.toCrossSectionDetail(json, survey)!!

        assertEquals(CrossSection.Orientation.VERTICAL, loaded.crossSection.orientation)
        assertEquals(30f, loaded.crossSection.angle)
    }

    @Test
    fun aSectionWithAnUnknownOrientationLoadsAsVertical() {
        val survey = Survey("X")
        val written =
            SketchJson.toJson(CrossSectionDetail(Coord2D.ORIGIN, CrossSection(survey.origin, 30f)))
        val json = JsonObject(written + (SketchJson.ORIENTATION_TAG to JsonPrimitive("sideways")))

        val loaded = SketchJson.toCrossSectionDetail(json, survey)!!

        assertEquals(CrossSection.Orientation.VERTICAL, loaded.crossSection.orientation)
        assertEquals(30f, loaded.crossSection.angle)
    }

    /**
     * The file the Android app writes for a horizontal section, as it writes it, read here: the
     * two apps open each other's surveys, so the format has to match both ways, not just round
     * trip within one of them.
     */
    @Test
    fun aHorizontalSectionAsTheAndroidAppWritesItIsRead() {
        val survey = Survey("X")
        val text =
            """{"x-sections": [{"station-id": "1", "location": {"x": 3, "y": 4}, "angle": 0, """ +
                """"orientation": "horizontal"}], "settings": {"cross-section-scale": 2.5}}"""

        val sketch = SketchJson.parse(text, survey)

        val detail = sketch.crossSectionDetails.single()
        assertEquals(CrossSection.Orientation.HORIZONTAL, detail.crossSection.orientation)
        assertEquals(2.5f, sketch.crossSectionScale)
    }
}
