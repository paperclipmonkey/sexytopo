package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Names new stations. Ported from `control/util/StationNamer`, together with the one naming helper
 * it uses from `control/util/TextTools` ([advanceLastNumber]).
 *
 * The rule is deliberately simple: a new station takes its parent's name with the last number in
 * it bumped, repeatedly, until the name is unused anywhere in the survey. Surveying a straight
 * passage from "1" therefore gives 2, 3, 4...; branching off "1" again in a survey that already
 * reaches "4" gives "5", because 2, 3 and 4 are taken. Branch *suffixes* are supported only in the
 * sense that a hand-typed name like "S2-1.1" keeps its shape and advances its trailing number to
 * "S2-1.2": there is no automatic "1a"/"1b" branch lettering in SexyTopo.
 */
object StationNamer {

    /** The origin is always station "1". */
    fun generateOriginName(): String = "1"

    fun generateNextStationName(survey: Survey, originatingStation: Station): String =
        advanceNumberIfNotUnique(survey, originatingStation.name)

    /** Bumps [candidateName]'s last number until it collides with nothing in the survey. */
    fun advanceNumberIfNotUnique(survey: Survey, candidateName: String): String {
        val allNames = getAllStationNames(survey)
        var candidate = candidateName
        while (candidate in allNames) {
            candidate = advanceLastNumber(candidate)
        }
        return candidate
    }

    fun getAllStationNames(survey: Survey): Set<String> =
        survey.getAllStations().mapTo(mutableSetOf()) { it.name }

    /**
     * Increments the last run of digits in a name, preserving anything either side of it and any
     * zero padding. Ported from `control/util/TextTools.advanceLastNumber`.
     *
     * Worked examples from the Java tests:
     *  - "S1" -> "S2", "1" -> "2"
     *  - "S2-1.1" -> "S2-1.2" (only the final run of digits moves)
     *  - "foo" -> "foo1" (no digits at all: a 1 is appended)
     *  - "a99f" -> "a100f" (the run need not be at the end)
     *  - "a01f" -> "a02f", "a09f" -> "a10f" (padding is kept, but never widened)
     *  - "" -> "1"
     */
    fun advanceLastNumber(originatingName: String): String {
        if (originatingName.isEmpty()) {
            return "1"
        }

        // Scan backwards for the last run of digits: lastDigitChar latches onto the first digit
        // seen from the right, firstDigitChar keeps sliding left while digits continue, and the
        // scan stops at the first non-digit once a run has been found.
        var lastDigitChar = -1
        var firstDigitChar = -1
        for (i in originatingName.indices.reversed()) {
            val c = originatingName[i]
            if (lastDigitChar == -1 && c.isDigit()) {
                lastDigitChar = i
            }
            if (c.isDigit()) {
                firstDigitChar = i
            }
            if (!c.isDigit() && firstDigitChar > -1) {
                break
            }
        }

        if (lastDigitChar == -1) {
            return originatingName + "1"
        }

        val oldDigitString = originatingName.substring(firstDigitChar, lastDigitChar + 1)
        val newValue = oldDigitString.toInt() + 1
        var newDigitString = newValue.toString()
        val lengthDifference = oldDigitString.length - newDigitString.length
        if (lengthDifference > 0) {
            newDigitString = "0".repeat(lengthDifference) + newDigitString
        }
        return originatingName.substring(0, firstDigitChar) +
            newDigitString +
            originatingName.substring(lastDigitChar + 1)
    }
}
