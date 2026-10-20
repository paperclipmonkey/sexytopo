package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mark on a station that has a cross-section, and which way it says the section faces.
 * `CrossSectionIndicatorTest`, plus the plan's indicator, which this port drew before the
 * geometry had a class of its own to test, and how each kind of section chooses its mark.
 */
class CrossSectionIndicatorTest {

    private fun assertClose(expected: Float, actual: Float, message: String = "") =
        assertTrue(abs(expected - actual) < 1e-4f, "$message: expected $expected but was $actual")

    @Test
    fun onTheElevationTheArrowheadIsAtTheTopFacingEitherWay() {
        for (facingRight in listOf(true, false)) {
            val indicator = CrossSectionIndicator.onElevation(Coord2D(100f, 50f), 20f, facingRight)

            val top = min(indicator.start.y, indicator.end.y)
            val message = if (facingRight) "right" else "left"
            assertClose(top, indicator.arrowOuter.y, message)
            assertEquals(facingRight, indicator.arrowTip.x > indicator.arrowOuter.x, message)
        }
    }

    @Test
    fun onTheElevationTheLineStandsUpright() {
        val indicator = CrossSectionIndicator.onElevation(Coord2D(100f, 50f), 20f, true)

        assertClose(100f, indicator.start.x)
        assertClose(100f, indicator.end.x)
        assertClose(20f, max(indicator.start.y, indicator.end.y) - min(indicator.start.y, indicator.end.y))
    }

    @Test
    fun aHorizontalSectionIsLookedDownOn() {
        val indicator = CrossSectionIndicator.lookingDown(Coord2D(100f, 50f), 20f)

        // A level line, with the arrowhead pointing down the page
        assertClose(50f, indicator.start.y)
        assertClose(50f, indicator.end.y)
        assertTrue(indicator.arrowTip.y > indicator.arrowOuter.y)
    }

    /** On the plan the arrowhead points along the bearing: north is up the page. */
    @Test
    fun onThePlanTheArrowheadPointsAlongTheBearing() {
        val facingNorth = CrossSectionIndicator.atAngle(Coord2D(0f, 0f), 20f, 0f)
        assertTrue(facingNorth.arrowTip.y < facingNorth.arrowOuter.y)
        assertClose(0f, facingNorth.start.y)
        assertClose(0f, facingNorth.end.y)

        val facingEast = CrossSectionIndicator.atAngle(Coord2D(0f, 0f), 20f, 90f)
        assertTrue(facingEast.arrowTip.x > facingEast.arrowOuter.x)
    }

    private fun northwardPassage(): Survey {
        val survey = Survey("T")
        repeat(3) { SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f)) }
        return survey
    }

    /**
     * On the elevation the mark is worked out from the section's bearing, so it follows a flip of
     * the survey's direction rather than staying where it was drawn.
     */
    @Test
    fun onTheElevationAVerticalSectionsMarkFollowsTheSurvey() {
        val survey = northwardPassage()
        val station = survey.getStationByName("2")!!
        val detail = CrossSectionDetail(Coord2D(5f, 5f), CrossSection(station, 0f))
        fun facesRight(): Boolean {
            val indicator =
                CrossSectionIndicator.of(
                    detail,
                    Coord2D(0f, 0f),
                    20f,
                    Projection2D.EXTENDED_ELEVATION,
                    survey,
                )
            return indicator.arrowTip.x > indicator.arrowOuter.x
        }

        assertTrue(facesRight(), "facing along a survey drawn to the right")

        SurveyUpdater.setExtendedElevationDirection(
            survey,
            survey.origin,
            ExtendedElevationDirection.LEFT,
        )
        assertTrue(!facesRight(), "the same bearing, with the survey drawn to the left")
    }

    /** Only the section being turned previews a new direction; the others stay as they are. */
    @Test
    fun aPreviewAngleTurnsTheMark() {
        val survey = northwardPassage()
        val detail = CrossSectionDetail(Coord2D(5f, 5f), CrossSection(survey.origin, 0f))

        val turned =
            CrossSectionIndicator.of(detail, Coord2D(0f, 0f), 20f, Projection2D.PLAN, survey, 90f)

        assertTrue(turned.arrowTip.x > turned.arrowOuter.x, "previewed as facing east")
    }

    @Test
    fun aHorizontalSectionIsLookedDownOnOnThePlanToo() {
        val survey = northwardPassage()
        val detail = CrossSectionDetail(Coord2D(5f, 5f), CrossSection.horizontal(survey.origin))

        for (projection in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
            val indicator =
                CrossSectionIndicator.of(detail, Coord2D(0f, 0f), 20f, projection, survey)
            assertTrue(indicator.arrowTip.y > indicator.arrowOuter.y, projection.name)
        }
    }
}
