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
 *
 * Beyond the tree itself the survey keeps a *chronological record* of every leg ever recorded,
 * in the order the instrument delivered it. The survey engine leans on it heavily: the triple-shot
 * detector looks at the last N readings, undo pops the record from the end, and promotion of a
 * splay into the leg above it searches backwards through it. The record is therefore a second,
 * independent index over the same Leg objects, and the two can disagree — hence
 * [checkSurveyIntegrity], which prunes record entries pointing at stations no longer in the tree.
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

    /**
     * Dirty flag, cleared by the caller when it persists the survey.
     *
     * The Java original ANDs this with a per-sketch saved flag; the KMP [Sketch] has no such flag
     * yet, so this is the survey-level flag alone. Clearing it does *not* clear [isAutosaved],
     * matching the original: a save is not an autosave, but any edit invalidates both.
     */
    var isSaved: Boolean = true
        set(value) {
            field = value
            if (!value) {
                isAutosaved = false
            }
        }

    var isAutosaved: Boolean = true

    fun getSketch(projection: Projection2D): Sketch =
        when (projection) {
            Projection2D.EXTENDED_ELEVATION -> elevationSketch
            else -> planSketch
        }

    fun isOrigin(station: Station): Boolean = station === origin

    // -----------------------------------------------------------------------------------------
    // The chronological record
    // -----------------------------------------------------------------------------------------

    fun addLegRecord(leg: Leg) {
        legsInChronoOrder.add(leg)
    }

    fun getAllLegsInChronoOrder(): List<Leg> = legsInChronoOrder.toList()

    val mostRecentLeg: Leg?
        get() = legsInChronoOrder.lastOrNull()

    /**
     * The last [n] recorded legs, oldest first; fewer than [n] if that is all there is.
     *
     * The Java original returns a live sub-list view of its backing stack; this returns a copy.
     * Every caller in the original reads the readings out (or copies them into a promotedFrom
     * array) before mutating the record, so the two behave identically — and the copy removes the
     * concurrent-modification trap the view carries.
     */
    fun getLastNLegs(n: Int): List<Leg> = legsInChronoOrder.takeLast(n)

    /** Removes the first record entry that *is* [leg] (identity, as Leg has no equals). */
    fun removeLegRecord(leg: Leg) {
        legsInChronoOrder.remove(leg)
    }

    /** Swaps [oldLeg] for [newLeg] in place, so the edited leg keeps its chronological position. */
    fun replaceLegInRecord(oldLeg: Leg, newLeg: Leg) {
        val oldIndex = legsInChronoOrder.indexOf(oldLeg)
        legsInChronoOrder.add(oldIndex + 1, newLeg)
        legsInChronoOrder.removeAt(oldIndex)
        checkSurveyIntegrity()
    }

    /**
     * Undoes the most recent recorded leg: pops it off the record and unhooks it from whichever
     * station it hangs off. Silently does nothing when there is nothing to undo.
     */
    fun undoAddLeg() {
        if (legsInChronoOrder.isEmpty()) {
            return
        }

        val toDelete = legsInChronoOrder.removeAt(legsInChronoOrder.lastIndex)
        traverseLegs { station, leg ->
            if (leg === toDelete) {
                station.onwardLegs.remove(toDelete)
                true
            } else {
                false
            }
        }

        if (toDelete.hasDestination()) {
            checkSurveyIntegrity()
        }

        isSaved = false
    }

    /**
     * Drops record entries whose destination station is no longer reachable from the origin, and
     * re-homes the active station if it has itself been detached. Called after any edit that can
     * orphan part of the tree.
     */
    fun checkSurveyIntegrity() {
        val reachableStations = getAllStations()

        legsInChronoOrder.removeAll { leg ->
            leg.hasDestination() && !reachableStations.contains(leg.destination)
        }

        if (!reachableStations.contains(activeStation)) {
            activeStation = findNewActiveStation()
        }
    }

    /** The most recently recorded leg's destination, falling back to the origin. */
    private fun findNewActiveStation(): Station =
        legsInChronoOrder.lastOrNull { it.hasDestination() }?.destination ?: origin

    // -----------------------------------------------------------------------------------------
    // Tree queries
    // -----------------------------------------------------------------------------------------

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

    /** The origin, then each station in the order the leg that created it was recorded. */
    fun getAllStationsInChronoOrder(): List<Station> =
        listOf(origin) + legsInChronoOrder.filter { it.hasDestination() }.map { it.destination }

    fun getAllLegs(): List<Leg> {
        val legs = mutableListOf<Leg>()
        for (station in getAllStations()) {
            legs.addAll(station.onwardLegs)
        }
        return legs
    }

    fun getStationByName(name: String): Station? = getAllStations().firstOrNull { it.name == name }

    /** The leg that creates [station], or null for the origin (which no leg creates). */
    fun getReferringLeg(station: Station): Leg? {
        if (station === origin) {
            return null
        }
        var found: Leg? = null
        traverseLegs { _, leg ->
            if (leg.destination === station) {
                found = leg
                true
            } else {
                false
            }
        }
        return found
    }

    /** The station [leg] hangs off, or null if it is not in the tree. */
    fun getOriginatingStation(leg: Leg): Station? {
        var found: Station? = null
        traverseStations { station ->
            if (station.onwardLegs.contains(leg)) {
                found = station
                true
            } else {
                false
            }
        }
        return found
    }

    /** Depth-first over every (originating station, leg) pair; stops when [visit] returns true. */
    fun traverseLegs(visit: (Station, Leg) -> Boolean): Boolean = traverseLegs(origin, visit)

    /** Depth-first over every station; stops when [visit] returns true. */
    fun traverseStations(visit: (Station) -> Boolean): Boolean = traverseStations(origin, visit)

    override fun toString(): String = name

    companion object {
        const val ORIGIN_NAME = "1"
        const val DEFAULT_NAME = "Untitled"

        /**
         * Ported from `control/util/SurveyTools.traverseLegs`. Iterates a snapshot of each
         * station's legs so a visitor may unhook the leg it is looking at, which is how the
         * original's edit, undo and reverse operations all work.
         */
        fun traverseLegs(station: Station, visit: (Station, Leg) -> Boolean): Boolean {
            for (leg in station.onwardLegs.toList()) {
                if (visit(station, leg)) {
                    return true
                }
                if (leg.hasDestination() && traverseLegs(leg.destination, visit)) {
                    return true
                }
            }
            return false
        }

        /** Ported from `control/util/SurveyTools.traverseStations`; visits [station] first. */
        fun traverseStations(station: Station, visit: (Station) -> Boolean): Boolean {
            if (visit(station)) {
                return true
            }
            for (leg in station.getConnectedOnwardLegs()) {
                if (traverseStations(leg.destination, visit)) {
                    return true
                }
            }
            return false
        }
    }
}

/** Minimal trip metadata; the Android original also carries licence and copyright fields. */
class Trip {
    var surveyDate: String? = null
    var team: List<TeamEntry> = emptyList()

    class TeamEntry(val name: String, val roles: List<String> = emptyList())
}
