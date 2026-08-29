package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.math.Space3DTransformerForElevation
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ported from `Space3DTransformerForElevationTest` in the Android app.
 *
 * The extended-elevation unroll is the most cave-specific maths in the codebase, so it is the
 * thing a port most needs to prove it got right.
 */
class ExtendedElevationTest {

    private val transformer = Space3DTransformerForElevation()

    private fun surveyWithOneLeg(
        azimuth: Float,
        inclination: Float,
        direction: ExtendedElevationDirection,
    ): Survey {
        val survey = Survey()
        SurveyBuilder.updateWithNewStation(survey, Leg(5f, azimuth, inclination))
        survey.getStationByName("2")!!.extendedElevationDirection = direction
        return survey
    }

    @Test
    fun rightLegProjectsToPositiveY() {
        val survey = surveyWithOneLeg(90f, 0f, ExtendedElevationDirection.RIGHT)
        val station2 = transformer.transformTo3D(survey).stationMap[survey.getStationByName("2")]!!
        assertTrue(station2.y > 0, "Right leg should produce positive y")
    }

    @Test
    fun rightLegZeroesXComponent() {
        val survey = surveyWithOneLeg(90f, 0f, ExtendedElevationDirection.RIGHT)
        val station2 = transformer.transformTo3D(survey).stationMap[survey.getStationByName("2")]!!
        assertTrue(abs(station2.x) < DELTA, "x should be zero, was ${station2.x}")
    }

    @Test
    fun leftLegProjectsToNegativeY() {
        val survey = surveyWithOneLeg(90f, 0f, ExtendedElevationDirection.LEFT)
        val station2 = transformer.transformTo3D(survey).stationMap[survey.getStationByName("2")]!!
        assertTrue(station2.y < 0, "Left leg should produce negative y")
    }

    @Test
    fun leftLegZeroesXComponent() {
        val survey = surveyWithOneLeg(90f, 0f, ExtendedElevationDirection.LEFT)
        val station2 = transformer.transformTo3D(survey).stationMap[survey.getStationByName("2")]!!
        assertTrue(abs(station2.x) < DELTA, "x should be zero, was ${station2.x}")
    }

    @Test
    fun verticalLegDoesNotExtendAlongSection() {
        // The section is laid out along y, so a vertical leg must not travel along it.
        val survey = surveyWithOneLeg(45f, 60f, ExtendedElevationDirection.VERTICAL)
        val station2 = transformer.transformTo3D(survey).stationMap[survey.getStationByName("2")]!!
        assertTrue(abs(station2.y) < DELTA, "y should be zero, was ${station2.y}")
    }

    @Test
    fun verticalLegKeepsItsHeight() {
        val survey = surveyWithOneLeg(45f, 60f, ExtendedElevationDirection.VERTICAL)
        val station2 = transformer.transformTo3D(survey).stationMap[survey.getStationByName("2")]!!
        // 5m at 60 degrees up
        assertTrue(station2.z > 4.3f && station2.z < 4.4f, "z should be ~4.33, was ${station2.z}")
    }

    @Test
    fun directionPropagationRules() {
        // Left and right describe where the survey goes and carry down the subtree; vertical
        // applies only to its own leg.
        assertTrue(ExtendedElevationDirection.LEFT.propagates)
        assertTrue(ExtendedElevationDirection.RIGHT.propagates)
        assertTrue(!ExtendedElevationDirection.VERTICAL.propagates)
        assertEquals(ExtendedElevationDirection.RIGHT, ExtendedElevationDirection.DEFAULT)
    }

    @Test
    fun everyLegIsInPlaneExceptVerticalOnes() {
        val right = surveyWithOneLeg(90f, 0f, ExtendedElevationDirection.RIGHT)
        val rightLeg = right.origin.getConnectedOnwardLegs().first()
        assertTrue(Projection2D.EXTENDED_ELEVATION.isLegInPlane(rightLeg))

        val vertical = surveyWithOneLeg(45f, 60f, ExtendedElevationDirection.VERTICAL)
        val verticalLeg = vertical.origin.getConnectedOnwardLegs().first()
        assertTrue(!Projection2D.EXTENDED_ELEVATION.isLegInPlane(verticalLeg))
    }

    companion object {
        private const val DELTA = 0.0001f
    }
}
