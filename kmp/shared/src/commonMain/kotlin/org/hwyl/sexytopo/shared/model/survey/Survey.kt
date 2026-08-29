package org.hwyl.sexytopo.shared.model.survey

import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Sketch

/**
 * Ported from `org.hwyl.sexytopo.model.survey.Survey`, minus the Android storage coupling.
 *
 * Note what is deliberately absent: the Java original holds a `DocumentFile` for its save location
 * and derives equals/hashCode from that Android `content://` URI. That is exactly the Storage
 * Access Framework leak into the domain model that an iOS port has to remove, so this port carries
 * an opaque [location] string instead and leaves identity reference-based.
 *
 * A survey is a tree, not a general graph: there is no loop closure and no network adjustment.
 */
class Survey(var name: String = DEFAULT_NAME) {

    var origin: Station = Station(ORIGIN_NAME)

    var activeStation: Station = origin

    /** Platform-neutral replacement for the Android DocumentFile directory. */
    var location: String? = null

    var trip: Trip? = null

    private val legsInChronoOrder: MutableList<Leg> = mutableListOf()

    var planSketch: Sketch = Sketch()

    var elevationSketch: Sketch = Sketch()

    fun getSketch(projection: Projection2D): Sketch =
        when (projection) {
            Projection2D.EXTENDED_ELEVATION -> elevationSketch
            else -> planSketch
        }

    fun isOrigin(station: Station): Boolean = station === origin

    fun addLegRecord(leg: Leg) {
        legsInChronoOrder.add(leg)
    }

    fun getAllLegsInChronoOrder(): List<Leg> = legsInChronoOrder.toList()

    val mostRecentLeg: Leg?
        get() = legsInChronoOrder.lastOrNull()

    fun getAllStations(): List<Station> {
        val stations = mutableListOf<Station>()
        collectStations(origin, stations)
        return stations
    }

    private fun collectStations(station: Station, into: MutableList<Station>) {
        into.add(station)
        for (leg in station.onwardLegs) {
            if (leg.hasDestination()) {
                collectStations(leg.destination, into)
            }
        }
    }

    fun getAllLegs(): List<Leg> {
        val legs = mutableListOf<Leg>()
        for (station in getAllStations()) {
            legs.addAll(station.onwardLegs)
        }
        return legs
    }

    fun getStationByName(name: String): Station? = getAllStations().firstOrNull { it.name == name }

    override fun toString(): String = name

    companion object {
        const val ORIGIN_NAME = "1"
        const val DEFAULT_NAME = "Untitled"
    }
}

/** Minimal trip metadata; the Android original also carries licence and copyright fields. */
class Trip {
    var surveyDate: String? = null
    var team: List<TeamEntry> = emptyList()

    class TeamEntry(val name: String, val roles: List<String> = emptyList())
}
