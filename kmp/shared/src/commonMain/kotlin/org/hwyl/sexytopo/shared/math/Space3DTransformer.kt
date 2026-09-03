package org.hwyl.sexytopo.shared.math

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Line
import org.hwyl.sexytopo.shared.model.graph.Space
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Walks the survey tree from the origin, accumulating each station's position, with an explicit
 * stack rather than the Java's recursion: a passage is a *chain*, so a club's survey of a few
 * thousand stations recurses as deep as the survey is long and overflows the stack, and the first
 * thing that touches it is the plan view — opening the survey crashes the app.
 */
open class Space3DTransformer {

    fun transformTo3D(survey: Survey): Space<Coord3D> = transformTo3D(survey.origin)

    fun transformTo3D(root: Station): Space<Coord3D> {
        val space = Space<Coord3D>()
        walk(space, root, Coord3D.ORIGIN)
        return space
    }

    /** Left open so the extended elevation can carry its extra accumulator. */
    protected open fun walk(space: Space<Coord3D>, root: Station, origin: Coord3D) {
        val pending = ArrayDeque<Pair<Station, Coord3D>>()
        pending.addLast(root to origin)
        while (pending.isNotEmpty()) {
            val (station, at) = pending.removeLast()
            space.addStation(station, at)
            for (leg in station.onwardLegs.asReversed()) {
                val end = transform(at, leg)
                space.addLeg(leg, Line(at, end))
                if (leg.hasDestination()) pending.addLast(leg.destination to end)
            }
        }
    }

    open fun transform(start: Coord3D, leg: Leg): Coord3D = toCartesian(start, leg)
}

/**
 * The extended elevation "unrolls" the cave onto a single plane: each leg's real bearing is
 * replaced by whichever one puts its horizontal run where the destination station's direction
 * wants it — north to run rightwards, south leftwards, east into the page — and each splay is
 * rotated by the accumulated bearing change of its branch so it still points sensibly relative to
 * the passage.
 */
class Space3DTransformerForElevation : Space3DTransformer() {

    override fun walk(space: Space<Coord3D>, root: Station, origin: Coord3D) {
        val pending = ArrayDeque<Triple<Station, Coord3D, Float>>()
        pending.addLast(Triple(root, origin, 0f))
        while (pending.isNotEmpty()) {
            val (station, at, rotation) = pending.removeLast()
            space.addStation(station, at)
            for (leg in station.onwardLegs.asReversed()) {
                if (leg.hasDestination()) {
                    val destination = leg.destination
                    val projected = projectLeg(leg, destination.extendedElevationDirection)
                    val end = toCartesian(at, projected)
                    space.addLeg(leg, Line(at, end))
                    pending.addLast(Triple(destination, end, projected.azimuth - leg.azimuth))
                } else {
                    val end = toCartesian(at, leg.rotate(rotation))
                    space.addLeg(leg, Line(at, end))
                }
            }
        }
    }

    private fun projectLeg(leg: Leg, direction: ExtendedElevationDirection): Leg =
        when (direction) {
            ExtendedElevationDirection.RIGHT -> leg.adjustAzimuth(0f)
            ExtendedElevationDirection.LEFT -> leg.adjustAzimuth(180f)
            ExtendedElevationDirection.VERTICAL -> leg.adjustAzimuth(90f)
        }
}
