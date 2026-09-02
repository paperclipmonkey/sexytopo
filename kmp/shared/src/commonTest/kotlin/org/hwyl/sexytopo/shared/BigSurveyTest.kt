package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.CompassExporter
import org.hwyl.sexytopo.shared.io.export.PocketTopoExporter
import org.hwyl.sexytopo.shared.io.export.SurvexExporter
import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.Th2Exporter
import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.TherionExporter
import org.hwyl.sexytopo.shared.io.export.XviExporter
import org.hwyl.sexytopo.shared.io.imports.SurveyImporter
import org.hwyl.sexytopo.shared.math.Space3DTransformer
import org.hwyl.sexytopo.shared.math.Wireframe
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.boundsOf
import org.hwyl.sexytopo.shared.sketch.findNearestVisibleItemWithin
import org.hwyl.sexytopo.shared.survey.SurveyStats
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A cave the size of a real one: a passage is a *chain*, so walking the survey tree recursively
 * overflowed the stack between one and three thousand stations, crashing the app on the plan view
 * before it drew anything. Legs are attached directly rather than through the survey engine, whose
 * station naming scans the whole survey per name and would make the fixture cost more than the
 * thing being tested.
 */
class BigSurveyTest {

    private val stationCount = 4000

    /** A real cave is branchier than one long passage, and branches make the recursion *shallower*. */
    private fun aLongPassage(): Survey {
        val survey = Survey("Long")
        var previous = survey.origin
        for (i in 2..stationCount) {
            val station = Station("$i")
            val leg = Leg(5f, (i * 11f) % 360f, ((i % 15) - 7).toFloat(), station)
            previous.addOnwardLeg(leg)
            survey.addLegRecord(leg)
            for (side in 0 until 2) {
                val splay = Leg(2f, ((i * 11f) + 90f + side * 180f) % 360f, 0f)
                station.addOnwardLeg(splay)
                survey.addLegRecord(splay)
            }
            previous = station
        }
        return survey
    }

    @Test
    fun everyStationIsFound() {
        val survey = aLongPassage()
        assertEquals(stationCount, survey.getAllStations().size)
        assertEquals(stationCount - 1 + (stationCount - 1) * 2, survey.getAllLegs().size)
    }

    @Test
    fun aLongPassageProjectsToAPlan() {
        val space = Projection2D.PLAN.project(aLongPassage())
        assertEquals(stationCount, space.stationMap.size)
    }

    @Test
    fun aLongPassageUnrollsToAnExtendedElevation() {
        val space = Projection2D.EXTENDED_ELEVATION.project(aLongPassage())
        assertEquals(stationCount, space.stationMap.size)
    }

    @Test
    fun aLongPassageBuildsAWireframe() {
        val wireframe = Wireframe.of(Space3DTransformer().transformTo3D(aLongPassage()))
        assertEquals(stationCount, wireframe.stations.size)
        assertEquals(stationCount - 1, wireframe.legs.size)
        assertTrue(wireframe.extent > 0f)
    }

    @Test
    fun aLongPassageHasStatistics() {
        val survey = aLongPassage()
        assertEquals(stationCount - 1, SurveyStats.numberOfStations(survey))
        assertTrue(SurveyStats.totalLength(survey) > 0f)
        assertTrue(SurveyStats.numberOfLegsUnder(survey.origin) > 0)
    }

    /** The `extend` commands used to overflow part-way through writing the file, not before starting it. */
    @Test
    fun aLongPassageExportsToSurvexAndTherion() {
        val survey = aLongPassage()

        val svx = SurvexExporter.export(survey)
        assertTrue(svx.contains("*begin Long"))
        assertEquals(1, svx.lines().count { it.startsWith("*extend ") })

        val th = TherionExporter.export(survey)
        assertTrue(th.contains("survey Long"))
    }

