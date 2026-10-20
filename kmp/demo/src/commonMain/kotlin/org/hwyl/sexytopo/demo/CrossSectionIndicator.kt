package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.CrossSectioner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mark drawn over a station to say it has a cross-section: a line standing for the plane of
 * the section seen edge-on, with an arrowhead off one end saying which way the section faces.
 *
 * `CrossSectionIndicator`. Only the geometry, in screen coordinates, so it can be worked out and
 * tested without a canvas; [drawCrossSectionIndicator] draws it.
 */
internal class CrossSectionIndicator
private constructor(
    val start: Coord2D,
    val end: Coord2D,
    /** The arrowhead's corner on the line a little way in from [start]. */
    val arrowInner: Coord2D,
    /** Its corner at the end of the line, which is [start] itself. */
    val arrowOuter: Coord2D,
    val arrowTip: Coord2D,
) {

    private fun mirroredAbout(x: Float): CrossSectionIndicator {
        fun mirror(point: Coord2D) = Coord2D(2 * x - point.x, point.y)
        return CrossSectionIndicator(
            mirror(start),
            mirror(end),
            mirror(arrowInner),
            mirror(arrowOuter),
            mirror(arrowTip),
        )
    }

    companion object {
        private const val ARROW_LENGTH_FRACTION = 0.4f
        private const val ARROW_INNER_OFFSET_FRACTION = 0.05f

        // Angles on the elevation for atAngle, where the page is read like a compass rose: 0 is up
        private const val FACING_RIGHT = 90f
        private const val FACING_DOWN = 180f

        /**
         * An indicator for a section facing [angleDegrees]: a line [length] long through
         * [centre], running across the way the section faces, with an arrowhead on the end to
         * the left as you look that way, pointing the way it faces.
         *
         * On the plan the angle is a compass bearing with 0 up the page. A compass bearing `a` is
         * the screen direction `(sin a, -cos a)`, and the section's plane is that turned a right
         * angle, `(cos a, sin a)` - so the cosine and sine of the bearing give the plane directly,
         * and the arrow uses `a - 90`, which turns it back. The elevation has no bearings; see
         * [onElevation] and [lookingDown].
         */
        fun atAngle(centre: Coord2D, length: Float, angleDegrees: Float): CrossSectionIndicator {
            val angle = angleDegrees.toRadians()
            val alongPlane = Coord2D(cos(angle), sin(angle))
            val start = centre - alongPlane.scale(length / 2)
            val end = centre + alongPlane.scale(length / 2)

            val lineLength = getDistance(start, end)
            val inner = start + alongPlane.scale(lineLength * ARROW_INNER_OFFSET_FRACTION)
            val arrowAngle = adjustAngle(angleDegrees, -90f).toRadians()
            val tip =
                start +
                    Coord2D(cos(arrowAngle), sin(arrowAngle)).scale(
                        lineLength * ARROW_LENGTH_FRACTION,
                    )

            return CrossSectionIndicator(start, end, inner, start, tip)
        }

        /**
         * A vertical section on the elevation: an upright line through [centre], the section seen
         * edge-on, with the arrowhead always at the top and pointing the way it faces.
         */
        fun onElevation(centre: Coord2D, length: Float, facingRight: Boolean): CrossSectionIndicator {
            // Facing right the arrowhead is on the top end already, so facing left is its mirror
            val right = atAngle(centre, length, FACING_RIGHT)
            return if (facingRight) right else right.mirroredAbout(centre.x)
        }

        /**
         * A horizontal section: a level line through [centre] with the arrowhead pointing down,
         * since the section is looked at from above.
         */
        fun lookingDown(centre: Coord2D, length: Float): CrossSectionIndicator =
            atAngle(centre, length, FACING_DOWN)

        /**
         * The indicator for [detail] at [centre] on [projection], facing the way it faces or, for
         * the one being turned, the way it is being turned to ([previewAngle]). On the plan that
         * is its bearing. The elevation has no bearings, so a vertical section faces left or right
         * on the page, whichever its bearing is nearer to the way the survey goes on; and a
         * horizontal section is looked down on wherever it is. `SketchView.getIndicator`.
         */
        fun of(
            detail: CrossSectionDetail,
            centre: Coord2D,
            length: Float,
            projection: Projection2D,
            survey: Survey,
            previewAngle: Float? = null,
        ): CrossSectionIndicator {
            val crossSection = detail.crossSection
            if (!crossSection.isRotatable) return lookingDown(centre, length)

            val angle = previewAngle ?: crossSection.angle
            if (projection == Projection2D.EXTENDED_ELEVATION) {
                val facingRight =
                    CrossSectioner.isFacingRightOnElevation(survey, crossSection.station, angle)
                return onElevation(centre, length, facingRight)
            }
            return atAngle(centre, length, angle)
        }

        private fun Float.toRadians(): Float = this * PI.toFloat() / 180f
    }
}
