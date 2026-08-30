package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate

/**
 * Export to Compass `.dat`, the format used by Larry Fish's Windows cave-survey suite.
 *
 * Ported from `control/io/thirdparty/compass/CompassExporter`, which the Android app marks
 * `Experimental`. Three things about the format are easy to get wrong and are pinned by tests here:
 *
 *  - **CRLF, everywhere.** Compass is a DOS-lineage program and the Java writes `\r\n` explicitly
 *    rather than relying on a platform separator.
 *  - **Decimal feet, not metres.** Every length is multiplied by [METRES_TO_FEET]. A survey exported
 *    in metres would be silently wrong by a factor of 3.28 rather than rejected.
 *  - **A form feed terminates the survey.** The trailing FF is not decoration; it is how the format
 *    marks the end of one survey in a file that may hold several.
 *
 * LRUD is written as `-9.99` throughout - the sentinel for "not recorded" - because SexyTopo models
 * passage dimensions as splays rather than as per-station left/right/up/down.
 */
object CompassExporter {

    const val METRES_TO_FEET = 3.28084

    const val FILE_EXTENSION = "dat"

    /** Compass's "no reading" sentinel for a LRUD field. */
    private const val NO_LRUD = "-9.99"

    /**
     * Excludes a shot from cave-length totals. Splays are wall detail rather than passage, so
     * counting them would inflate the surveyed length considerably.
     */
    private const val SPLAY_FLAGS = "#|L#"

    private const val CRLF = "\r\n"

    /** ASCII form feed: the end-of-survey marker. */
    const val FORM_FEED = '\u000C'

    /**
     * [fallbackDate] is used when the survey has no trip recorded.
     *
     * The Java substitutes `new Date()` - today - which makes the output depend on when it ran, and
     * is why its exporters have no golden test for that path. Passing it in keeps the behaviour
     * available to a caller that wants it while leaving this function deterministic.
     */
    fun export(survey: Survey, fallbackDate: SurveyDate? = null): String {
        val date = survey.trip?.surveyDate ?: fallbackDate
        val builder = StringBuilder(1024)

        builder.append("SexyTopo Export").append(CRLF)
        builder.append("SURVEY NAME: ${survey.name}").append(CRLF)
        builder.append("SURVEY DATE: ${formatDate(date)}\tCOMMENT: ").append(CRLF)
        builder.append("SURVEY TEAM:").append(CRLF).append(CRLF)
        builder
            .append("DECLINATION: 0.00\tFORMAT: DMMDLRUDLADNF\tCORRECTIONS: 0.00 0.00 0.00")
            .append(CRLF)
        builder.append(CRLF)
        builder
            .append("FROM\tTO\tLENGTH\tBEARING\tINC\tLEFT\tUP\tDOWN\tRIGHT\tFLAGS\tCOMMENTS")
            .append(CRLF)
        builder.append(CRLF)

        val splayNamer = SplayStationNamer()

        for ((from, leg) in SurvexTherionWriter.chronologicalEntries(survey)) {
            val to =
                if (leg.hasDestination()) {
                    leg.destination.name
                } else {
                    splayNamer.nameFor(from)
                }
            val distanceInFeet = leg.distance * METRES_TO_FEET

            builder.append(from.name).append('\t')
            builder.append(to).append('\t')
            builder.append(formatFixed(distanceInFeet, 2)).append('\t')
            builder.append(formatFixed(leg.azimuth, 2)).append('\t')
            builder.append(formatFixed(leg.inclination, 2)).append('\t')
            // Left, Up, Down, Right - in that order, which is not the order the header names them.
            builder.append(NO_LRUD).append('\t')
            builder.append(NO_LRUD).append('\t')
            builder.append(NO_LRUD).append('\t')
            builder.append(NO_LRUD).append('\t')
            if (!leg.hasDestination()) {
                builder.append(SPLAY_FLAGS)
            }
            builder.append("\t\t").append(CRLF) // the empty comments field
        }

        builder.append(FORM_FEED)
        return builder.toString()
    }

    /** `MM dd yyyy`, the Java's `SimpleDateFormat` pattern. Blank when there is no date. */
    private fun formatDate(date: SurveyDate?): String {
        if (date == null) return ""
        val month = date.month.toString().padStart(2, '0')
        val day = date.day.toString().padStart(2, '0')
        return "$month $day ${date.year}"
    }
}

/**
 * Invents a station name for the far end of a splay, because Compass has no anonymous stations.
 *
 * `A53ss003` is the fourth splay recorded off station A53. The counter resets whenever the *from*
 * station changes, matching the Java - including its consequence: a surveyor who shoots splays off
 * a station, moves on, and later comes back to shoot more gets a second run numbered from zero, so
 * two different splays share a name. That is a defect in the Android app rather than a porting
 * choice, and it is reproduced rather than fixed because changing the naming would change files
 * existing Compass users have already imported.
 *
 * Unlike the Java's, this state is per-export rather than per-exporter instance. There the fields
 * live on a long-lived exporter and are never reset at the start of a run, so consecutive exports
 * can influence each other's numbering.
 */
private class SplayStationNamer {

    private var currentFrom: Station? = null
    private var splayCount = 0

    fun nameFor(from: Station): String {
        // Reference identity, as in the Java: Station has no equals override, and a survey is a
        // tree of distinct station objects.
        if (from !== currentFrom) {
            currentFrom = from
            splayCount = 0
        }
        val index = splayCount++
        return "${from.name}ss${index.toString().padStart(3, '0')}"
    }
}
