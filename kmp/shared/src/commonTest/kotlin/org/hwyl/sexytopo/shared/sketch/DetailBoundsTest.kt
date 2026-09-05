package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from `PathDetailTest`: whether a stroke's box overlaps a rectangle, which is what the
 * eraser and the selection tool ask of every stroke on every drag.
 *
 * The Java asks the path (`intersectsRectangle`); here the path's box answers, through
 * `DetailBounds.intersects`, and the four cases are the Java's four. The port's function existed
 * without a test against it, which the ledger in `PortedTestsTest` is there to notice.
 */
class DetailBoundsTest {

    private fun boundsOf(path: PathDetail): DetailBounds =
        path.path.fold(DetailBounds.EMPTY) { box, point -> box + point }

    @Test
    fun aRectangleOverTheStrokeIntersects() {
        val path = PathDetail(Coord2D.ORIGIN, Colour.BLACK)
        assertTrue(boundsOf(path).intersects(Coord2D(0f, 0f), Coord2D(1f, 1f)))
    }

    @Test
    fun aRectangleAwayFromTheStrokeDoesNot() {
        val path = PathDetail(Coord2D.ORIGIN, Colour.BLACK)
        assertFalse(boundsOf(path).intersects(Coord2D(1f, 1f), Coord2D(2f, 2f)))
    }

    @Test
    fun aRectangleThatEntersTheBoundingBoxIntersects() {
        val path = PathDetail(Coord2D.ORIGIN, Colour.BLACK)
        path.lineTo(Coord2D(1.5f, 1.5f))
        assertTrue(boundsOf(path).intersects(Coord2D(1f, 1f), Coord2D(2f, 2f)))
    }

    @Test
    fun aRectangleOutsideTheBoundingBoxDoesNot() {
        val path = PathDetail(Coord2D.ORIGIN, Colour.BLACK)
        path.lineTo(Coord2D(1.5f, 1.5f))
        assertFalse(boundsOf(path).intersects(Coord2D(2f, 2f), Coord2D(3f, 3f)))
    }
}
