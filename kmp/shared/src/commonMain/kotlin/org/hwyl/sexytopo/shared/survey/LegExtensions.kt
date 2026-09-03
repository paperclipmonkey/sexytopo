package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station

/**
 * Re-expresses a backsight as the equivalent foresight: azimuth turned through 180 degrees and
 * inclination negated, so a reading taken from the far end can be compared with, and averaged
 * against, the foresight down the same leg.
 *
 * Two things the original does that are easy to "fix" by accident, so are reproduced here:
 *  - it does **not** flip `wasShotBackwards`; the corrected reading always comes out as a forward
 *    shot regardless of the source leg's flag (unlike [Leg.reverse], which does flip it);
 *  - it carries `promotedFrom` over unchanged, even though those constituent readings are still in
 *    the original orientation.
 *
 * A leg whose inclination is in the 270..360 theodolite band cannot be negated into a legal
 * inclination, so this throws for such legs.
 */
fun Leg.asBacksight(destination: Station = Station.NULL_STATION): Leg {
    val backAzimuth = adjustAngle(azimuth, 180.0f)
    val leg = Leg(distance, backAzimuth, -1 * inclination, destination, promotedFrom)
    leg.comment = comment
    return leg
}
