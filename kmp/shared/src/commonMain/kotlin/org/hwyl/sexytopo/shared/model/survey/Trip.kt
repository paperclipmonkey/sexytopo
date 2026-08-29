package org.hwyl.sexytopo.shared.model.survey

/**
 * A calendar date, with no time and no zone.
 *
 * Ported in place of the `java.util.Date` the Java `Trip` holds. That substitution is deliberate,
 * not incidental: a trip date is what the surveyor wrote in the book, so it has no time of day and
 * no timezone, and `Date` only ever gets away with representing it because the app immediately
 * formats it back through `SimpleDateFormat("yyyy-MM-dd")` in the device's default zone. Carry the
 * instant across a zone boundary — export a survey on a phone set to UTC+13, read it on one set to
 * UTC-8 — and the date the Java app shows can differ by a day from the date it saved. Storing the
 * three fields the format actually round-trips removes that class of bug from the port, and makes
 * the file bytes reproducible in a test without a clock.
 *
 * There is no validation: the Java `SimpleDateFormat` is lenient (it rolls "2026-13-45" forward
 * into 2027), and nothing downstream cares, so an out-of-range field is kept verbatim rather than
 * either rejected or silently rolled.
 */
data class SurveyDate(val year: Int, val month: Int, val day: Int) {

    /** The `yyyy-MM-dd` the file format uses. */
    override fun toString(): String =
        "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}"

    companion object {
        /** Parses `yyyy-MM-dd`, returning null for anything else. */
        fun parseOrNull(text: String?): SurveyDate? {
            val parts = text?.trim()?.split("-") ?: return null
            if (parts.size != 3) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            return SurveyDate(year, month, day)
        }

        private fun pad(value: Int, width: Int): String =
            value.toString().padStart(width, '0')
    }
}

/**
 * Who was on the trip and what they did.
 *
 * Ported from `model/survey/Trip`. This is the metadata block that turns a set of numbers into a
 * survey somebody can publish: the date it was surveyed, the date the passage was found (often
 * earlier, and often the same, hence [explorationDateLinked]), who was there, what instrument was
 * used, and the copyright and licence terms the data is released under.
 *
 * The port keeps the Java's data model but not its mutable-bean shape where a value class will do.
 * [team] is a list rather than a set: order is meaningful (the book-keeper is conventionally
 * listed first) and the file format preserves it.
 */
class Trip(surveyDate: SurveyDate) {

    /** The day the survey was made. Always written to file. */
    var surveyDate: SurveyDate = surveyDate

    /**
     * The day the passage was originally explored, when that differs from the survey date.
     *
     * Null means "not recorded". Note the two-flag encoding the file format uses and this port
     * keeps: [explorationDateLinked] true means "explored on the day it was surveyed", in which
     * case this stays null and [hasExplorationDate] is still true.
     */
    var explorationDate: SurveyDate? = null

    var explorationDateLinked: Boolean = true

    var team: List<TeamEntry> = emptyList()

    var comments: String = ""

    var instrument: String = ""

    var copyrightHolder: String = ""

    var licence: String = ""

    fun hasExplorationDate(): Boolean = !explorationDateLinked || explorationDate != null

    fun hasInstrument(): Boolean = instrument.isNotBlank()

    fun hasCopyrightHolder(): Boolean = copyrightHolder.isNotBlank()

    fun hasLicence(): Boolean = licence.isNotBlank()

    /** A deep copy; [team] entries are copied too, so editing one trip cannot alter the other. */
    fun copy(): Trip {
        val copy = Trip(surveyDate)
        copy.explorationDate = explorationDate
        copy.explorationDateLinked = explorationDateLinked
        copy.team = team.map { TeamEntry(it.name, it.roles.toList()) }
        copy.comments = comments
        copy.instrument = instrument
        copy.copyrightHolder = copyrightHolder
        copy.licence = licence
        return copy
    }

    /**
     * The trip to pre-fill a follow-on survey with: same team, instrument and terms, but a fresh
     * date and no comments.
     *
     * The Java stamps `new Date()` here; this port takes the date as a parameter, because common
     * code has no clock and a caller that wants "today" is a platform caller anyway.
     */
    fun toNextTrip(newSurveyDate: SurveyDate): Trip {
        val next = copy()
        next.surveyDate = newSurveyDate
        next.comments = ""
        return next
    }

    override fun equals(other: Any?): Boolean =
        other is Trip &&
            other.surveyDate == surveyDate &&
            other.explorationDate == explorationDate &&
            other.explorationDateLinked == explorationDateLinked &&
            other.comments == comments &&
            other.instrument == instrument &&
            other.copyrightHolder == copyrightHolder &&
            other.licence == licence &&
            other.team == team

    override fun hashCode(): Int {
        var result = surveyDate.hashCode()
        result = 31 * result + (explorationDate?.hashCode() ?: 0)
        result = 31 * result + explorationDateLinked.hashCode()
        result = 31 * result + comments.hashCode()
        result = 31 * result + instrument.hashCode()
        result = 31 * result + copyrightHolder.hashCode()
        result = 31 * result + licence.hashCode()
        result = 31 * result + team.hashCode()
        return result
    }

    /**
     * One person and what they were doing.
     *
     * A caver can hold several roles at once — the usual case being one person on both the book
     * and the instruments on a two-person trip — so [roles] is a list, not a single value.
     */
    data class TeamEntry(val name: String, val roles: List<Role> = emptyList()) {
        fun hasRoles(): Boolean = roles.isNotEmpty()
    }

    /**
     * The jobs on a survey trip.
     *
     * The Java carries an Android string-resource id per constant for the UI label; that is a
     * platform concern, so the port keeps only the names — which are what the file format stores.
     */
    enum class Role {
        /** Wrote the readings down (and, in practice, drew the sketch). */
        BOOK,

        /** Operated the instrument. */
        INSTRUMENTS,

        /** Held the other end of the tape, or otherwise made themselves useful. */
        DOG,

        /** Found the passage in the first place. */
        EXPLORATION,
        ;

        companion object {
            /** Unrecognised names read as null so a file from a newer version still loads. */
            fun fromNameOrNull(name: String?): Role? = entries.firstOrNull { it.name == name }
        }
    }
}
