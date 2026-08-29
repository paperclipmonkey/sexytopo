package org.hwyl.sexytopo.shared.math

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Line
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Ported from `control/util/Space3DTransformer`.
 *
 * Walks the survey tree from the origin, accumulating each station's position.
 */
open class Space3DTransformer {

    fun transformTo3D(survey: Survey): Space<Coord3D> = transformTo3D(survey.origin)

    fun transformTo3D(root: Station): Space<Coord3D> {
        val space = Space<Coord3D>()
        update(space, root, Coord3D.ORIGIN)
        return space
    }

    protected open fun update(space: Space<Coord3D>, station: Station, coord3D: Coord3D) {
        space.addStation(station, coord3D)
        for (leg in station.onwardLegs) {
            update(space, leg, coord3D)
        }
    }

    protected open fun update(space: Space<Coord3D>, leg: Leg, start: Coord3D) {
        val end = transform(start, leg)
        space.addLeg(leg, Line(start, end))
        if (leg.hasDestination()) {
            update(space, leg.destination, end)
        }
    }

    open fun transform(start: Coord3D, leg: Leg): Coord3D = toCartesian(start, leg)
}

/**
 * Ported from `control/util/Space3DTransformerForElevation`.
 *
 * The extended elevation "unrolls" the cave onto a single plane so one dimension can be dropped.
 * Each leg's real bearing is replaced by whichever one puts its horizontal run where the
 * destination station's direction wants it — north to run rightwards, south leftwards, east into
 * the page (leaving only the height change visible) — and each splay is rotated by the accumulated
 * bearing change of its branch so it still points sensibly relative to the passage.
 *
 * Small, subtle, and the most cave-surveying-specific piece of maths in the codebase.
 */
class Space3DTransformerForElevation : Space3DTransformer() {

    override fun update(space: Space<Coord3D>, station: Station, coord3D: Coord3D) {
        update(space, station, coord3D, 0f)
    }

    private fun update(
        space: Space<Coord3D>,
        station: Station,
        coord3D: Coord3D,
        rotation: Float,
    ) {
        space.addStation(station, coord3D)
        for (leg in station.onwardLegs) {
            if (leg.hasDestination()) {
                updateLeg(space, leg, coord3D)
            } else {
                updateSplay(space, leg, coord3D, rotation)
            }
        }
    }

    private fun updateLeg(space: Space<Coord3D>, leg: Leg, start: Coord3D) {
        val destination = leg.destination
        val projected = projectLeg(leg, destination.extendedElevationDirection)

        val end = toCartesian(start, projected)
        space.addLeg(leg, Line(start, end))

        val rotation = projected.azimuth - leg.azimuth
        update(space, destination, end, rotation)
    }

    private fun updateSplay(
        space: Space<Coord3D>,
        leg: Leg,
        start: Coord3D,
        rotation: Float,
    ) {
        val adjustedLeg = leg.rotate(rotation)
        val end = toCartesian(start, adjustedLeg)
        space.addLeg(leg, Line(start, end))
    }

    private fun projectLeg(leg: Leg, direction: ExtendedElevationDirection): Leg =
        when (direction) {
            ExtendedElevationDirection.RIGHT -> leg.adjustAzimuth(0f)
            ExtendedElevationDirection.LEFT -> leg.adjustAzimuth(180f)
            ExtendedElevationDirection.VERTICAL -> leg.adjustAzimuth(90f)
        }
}
