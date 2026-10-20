package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.sketch.boundsOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two kinds of cross-section, vertical and horizontal: what each is and how each flattens its
 * station's splays. Ported from `CrossSectionTest`, with the new cases of `CrossSectionDetailTest`
 * that are about a section's orientation.
 */
class CrossSectionOrientationTest {

    private val delta = 1e-4f

    private fun stationWithSplay(distance: Float, azimuth: Float, inclination: Float): Station {
        val station = Station("A1")
        station.addOnwardLeg(Leg(distance, azimuth, inclination))
        return station
    }

    private fun endOfOnlySplay(crossSection: CrossSection): Coord2D =
        crossSection.getProjection().legMap.values.single().end

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < delta, "expected $expected but was $actual")

    private fun assertCoord(x: Float, y: Float, actual: Coord2D) {
        assertClose(x, actual.x)
        assertClose(y, actual.y)
    }

    @Test
    fun verticalIsTheDefaultOrientation() {
        val crossSection = CrossSection(Station("A1"), 45f)

        assertEquals(CrossSection.Orientation.VERTICAL, crossSection.orientation)
        assertTrue(crossSection.isRotatable)
        assertClose(45f, crossSection.angle)
        assertEquals(Projection2D.CROSS_SECTION, crossSection.projectionType)
    }

    @Test
    fun aHorizontalSectionHasNoAngleAndCannotBeTurned() {
        val station = Station("A1")
        val crossSection = CrossSection.horizontal(station)

        assertEquals(CrossSection.Orientation.HORIZONTAL, crossSection.orientation)
        assertFalse(crossSection.isRotatable)
        assertClose(0f, crossSection.angle)
        assertSame(station, crossSection.station)
        assertEquals(Projection2D.PLAN, crossSection.projectionType)
    }

    @Test
    fun aHorizontalSectionPutsNorthAtTheTop() {
        // Screen y grows downwards, so a splay running north has a negative y
        assertCoord(0f, -2f, endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 0f, 0f))))
        assertCoord(0f, 2f, endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 180f, 0f))))
    }

    @Test
    fun aHorizontalSectionPutsEastOnTheRight() {
        assertCoord(2f, 0f, endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 90f, 0f))))
        assertCoord(-2f, 0f, endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 270f, 0f))))
    }

    @Test
    fun aHorizontalSectionForeshortensSlopingSplays() {
        val expected = (2 * cos(30 * PI / 180)).toFloat()
        assertCoord(
            0f,
            -expected,
            endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 0f, 30f))),
        )
    }

    @Test
    fun aHorizontalSectionCollapsesVerticalSplays() {
        assertCoord(0f, 0f, endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 0f, 90f))))
        assertCoord(0f, 0f, endOfOnlySplay(CrossSection.horizontal(stationWithSplay(2f, 0f, -90f))))
    }

    @Test
    fun aHorizontalSectionKeepsTheStationAtTheOrigin() {
        val station = stationWithSplay(2f, 90f, 0f)

        assertEquals(
            Coord2D.ORIGIN,
            CrossSection.horizontal(station).getProjection().stationMap[station],
        )
    }

    @Test
    fun aHorizontalSectionIncludesEverySplay() {
        val station = stationWithSplay(2f, 90f, 0f)
        station.addOnwardLeg(Leg(3f, 0f, 0f))
        station.addOnwardLeg(Leg(4f, 180f, 0f))

        assertEquals(3, CrossSection.horizontal(station).getProjection().legMap.size)
    }

    @Test
    fun aVerticalSectionFacingNorthIsAsItWas() {
        // Facing north, a splay to the east is to the right; one straight up is straight up
        assertCoord(3f, 0f, endOfOnlySplay(CrossSection(stationWithSplay(3f, 90f, 0f), 0f)))
        assertCoord(0f, -2f, endOfOnlySplay(CrossSection(stationWithSplay(2f, 0f, 90f), 0f)))
    }

    @Test
    fun aVerticalSectionFacingEastIsAsItWas() {
        // Facing east, a splay to the south is to the right
        assertCoord(3f, 0f, endOfOnlySplay(CrossSection(stationWithSplay(3f, 180f, 0f), 90f)))
    }

    // From `CrossSectionDetailTest`

    @Test
    fun aHorizontalSectionsExtentCoversItsProjectedSplays() {
        val station = Station("A1")
        station.addOnwardLeg(Leg(5f, 90f, 0f))

        val detail = CrossSectionDetail(Coord2D(10f, 20f), CrossSection.horizontal(station))

        // The splay runs 5 to the east of the centre; the minimum extent is 1 either way
        val bounds = boundsOf(detail)
        assertClose(15f, bounds.right)
        assertClose(9f, bounds.left)
    }

    @Test
    fun movingASectionKeepsItsOrientation() {
        val detail = CrossSectionDetail(Coord2D.ORIGIN, CrossSection.horizontal(Station("A1")))

        val moved = detail.translate(Coord2D(5f, 7f))

        assertEquals(CrossSection.Orientation.HORIZONTAL, moved.crossSection.orientation)
    }

    @Test
    fun aVerticalSectionCanBeTurned() {
        val subSketch = Sketch()
        val detail =
            CrossSectionDetail(Coord2D(1f, 2f), CrossSection(Station("A1"), 10f), subSketch)

        val rotated = detail.withAngle(80f)

        assertClose(80f, rotated.crossSection.angle)
        assertEquals(Coord2D(1f, 2f), rotated.position)
        assertSame(subSketch, rotated.sketch)
    }

    @Test
    fun aHorizontalSectionIsLeftAloneByTurning() {
        val detail = CrossSectionDetail(Coord2D(1f, 2f), CrossSection.horizontal(Station("A1")))

        assertSame(detail, detail.withAngle(80f))
    }
}
