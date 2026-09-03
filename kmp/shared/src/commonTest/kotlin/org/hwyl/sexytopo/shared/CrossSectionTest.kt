package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.TestSurveys
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ported from `CrossSectionerTest` (the first three cases, expectations unchanged) and extended to
 * cover the projection and the sub-sketch, which the Java has no unit tests for.
 */
class CrossSectionTest {

    /** SexyTopoConstants.ALLOWED_DOUBLE_DELTA. */
    private val allowedDelta = 0.0001f

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = allowedDelta) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")

    @Test
    fun straightNorthCrossSection() {
        val survey = TestSurveys.createStraightNorth()
        val s2 = assertNotNull(survey.getStationByName("2"))
        assertClose(0f, CrossSectioner.angleOfSection(survey, s2))
    }

    @Test
    fun straightSouthCrossSection() {
        val survey = TestSurveys.createStraightSouth()
        val s2 = assertNotNull(survey.getStationByName("2"))
        assertClose(180f, CrossSectioner.angleOfSection(survey, s2))
    }

    @Test
    fun crossSectionSpanningZeroBoundary() {
        // Issue #176: legs at 350 and 10 degrees give a section at 0, not at 180.
        val survey = TestSurveys.createSpanningZeroBoundary()
        val s2 = assertNotNull(survey.getStationByName("2"))
        assertClose(0f, CrossSectioner.angleOfSection(survey, s2))
    }

    @Test
    fun midPassageTheSectionBisectsTheCorner() {
        val survey = TestSurveys.createRightRight()
        val s2 = assertNotNull(survey.getStationByName("2"))
        // In at 0, on at 90: the section cuts the corner square.
        assertClose(45f, CrossSectioner.angleOfSection(survey, s2))
    }

    @Test
    fun atADeadEndOnlyTheWayInCounts() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 45f, 0f))
        assertClose(45f, CrossSectioner.angleOfSection(survey, survey.getStationByName("2")!!))
    }

    @Test
    fun atTheOriginOnlyTheWayOnCounts() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 120f, 0f))
        assertClose(120f, CrossSectioner.angleOfSection(survey, survey.origin))
    }

    @Test
    fun anIsolatedOriginHasNothingToGoOn() {
        val survey = Survey("X")
        assertClose(0f, CrossSectioner.angleOfSection(survey, survey.origin))
    }

    @Test
    fun anOriginWithSeveralWaysOnAlsoHasNothingToGoOn() {
        val survey = Survey("X")
        val origin = survey.origin
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        survey.activeStation = origin
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 270f, 0f))
        assertClose(0f, CrossSectioner.angleOfSection(survey, origin))
    }

    @Test
    fun aJunctionFallsBackToTheWayIn() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 30f, 0f))
        val junction = survey.activeStation
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        survey.activeStation = junction
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 270f, 0f))

        // Two ways on, so the average is meaningless; the incoming leg decides.
        assertClose(30f, CrossSectioner.angleOfSection(survey, junction))
    }

    @Test
    fun splaysDoNotCountAsWaysOn() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f))
        val station = survey.activeStation
        SurveyBuilder.addSplay(survey, station, Leg(2f, 90f, 0f))
        // Still a dead end as far as the heuristic is concerned.
        assertClose(0f, CrossSectioner.angleOfSection(survey, station))
    }

    /** Station "2" of an eastward passage, with wall, wall and roof splays hung off it. */
    private fun eastwardPassageWithSplays(): Pair<Survey, Station> {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(2f, 0f, 0f)) // north: left wall
        SurveyBuilder.addSplay(survey, station, Leg(3f, 180f, 0f)) // south: right wall
        SurveyBuilder.addSplay(survey, station, Leg(1f, 0f, 90f)) // straight up: roof
        return survey to station
    }

    @Test
    fun theProfilePutsTheStationAtTheOriginAndSplaysAroundIt() {
        val (survey, station) = eastwardPassageWithSplays()
        val projection = CrossSectioner.section(survey, station).getProjection()

        assertEquals(1, projection.stationMap.size)
        assertEquals(Coord2D.ORIGIN, projection.stationMap[station])
        assertEquals(3, projection.legMap.size, "one line per splay")
        assertTrue(projection.legMap.values.all { it.start == Coord2D.ORIGIN })
    }

    @Test
    fun theProfileFacesAlongThePassage() {
        val (survey, station) = eastwardPassageWithSplays()
        // Dead end, so the section faces the way in: due east.
        assertClose(90f, CrossSectioner.angleOfSection(survey, station))
        val projection = CrossSectioner.section(survey, station).getProjection()

        // The splays are keyed on the *rotated* leg, so look them up by rotated bearing.
        fun end(azimuth: Float, inclination: Float): Coord2D =
            projection.legMap.entries
                .first { it.key.azimuth == azimuth && it.key.inclination == inclination }
                .value
                .end

        // Looking east along the passage: the northern wall is on the viewer's left (negative x),
        // the southern wall on the right, and the roof splay is up the screen (negative y).
        assertEquals(Coord2D(-2f, 0f), end(270f, 0f))
        assertEquals(Coord2D(3f, 0f), end(90f, 0f))
        assertEquals(Coord2D(0f, -1f), end(270f, 90f))
    }

    @Test
    fun aSplayShotAlongThePassageCollapsesOntoTheStation() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, 90f, 0f))
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(4f, 90f, 0f)) // straight down the passage

        val projection = CrossSectioner.section(survey, station).getProjection()
        assertEquals(Coord2D.ORIGIN, projection.legMap.values.single().end)
    }

    @Test
    fun horizontalRadiusIgnoresVerticalSplays() {
        val survey = Survey("X")
        val station = survey.origin
        SurveyBuilder.addSplay(survey, station, Leg(4f, 0f, 0f))
        // Straight up: reaches nowhere horizontally, so must not set the radius.
        SurveyBuilder.addSplay(survey, station, Leg(20f, 0f, 90f))

        assertClose(4f, CrossSectioner.horizontalRadius(station))
    }

    @Test
    fun horizontalRadiusForeshortensASlopingSplay() {
        val survey = Survey("X")
        val station = survey.origin
        SurveyBuilder.addSplay(survey, station, Leg(10f, 0f, 60f))
        // 10 * cos(60) = 5
        assertClose(5f, CrossSectioner.horizontalRadius(station), 0.001f)
    }

    @Test
    fun horizontalRadiusIgnoresConnectedLegs() {
        val survey = Survey("X")
        SurveyBuilder.updateWithNewStation(survey, Leg(50f, 0f, 0f))
        assertClose(0f, CrossSectioner.horizontalRadius(survey.origin))
    }

    @Test
    fun aStationWithNoSplaysHasNoRadius() {
        assertClose(0f, CrossSectioner.horizontalRadius(Survey("X").origin))
    }

    @Test
    fun theDetailProjectionIsRelativeToWhereItWasDropped() {
        val (survey, station) = eastwardPassageWithSplays()
        val position = Coord2D(10f, 20f)
        val detail = CrossSectionDetail(position, CrossSectioner.section(survey, station))

        assertEquals(position, detail.getProjection().stationMap[station])
        assertTrue(detail.getProjection().legMap.values.all { it.start == position })
    }

    @Test
    fun theSubSketchIsCarriedAroundWithTheSection() {
        val (survey, station) = eastwardPassageWithSplays()
        val subSketch = Sketch()
        subSketch.pathDetails.add(PathDetail(listOf(Coord2D(0f, 0f), Coord2D(0f, 6f)), Colour.BLACK))
        val detail = CrossSectionDetail(Coord2D(1f, 2f), CrossSectioner.section(survey, station), subSketch)

        val moved = detail.translate(Coord2D(4f, 4f))
        assertEquals(Coord2D(5f, 6f), moved.position)
        // The sub-sketch is shared, not copied, and stays in station-relative coordinates.
        assertTrue(moved.sketch === subSketch)
        assertEquals(Coord2D(0f, 6f), moved.sketch.pathDetails.single().path.last())
    }

    @Test
    fun rotatingASectionKeepsItsPlaceAndItsSubSketch() {
        val (survey, station) = eastwardPassageWithSplays()
        val subSketch = Sketch()
        val detail = CrossSectionDetail(Coord2D(1f, 2f), CrossSectioner.section(survey, station), subSketch)

        val rotated = detail.withAngle(180f)
        assertEquals(Coord2D(1f, 2f), rotated.position)
        assertClose(180f, rotated.crossSection.angle)
        assertTrue(rotated.station === station)
        assertTrue(rotated.sketch === subSketch)
    }

    @Test
    fun scalingASectionIsANoOp() {
        // Reproduces the original: CrossSectionDetail never overrode scale, so it returns itself.
        val survey = Survey("X")
        val detail = CrossSectionDetail(Coord2D(3f, 4f), CrossSectioner.section(survey, survey.origin))
        assertTrue(detail.scale(10f) === detail)
    }
}
