package org.hwyl.sexytopo.shared.model

import org.hwyl.sexytopo.shared.model.graph.Projection2D
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Looking a projection up by its abbreviation. Ported from `Projection2DTest`; upstream passes it
 * to the cross-section editor to say which sketch the section is in, and anything it does not
 * know means the plan, which is where every section was before the elevation had them.
 */
class Projection2DTest {

    @Test
    fun everyProjectionRoundTripsThroughItsAbbreviation() {
        for (projection in Projection2D.entries) {
            assertSame(projection, Projection2D.fromAbbreviation(projection.abbreviation))
        }
    }

    @Test
    fun theExtendedElevationIsFound() {
        assertSame(
            Projection2D.EXTENDED_ELEVATION,
            Projection2D.fromAbbreviation(Projection2D.EXTENDED_ELEVATION.abbreviation),
        )
    }

    @Test
    fun nothingMeansThePlan() {
        assertSame(Projection2D.PLAN, Projection2D.fromAbbreviation(null))
    }

    @Test
    fun anythingUnknownMeansThePlan() {
        assertSame(Projection2D.PLAN, Projection2D.fromAbbreviation("not-a-projection"))
        assertSame(Projection2D.PLAN, Projection2D.fromAbbreviation(""))
    }
}
