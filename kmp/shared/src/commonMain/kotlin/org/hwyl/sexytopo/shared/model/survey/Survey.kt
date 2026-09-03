package org.hwyl.sexytopo.shared.model.survey

import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Sketch

/**
 * The Java original holds a `DocumentFile` for its save location; this port carries an opaque
 * [location] string instead and leaves identity reference-based.
 *
 * A survey is a tree, not a general graph: there is no loop closure and no network adjustment.
 *
 * Beyond the tree itself the survey keeps a *chronological record* of every leg ever recorded. The
 * triple-shot detector, undo, and splay promotion all rely on it, and it is a second, independent
 * index over the same Leg objects that can disagree with the tree — hence [checkSurveyIntegrity],
 * which prunes record entries pointing at stations no longer in the tree.
 */
class Survey(name: String = DEFAULT_NAME) {

    /**
     * Sanitised on every write: the name becomes a directory name, so characters that would split
     * a path or a filename are stripped and a name left empty falls back.
     */
    var name: String = sanitiseName(name)
        set(value) {
            field = sanitiseName(value)
        }


    var origin: Station = Station(ORIGIN_NAME)

    var activeStation: Station = origin

    var location: String? = null

    var trip: Trip? = null

    private val legsInChronoOrder: MutableList<Leg> = mutableListOf()

    var planSketch: Sketch = Sketch()

    var elevationSketch: Sketch = Sketch()

    /**
     * Dirty flag, cleared by the caller when it persists the survey.
     *
     * Setting it false also clears [isAutosaved]; setting it true does not — a save is not an
     * autosave, but any edit invalidates both.
     */
    var isSaved: Boolean = true
        set(value) {
            field = value
            if (!value) {
                isAutosaved = false
            }
        }

    var isAutosaved: Boolean = true

    /**
     * The sketch drawn on one of the two projections a survey has.
     *
     * Only [Projection2D.PLAN] and [Projection2D.EXTENDED_ELEVATION] are drawable — asking for any
     * other projection is a caller bug, so this throws rather than quietly handing back the plan.
     */
    fun getSketch(projection: Projection2D): Sketch =
        when (projection) {
            Projection2D.PLAN -> planSketch
            Projection2D.EXTENDED_ELEVATION -> elevationSketch
            // Which is what `Projection2D.isDrawable` reports, and the two must agree: the
            // export screen asks that question to decide how many scrap files to name.
            else -> throw IllegalArgumentException("Not a drawable projection: $projection")
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
     * Returns a copy rather than a live view, to avoid a concurrent-modification trap.
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
     * re-homes the active station if it has itself been detached.
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

    private fun findNewActiveStation(): Station =
        legsInChronoOrder.lastOrNull { it.hasDestination() }?.destination ?: origin

    // -----------------------------------------------------------------------------------------
    // Tree queries
    // -----------------------------------------------------------------------------------------

    /**
     * Every station, depth first from the origin.
     *
     * A loop rather than recursion, for the reason set out in `Space3DTransformer`: a cave passage
     * is a chain, so recursion is as deep as the survey is long and overflows the stack on a long
     * one. Legs are pushed reversed so they come off in the order they were recorded.
     *
     * ## Why it remembers where it has been
     *
     * A survey the app builds is a tree and cannot contain a cycle, but a survey it *reads* can: two
     * stations sharing a name collapse into a leg that points at its own source. A plain loop
     * without the `seen` set would meet that by never finishing — worse than the stack overflow it
     * replaces, because [checkSurveyIntegrity] is what would report the file as broken, and it
     * starts by calling this.
     *
     * Identity, not equality: two stations that happen to share a name are still two stations.
     */
    fun getAllStations(): List<Station> {
        val stations = mutableListOf<Station>()
        val seen = HashSet<Station>()
        val pending = ArrayDeque<Station>()
        pending.addLast(origin)
        while (pending.isNotEmpty()) {
            val station = pending.removeLast()
            if (!seen.add(station)) continue
            stations.add(station)
            for (leg in station.onwardLegs.asReversed()) {
                if (leg.hasDestination()) pending.addLast(leg.destination)
            }
        }
        return stations
    }

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
        const val DEFAULT_NAME = "Unsaved Survey"

        /** Characters that cannot appear in a survey name, because it is used as a folder name. */
        val FORBIDDEN_CHARS = charArrayOf(':', '.', '\n', '\r', '/', '\\')

        private fun sanitiseName(name: String): String {
            var sanitised = name
            for (c in FORBIDDEN_CHARS) sanitised = sanitised.replace(c.toString(), "")
            return sanitised.ifEmpty { "blank" }
        }

        /** Iterates a snapshot of each station's legs so a visitor may unhook the leg it is looking at. */
        fun traverseLegs(station: Station, visit: (Station, Leg) -> Boolean): Boolean {
            val pending = ArrayDeque<Station>()
            pending.addLast(station)
            while (pending.isNotEmpty()) {
                val at = pending.removeLast()
                val legs = at.onwardLegs.toList()
                for (leg in legs) {
                    if (visit(at, leg)) return true
                }
                for (leg in legs.asReversed()) {
                    if (leg.hasDestination()) pending.addLast(leg.destination)
                }
            }
            return false
        }

        /**
         * Used for one thing: refusing to re-hang a leg on a station that the leg itself leads to.
         * `SurveyUpdater.moveLeg` does not check this — a move into its own subtree makes a cycle
         * and the next traversal never comes back.
         */
        fun isInSubtree(root: Station?, station: Station?): Boolean {
            if (root == null || station == null) return false
            return traverseStations(root) { it === station }
        }

        /** Visits [station] first. */
        fun traverseStations(station: Station, visit: (Station) -> Boolean): Boolean {
            val pending = ArrayDeque<Station>()
            pending.addLast(station)
            while (pending.isNotEmpty()) {
                val at = pending.removeLast()
                if (visit(at)) return true
                for (leg in at.getConnectedOnwardLegs().asReversed()) {
                    pending.addLast(leg.destination)
                }
            }
            return false
        }
    }
}
