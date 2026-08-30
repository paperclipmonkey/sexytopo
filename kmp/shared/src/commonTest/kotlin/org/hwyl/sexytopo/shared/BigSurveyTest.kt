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
 * A cave the size of a real one.
 *
 * Every walk of the survey tree in this port was a recursion, one stack frame per station — which
 * is how the Java does it. A cave is not a bushy tree: a passage is a *chain*, so the recursion is
 * as deep as the survey is long. Somewhere between one and three thousand stations, on a desktop
 * JVM with a generous stack, the whole thing fell over — and the first thing that touches it is the
 * plan view, so opening a club's survey crashed the app before it drew anything.
 *
 * A phone's stack is smaller than a desktop's, and Kotlin/Wasm's is smaller still, which is why
 * this runs on every target rather than on the JVM alone.
 *
 * Four thousand stations is comfortably past where it used to break and small enough that the test
 * stays quick. The chain is built by attaching legs directly rather than through the survey engine,
 * because the engine's station naming scans the survey for each new name and that would make
 * building the fixture cost more than the thing being tested.
 */
class BigSurveyTest {

    private val stationCount = 4000

    /**
     * One long passage with two wall shots at every station: 4,000 stations, 3,999 legs and 8,000
     * splays. A real cave is branchier, and branches make the recursion *shallower*.
     */
    private fun aLongPassage(): Survey {
        val survey = Survey("Long")
        var previous = survey.origin
        // From 2, because the origin is already called 1 — and two stations of the same name make
        // a survey that cannot round-trip through a format that names a leg's far end.
        for (i in 2..stationCount) {
            val station = Station("$i")
            // Not straight: a real passage wanders, and a straight one would make the bounding box
            // degenerate in two dimensions and hide anything that divides by it.
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

    /** The plan, which is the screen a surveyor opens the survey onto. */
    @Test
    fun aLongPassageProjectsToAPlan() {
        val space = Projection2D.PLAN.project(aLongPassage())
        assertEquals(stationCount, space.stationMap.size)
    }

    /** The extended elevation, whose walk carries an extra accumulator down the chain. */
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

    /**
     * The export, whose `extend` commands walk the tree of their own accord — and which used to
     * overflow part-way through writing the file rather than before starting it.
     */
    @Test
    fun aLongPassageExportsToSurvexAndTherion() {
        val survey = aLongPassage()

        val svx = SurvexExporter.export(survey)
        assertTrue(svx.contains("*begin Long"))
        // One `*extend start` and nothing else: every station inherits the same direction, so
        // there is nothing to change along the way. What is being checked is that the walk that
        // writes these finished at all.
        assertEquals(1, svx.lines().count { it.startsWith("*extend ") })

        val th = TherionExporter.export(survey)
        assertTrue(th.contains("survey Long"))
    }

    /**
     * And to everything else, which is where a surveyor's weekend actually goes.
     *
     * All of these were measured as well as run: at four thousand stations with eight thousand
     * strokes on the drawing, the SVG takes about six hundred milliseconds and the rest are under
     * a tenth of a second, all linear. Nothing here needed fixing — but the Survex export did, and
     * it looked exactly like these until it was measured.
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

    /** And comes back in, which is the other end of the same trip. */
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

    /** Setting a whole subtree's extended-elevation direction walks it too. */
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
     * A survey read from a file can contain a cycle, unlike one the app built: the formats name a
     * leg's far end, and a file with two stations of the same name collapses them into a leg that
     * points at its own source. Walking that has to *stop*, because the check that would report the
     * file as broken begins by walking it.
     */
    @Test
    fun aSurveyThatPointsAtItselfStillFinishesBeingWalked() {
        val survey = Survey("Looped")
        val second = Station("2")
        survey.origin.addOnwardLeg(Leg(5f, 0f, 0f, second))
        // Back to where it started, which no edit in the app can produce and a file can.
        second.addOnwardLeg(Leg(5f, 180f, 0f, survey.origin))

        val stations = survey.getAllStations()

        assertEquals(2, stations.size)
        assertEquals(setOf("1", "2"), stations.map { it.name }.toSet())
    }

    // ---------------------------------------------------------------------------------------
    // The drawing on top of it
    // ---------------------------------------------------------------------------------------

    /**
     * A cave that size has a drawing to match: thousands of strokes, drawn over many trips.
     *
     * Nothing here was broken when this was written — every operation is linear in the number of
     * details, and the worst of them costs a couple of milliseconds on eight thousand strokes. The
     * test exists so it stays that way, because the ones that would not be linear are easy to
     * write: a hit test that measures bounds it has already measured, an erase that rebuilds the
     * list, an undo that copies the sketch.
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
        // A tap lands on the stroke it is nearest to, out of eight thousand.
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

    /** And so does looking for something in it. */
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
