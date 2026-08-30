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
 *
 * ## Why this is a loop and the original is a recursion
 *
 * The Java recurses, one stack frame per station. A cave is not a bushy tree — a passage is a
 * *chain*, and a chain of stations is a recursion as deep as the survey is long. A club's survey of
 * a few thousand stations therefore overflows the stack, and the first thing that touches it is the
 * plan view: opening the survey crashes the app. Measured here at somewhere between one and three
 * thousand stations on a desktop JVM, and a phone's main thread has a smaller stack than that.
 *
 * An explicit stack costs one list and reads almost the same. The traversal order is preserved
 * exactly — legs are pushed in reverse so they come off in the order they were recorded — because
 * `Space` iteration order feeds the exporters, and two exports of one survey must not differ.
 */
open class Space3DTransformer {

    fun transformTo3D(survey: Survey): Space<Coord3D> = transformTo3D(survey.origin)

    fun transformTo3D(root: Station): Space<Coord3D> {
        val space = Space<Coord3D>()
        walk(space, root, Coord3D.ORIGIN)
        return space
    }

    /**
     * Depth-first from [root], exactly as the recursion visited it, without the stack frames.
     *
     * Left open so the extended elevation can carry its extra accumulator, which is the only thing
     * that subclass changes.
     */
    protected open fun walk(space: Space<Coord3D>, root: Station, origin: Coord3D) {
        val pending = ArrayDeque<Pair<Station, Coord3D>>()
        pending.addLast(root to origin)
        while (pending.isNotEmpty()) {
            val (station, at) = pending.removeLast()
            space.addStation(station, at)
            // Reversed, so that popping from the end visits them in their recorded order.
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

    /**
     * The same loop as the base class, carrying one more thing down the tree.
     *
     * Each station remembers the bearing change its own leg applied, because that is what its
     * splays have to be rotated by to keep pointing sensibly relative to the unrolled passage.
     */
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
