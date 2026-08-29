package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.math.averageAzimuths
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.PI
import kotlin.math.cos

/**
 * Chooses the bearing to slice a passage at, from whatever the survey knows.
 *
 * Ported from `control/util/CrossSectioner`. The heuristic is simple and worth stating: in the
 * middle of a passage the section faces the average of the way in and the way on; at a dead end or
 * a junction only the incoming leg is meaningful; at the origin only the outgoing one is.
 */
object CrossSectioner {

    fun section(survey: Survey, station: Station): CrossSection =
        CrossSection(station, angleOfSection(survey, station))

    fun angleOfSection(survey: Survey, station: Station): Float {
        val incoming = if (station === survey.origin) 0 else 1
        val outgoing = station.getConnectedOnwardLegs().size

        return when {
            incoming == 1 && outgoing == 1 ->
                averageAzimuths(incomingAzimuth(survey, station), outgoingAzimuth(station))
            // A dead end, or a junction with several ways on: only the way in is meaningful.
            incoming == 1 -> incomingAzimuth(survey, station)
            // Sectioning at the origin, where there is no way in.
            outgoing == 1 -> outgoingAzimuth(station)
            // The origin with no onward legs, or several: nothing to go on.
            else -> 0f
        }
    }

    /**
     * How far any splay reaches from the station in the horizontal plane.
     *
     * Used to place a section clear of the passage it belongs to. Purely vertical splays contribute
     * nothing, and a station with no splays gives 0.
     */
    fun horizontalRadius(station: Station): Float =
        station.getUnconnectedOnwardLegs()
            .maxOfOrNull { splay -> splay.distance * cos(splay.inclination * PI / 180).toFloat() }
            ?: 0f

    /** The Java swallows a null referring leg here, having seen it reported in the wild. */
    private fun incomingAzimuth(survey: Survey, station: Station): Float =
        survey.getReferringLeg(station)?.azimuth ?: 0f

    private fun outgoingAzimuth(station: Station): Float =
        station.getConnectedOnwardLegs().first().azimuth
}
