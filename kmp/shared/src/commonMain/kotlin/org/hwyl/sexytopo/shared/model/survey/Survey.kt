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
class Survey(name: String = DEFAULT_NAME) {

    /**
     * Sanitised on every write, as in the Java: the name becomes a directory name, so characters
     * that would split a path or a filename are stripped and a name left empty falls back.
     */
    var name: String = sanitiseName(name)
        set(value) {
            field = sanitiseName(value)
        }


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

    /**
     * The sketch drawn on one of the two projections a survey has.
     *
     * Only [Projection2D.PLAN] and [Projection2D.EXTENDED_ELEVATION] are drawable — the other
     * projections exist for maths, not for sketching, and a cross-section's drawing lives in its
     * own [org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail]. Asking for one of those is a
     * caller bug, so it throws as in the original rather than quietly handing back the plan and
     * letting the caller's strokes land on the wrong sheet.
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

    /**
     * Every station, depth first from the origin.
     *
     * A loop rather than the original's recursion, for the reason set out in `Space3DTransformer`:
     * a cave passage is a chain, so a recursion is as deep as the survey is long, and a club's
     * survey of a few thousand stations overflows the stack. Legs are pushed reversed so they come
     * off in the order they were recorded, which keeps the order the recursion produced.
     *
     * ## Why it remembers where it has been
     *
     * A survey the app builds is a tree and cannot contain a cycle. A survey it *reads* can: the
     * file formats identify a leg's far end by name, and a file with two stations of the same name
     * — which nothing in the format forbids — collapses them into a leg that points at its own
     * source. The recursion this replaced met that as a stack overflow; a plain loop would meet it
     * by never finishing, which is worse, because [checkSurveyIntegrity] is the very thing that
     * would have reported the file as broken and it starts by calling this.
     *
     * Identity, not equality: `Station` overrides neither `equals` nor `hashCode`, and two
     * different stations that happen to share a name are two stations.
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
        const val DEFAULT_NAME = "Unsaved Survey"

        /** Characters that cannot appear in a survey name, because it is used as a folder name. */
        val FORBIDDEN_CHARS = charArrayOf(':', '.', '\n', '\r', '/', '\\')

        private fun sanitiseName(name: String): String {
            var sanitised = name
            for (c in FORBIDDEN_CHARS) sanitised = sanitised.replace(c.toString(), "")
            return sanitised.ifEmpty { "blank" }
        }

        /**
         * Ported from `control/util/SurveyTools.traverseLegs`. Iterates a snapshot of each
         * station's legs so a visitor may unhook the leg it is looking at, which is how the
         * original's edit, undo and reverse operations all work.
         */
        fun traverseLegs(station: Station, visit: (Station, Leg) -> Boolean): Boolean {
            val pending = ArrayDeque<Station>()
            pending.addLast(station)
            while (pending.isNotEmpty()) {
                val at = pending.removeLast()
                // A snapshot, because the visitor may unhook the leg it is looking at.
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

        /** Ported from `control/util/SurveyTools.traverseStations`; visits [station] first. */
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
