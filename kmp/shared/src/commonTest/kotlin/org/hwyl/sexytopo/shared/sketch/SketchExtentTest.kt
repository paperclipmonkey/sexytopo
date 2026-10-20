package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How far a sketch reaches once its cross-sections are drawn at the sketch's cross-section scale,
 * and that each of a survey's two sketches keeps its own sections. The cases `SketchTest` gained
 * in upstream 1.13.0.
 */
class SketchExtentTest {

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-4f, "expected $expected but was $actual")

    private fun sectionAt(sketch: Sketch, station: Station): CrossSectionDetail? =
        sketch.crossSectionDetails.firstOrNull { it.station === station }

    @Test
    fun theSameStationCanHaveSeparateSectionsOnThePlanAndTheElevation() {
        val survey = Survey("X")
        val station = survey.origin
        val plan = SketchEditor(survey.planSketch)
        val elevation = SketchEditor(survey.elevationSketch)

        val planDetail = plan.addCrossSection(CrossSection(station, 0f), Coord2D(1f, 2f))

        assertSame(planDetail, sectionAt(survey.planSketch, station))
        assertNull(sectionAt(survey.elevationSketch, station))

        val elevationDetail = elevation.addCrossSection(CrossSection(station, 0f), Coord2D(5f, 6f))

        assertSame(planDetail, sectionAt(survey.planSketch, station))
        assertSame(elevationDetail, sectionAt(survey.elevationSketch, station))

        elevation.delete(elevationDetail)

        assertSame(planDetail, sectionAt(survey.planSketch, station))
        assertNull(sectionAt(survey.elevationSketch, station))
    }

    /** A section at (10, 0) whose only splay reaches 2m east of it. */
    private fun sketchWithASectionReachingEast(): Sketch {
        val station = Station("1")
        station.addOnwardLeg(Leg(2f, 90f, 0f))
        val sketch = Sketch()
        sketch.addCrossSection(CrossSection(station, 0f), Coord2D(10f, 0f))
        return sketch
    }

    @Test
    fun theSketchHoldsItsSectionsAtItsCrossSectionScale() {
        val sketch = sketchWithASectionReachingEast()
        assertClose(12f, boundsOf(sketch).right)

        sketch.crossSectionScale = 3f
        assertClose(16f, boundsOf(sketch).right)

        sketch.crossSectionScale = 0.5f
        assertClose(11f, boundsOf(sketch).right)
    }

    @Test
    fun aCopiedSketchKeepsItsCrossSectionScaleInItsExtent() {
        val sketch = sketchWithASectionReachingEast()
        sketch.crossSectionScale = 3f

        assertClose(16f, boundsOf(sketch.copy()).right)
        assertClose(16f, Frame.from(sketch.copy()).right)
    }

    // From `ExportFrameFactoryTest`, against the frame the SVG and Therion exports are built on.

    /**
     * A straight survey with a section on the elevation well out to the right of it, so that the
     * section decides how far right the frame goes. Its east splay is 2m long and it sits at
     * (20, -3).
     */
    private fun surveyWithAWideElevationSection(orientation: CrossSection.Orientation): Survey {
        val survey = TestSurveys.createStraightNorth()
        val station = survey.getStationByName("2")!!
        station.addOnwardLeg(Leg(2f, 90f, 0f))
        survey.elevationSketch.addCrossSection(
            CrossSectioner.section(survey, station, orientation),
            Coord2D(20f, -3f),
        )
        return survey
    }

    @Test
    fun theExportFrameHoldsEnlargedSections() {
        for (orientation in CrossSection.Orientation.entries) {
            val survey = surveyWithAWideElevationSection(orientation)
            survey.elevationSketch.crossSectionScale = 2f

            val frame = SvgExporter.exportFrame(survey, Projection2D.EXTENDED_ELEVATION)

            // At twice the size the 2m splay reaches 4m from the section
            assertClose(24f, frame.right)
        }
    }

    @Test
    fun eachExportFrameHoldsOnlyItsOwnSketchsSections() {
        val survey = surveyWithAWideElevationSection(CrossSection.Orientation.VERTICAL)
        survey.elevationSketch.crossSectionScale = 3f

        val planFrame = SvgExporter.exportFrame(survey, Projection2D.PLAN)

        assertTrue(planFrame.right < 20f, "the plan's frame reached ${planFrame.right}")
    }
}
