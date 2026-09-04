package org.hwyl.sexytopo.shared.model

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ported from `Coord2DTest` and `Coord3DTest`, one case each.
 *
 * Trivial by design and by Kotlin: a data class equals by value. Kept because the Java had to say
 * so explicitly — its `equals` is hand-written — and a port that dropped the check would have no
 * answer if somebody one day gave these a hand-written `equals` too.
 */
class CoordTest {

    @Test
    fun theOriginEqualsAPointAtZero() {
        assertEquals(Coord2D.ORIGIN, Coord2D(0f, 0f))
    }

    @Test
    fun theOriginEqualsAPointAtZeroInThreeDimensions() {
        assertEquals(Coord3D.ORIGIN, Coord3D(0f, 0f, 0f))
    }
}
