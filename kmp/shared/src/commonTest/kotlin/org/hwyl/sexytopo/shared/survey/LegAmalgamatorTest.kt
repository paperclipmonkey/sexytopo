package org.hwyl.sexytopo.shared.survey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm

/**
 * Ported from `control/util/amalgamation/LegAmalgamatorTest`. The Java tests run with preferences
 * unset, which makes GeneralPreferences hand back the defaults (0.05m / 1.7 degrees / 0.05), so
 * they are run here against [SurveySettings.DEFAULT].
 */
class LegAmalgamatorTest {

    private val settings = SurveySettings.DEFAULT

    private fun compatible(algorithm: LegAmalgamationAlgorithm, legs: List<Leg>) =
        algorithm.areReadingsCompatible(legs, settings)

    /**
     * A pair of essentially-identical steep readings whose azimuths differ wildly. This is the case
     * from issue #321: near the vertical a tiny endpoint variation produces a large azimuth swing.
     */
    private val steepButSame = listOf(Leg(10.0f, 4f, 89f), Leg(10.0f, 356f, 89f))

    @Test
    fun angularRejectsSteepReadingsThatAreReallyTheSame() {
        assertFalse(compatible(LegAmalgamationAlgorithm.ANGULAR, steepButSame))
    }

    @Test
    fun cartesianAcceptsSteepReadingsThatAreReallyTheSame() {
        assertTrue(compatible(LegAmalgamationAlgorithm.CARTESIAN, steepButSame))
    }

    @Test
    fun pairwiseAcceptsSteepReadingsThatAreReallyTheSame() {
        assertTrue(compatible(LegAmalgamationAlgorithm.PAIRWISE, steepButSame))
    }

    @Test
    fun cartesianRejectsGenuinelyDifferentReadings() {
        val different = listOf(Leg(10.0f, 90f, 0f), Leg(10.0f, 100f, 0f))
        assertFalse(compatible(LegAmalgamationAlgorithm.CARTESIAN, different))
    }

    @Test
    fun cartesianAcceptsCloseFlatReadings() {
        val close = listOf(Leg(10.0f, 90f, 0f), Leg(10.0f, 90.1f, 0f))
        assertTrue(compatible(LegAmalgamationAlgorithm.CARTESIAN, close))
    }

    @Test
    fun pairwiseRejectsGenuinelyDifferentReadings() {
        val different = listOf(Leg(10.0f, 90f, 0f), Leg(10.0f, 100f, 0f))
        assertFalse(compatible(LegAmalgamationAlgorithm.PAIRWISE, different))
    }

    @Test
    fun pairwiseToleranceScalesWithLength() {
        // The same absolute endpoint gap is acceptable on a long leg but not on a short one,
        // because the pairwise error is relative to leg length.
        val longLegs = listOf(Leg(50.0f, 90f, 0f), Leg(50.0f, 90.5f, 0f))
        val shortLegs = listOf(Leg(1.0f, 90f, 0f), Leg(1.0f, 110f, 0f))
        assertTrue(compatible(LegAmalgamationAlgorithm.PAIRWISE, longLegs))
        assertFalse(compatible(LegAmalgamationAlgorithm.PAIRWISE, shortLegs))
    }

    @Test
    fun angularAveragesComponentsIndependently() {
        val legs = listOf(Leg(10.0f, 90f, 0f), Leg(20.0f, 90f, 0f))
        val averaged = LegAmalgamationAlgorithm.ANGULAR.average(legs)
        assertEquals(15.0f, averaged.distance, DELTA)
        assertEquals(90.0f, averaged.azimuth, DELTA)
        assertEquals(0.0f, averaged.inclination, DELTA)
    }

    @Test
    fun vectorAverageOfSteepReadingsGivesSensibleAzimuth() {
        // Averaging azimuths 4 and 356 as scalars would give a meaningless ~180; the vector average
        // should give roughly 0/360 (north), and the distance and steep inclination should be
        // preserved.
        val averaged = LegAmalgamationAlgorithm.CARTESIAN.average(steepButSame)
        assertEquals(10.0f, averaged.distance, 0.01f)
        assertEquals(89.0f, averaged.inclination, 0.1f)
        val azimuth = averaged.azimuth
        assertTrue(azimuth < 10 || azimuth > 350, "Expected azimuth near north but was $azimuth")
    }

    @Test
    fun unknownPreferenceValueFallsBackToAngular() {
        assertEquals(
            LegAmalgamationAlgorithm.ANGULAR,
            LegAmalgamationAlgorithm.fromPreferenceValue("angular"),
        )
        assertEquals(
            LegAmalgamationAlgorithm.PAIRWISE,
            LegAmalgamationAlgorithm.fromPreferenceValue("pairwise"),
        )
        assertEquals(
            LegAmalgamationAlgorithm.ANGULAR,
            LegAmalgamationAlgorithm.fromPreferenceValue("nonsense"),
        )
    }

    @Test
    fun defaultTolerancesMatchTheAndroidPreferences() {
        assertEquals(LegAmalgamationAlgorithm.ANGULAR, settings.legAmalgamationAlgorithm)
        assertEquals(0.05f, settings.maxDistanceDelta)
        assertEquals(1.7f, settings.maxAngleDelta)
        assertEquals(0.1f, settings.maxEndpointDelta)
        assertEquals(0.05f, settings.maxPairwiseError)
        assertEquals(3, settings.numberOfRepeatsForNewStation)
    }

    companion object {
        private const val DELTA = 0.0001f
    }
}