    /**
     * Measured as well as run: the SVG takes about 600ms at this size and the rest under a tenth
     * of a second, all linear.
     */
    @Test
    fun aLongPassageExportsToEveryOtherFormat() {
        val survey = aLongPassage()
        val projection = Projection2D.PLAN
        val inner = SvgExporter.exportFrame(survey, projection).scale(SvgExporter.SCALE.toFloat())
        val outer =
            SvgExporter.addBorder(SvgExporter.exportFrame(survey, projection))
                .scale(SvgExporter.SCALE.toFloat())

        val svg = SvgExporter.export(survey, projection)
        assertTrue(svg.startsWith("<?xml"), "the SVG does not start like one")
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"), "the SVG was cut off part way")

        val xvi = XviExporter.export(
            survey.getSketch(projection),
            projection.project(survey),
            SvgExporter.SCALE.toFloat(),
            outer,
        )
        assertTrue(xvi.contains("set XVIstations"))

        val th2 = Th2Exporter.export(
            survey = survey,
            projection = projection,
            innerFrame = inner,
            outerFrame = outer,
            scale = SvgExporter.SCALE.toFloat(),
        )
        assertTrue(th2.contains("encoding utf-8"))

        assertTrue(CompassExporter.export(survey).isNotEmpty())
        assertTrue(PocketTopoExporter.export(survey).isNotEmpty())
    }

    @Test
    fun aLongPassageRoundTripsThroughSurvex() {
        val survey = aLongPassage()
        val reread = SurveyImporter.read(SurvexExporter.export(survey), SurveyFormat.SURVEX, "Long")
        assertEquals(stationCount, reread.getAllStations().size)
    }

    @Test
    fun aLongPassageRoundTripsThroughTheNativeFormat() {
        val survey = aLongPassage()
        val reread = SurveyJson.parse(SurveyJson.write(survey))
        assertEquals(stationCount, reread.getAllStations().size)
        assertEquals(survey.getAllLegs().size, reread.getAllLegs().size)
    }

    @Test
    fun aLongPassageCanHaveItsDirectionSetThroughout() {
        val survey = aLongPassage()

        SurveyUpdater.setExtendedElevationDirectionOfSubtree(
            survey.origin,
            ExtendedElevationDirection.LEFT,
        )

        assertTrue(survey.getAllStations().all { it.extendedElevationDirection == ExtendedElevationDirection.LEFT })
    }

    /**
     * A file with two stations of the same name collapses them into a leg pointing at its own
     * source; walking that has to *stop*, because the check that reports it broken walks it first.
     */
    @Test
    fun aSurveyThatPointsAtItselfStillFinishesBeingWalked() {
        val survey = Survey("Looped")
        val second = Station("2")
        survey.origin.addOnwardLeg(Leg(5f, 0f, 0f, second))
        second.addOnwardLeg(Leg(5f, 180f, 0f, survey.origin))

        val stations = survey.getAllStations()

        assertEquals(2, stations.size)
        assertEquals(setOf("1", "2"), stations.map { it.name }.toSet())
    }

    /**
     * Every operation here is linear in the number of details. Easy ways to lose that: a hit test
     * that re-measures bounds, an erase that rebuilds the list, an undo that copies the sketch.
     */
    @Test
    fun aBigDrawingCanStillBeDrawnOnAndRubbedOut() {
        val strokes = 8000
        val sketch = Sketch()
        for (i in 0 until strokes) {
            val x = (i % 100) * 2f
            val y = (i / 100) * 2f
            sketch.pathDetails.add(
                PathDetail(List(12) { Coord2D(x + it * 0.1f, y + (it % 3) * 0.1f) }, Colour.BLACK),
            )
        }

        assertNotNull(boundsOf(sketch))
        assertNotNull(findNearestVisibleItemWithin(sketch, Coord2D(0.2f, 0.1f), 1f, 50f))

        val editor = SketchEditor(sketch)
        var erased = 0
        for (i in 0 until 50) {
            if (editor.eraseAt(Coord2D((i % 100) * 2f, (i / 100) * 2f), 1f, 50f)) erased++
        }
        assertTrue(erased > 0, "nothing was rubbed out of a drawing covered in strokes")
        repeat(erased) { assertTrue(editor.undo()) }
        assertEquals(strokes, sketch.pathDetails.size, "undo did not put the drawing back")
    }

    @Test
    fun aLongPassageCanBeTraversed() {
        val survey = aLongPassage()
        var seen = 0

        Survey.traverseStations(survey.origin) { seen++; false }
        assertEquals(stationCount, seen)

        var legs = 0
        Survey.traverseLegs(survey.origin) { _, _ -> legs++; false }
        assertEquals(survey.getAllLegs().size, legs)
    }
}
