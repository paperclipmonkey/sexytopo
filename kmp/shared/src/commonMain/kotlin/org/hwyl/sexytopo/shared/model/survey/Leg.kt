package org.hwyl.sexytopo.shared.model.survey

import org.hwyl.sexytopo.shared.math.adjustAngle

/**
 * A leg holds the polar reading as taken. A leg whose destination is [Station.NULL_STATION] is a
 * splay; one with a real destination is a tree edge.
 *
 * Two subtleties that are easy to lose in a port and are relied on by the exporters:
 *  - inclination accepts a 270..360 "theodolite" band as well as the usual -90..90
 *  - [wasShotBackwards] records that the reading was taken from the far end
 *
 * Identity is reference-based, so `Space` maps key on the leg object.
 */
class Leg(
    val distance: Float,
    val azimuth: Float,
    val inclination: Float,
    val destination: Station = Station.NULL_STATION,
    val promotedFrom: Array<Leg> = NO_LEGS,
    val wasShotBackwards: Boolean = false,
) {

    var comment: String = ""

    init {
        require(isDistanceLegal(distance)) { "Distance should be positive; actual $distance" }
        require(isAzimuthLegal(azimuth)) {
            "Azimuth should be at least 0 and less than 360; actual=$azimuth"
        }
        require(isInclinationLegal(inclination)) {
            "Inclination should be up to +-90; actual=$inclination"
        }
    }

    fun hasDestination(): Boolean = destination !== Station.NULL_STATION

    fun wasPromoted(): Boolean = promotedFrom.isNotEmpty()

    fun hasComment(): Boolean = comment.isNotEmpty()

    fun reverse(): Leg {
        val adjustedAzimuth = adjustAngle(azimuth, 180f)
        val leg =
            if (hasDestination()) {
                Leg(
                    distance,
                    adjustedAzimuth,
                    -inclination,
                    destination,
                    promotedFrom,
                    !wasShotBackwards,
                )
            } else {
                Leg(
                    distance,
                    adjustedAzimuth,
                    -inclination,
                    wasShotBackwards = !wasShotBackwards,
                )
            }
        leg.comment = comment
        return leg
    }

    fun rotate(delta: Float): Leg = adjustAzimuth(adjustAngle(azimuth, delta))

    fun adjustAzimuth(newAzimuth: Float): Leg =
        if (hasDestination()) {
            Leg(distance, newAzimuth, inclination, destination, promotedFrom)
        } else {
            Leg(distance, newAzimuth, inclination)
        }

    fun toSplay(): Leg =
        Leg(distance, azimuth, inclination, wasShotBackwards = wasShotBackwards)

    override fun toString(): String = buildString {
        append("(D").append(distance)
        append(" A").append(azimuth)
        append(" I").append(inclination)
        if (hasDestination()) append(" -> ").append(destination.name)
        if (wasShotBackwards) append("< ")
        append(")")
    }

    companion object {
        const val MIN_DISTANCE = 0
        const val MIN_AZIMUTH = 0
        const val MAX_AZIMUTH = 360
        const val MIN_INCLINATION = -90
        const val MAX_INCLINATION = 90
        const val MIN_THEODOLITE_INC = 270
        const val MAX_THEODOLITE_INC = 360

        val NO_LEGS: Array<Leg> = emptyArray()

        fun isDistanceLegal(distance: Float): Boolean = distance >= MIN_DISTANCE

        fun isAzimuthLegal(azimuth: Float): Boolean =
            azimuth >= MIN_AZIMUTH && azimuth < MAX_AZIMUTH

        fun isInclinationLegal(inclination: Float): Boolean =
            (inclination in MIN_INCLINATION.toFloat()..MAX_INCLINATION.toFloat()) ||
                (inclination in MIN_THEODOLITE_INC.toFloat()..MAX_THEODOLITE_INC.toFloat())

        fun upgradeSplayToConnectedLeg(
            splay: Leg,
            destination: Station,
            promotedFrom: Array<Leg> = NO_LEGS,
        ): Leg {
            val leg =
                Leg(
                    splay.distance,
                    splay.azimuth,
                    splay.inclination,
                    destination,
                    promotedFrom,
                    splay.wasShotBackwards,
                )
            leg.comment = splay.comment
            return leg
        }
    }
}
