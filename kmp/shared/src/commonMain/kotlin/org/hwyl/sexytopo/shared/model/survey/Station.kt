package org.hwyl.sexytopo.shared.model.survey

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection

/**
 * Ported from `org.hwyl.sexytopo.model.survey.Station`.
 *
 * Identity is deliberately reference-based (no equals/hashCode override), because a survey is a
 * tree of distinct station objects and Space maps are keyed on them.
 */
class Station(name: String) {

    var name: String = sanitiseName(name)
        set(value) {
            field = sanitiseName(value)
        }

    val onwardLegs: MutableList<Leg> = mutableListOf()

    var comment: String = ""

    var extendedElevationDirection: ExtendedElevationDirection = ExtendedElevationDirection.DEFAULT

    constructor(name: String, comment: String) : this(name) {
        this.comment = comment
    }

    fun addOnwardLeg(leg: Leg) {
        onwardLegs.add(leg)
    }

    /** Splays: legs with no destination station. */
    fun getUnconnectedOnwardLegs(): List<Leg> = onwardLegs.filter { !it.hasDestination() }

    /** Full legs: the tree edges. */
    fun getConnectedOnwardLegs(): List<Leg> = onwardLegs.filter { it.hasDestination() }

    fun hasComment(): Boolean = comment.isNotEmpty()

    override fun toString(): String = name

    companion object {
        val FORBIDDEN_CHARS = charArrayOf('\n', '\r')

        /**
         * The sentinel destination marking a splay. Compared by identity, as in the original, where
         * it lives on Survey.
         */
        val NULL_STATION = Station("-")

        private fun sanitiseName(name: String): String {
            var sanitised = name
            for (c in FORBIDDEN_CHARS) {
                sanitised = sanitised.replace(c.toString(), "")
            }
            return sanitised
        }
    }
}
