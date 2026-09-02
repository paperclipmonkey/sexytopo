package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * A whole Survex or Therion file, read into a survey.
 *
 * Ported from `SurvexImporter.toSurvey` and `TherionImporter.parseTh`.
 *
 * The order matters and is not obvious: the passage block is read *before* the centreline,
 * because it is keyed on station names that do not exist yet, and merged *after*, once they do.
 */
object SurveyImporter {

    /**
     * @param name what to call the survey; the file does not reliably say (Survex has `*begin
     *   <name>`, Therion `survey <name>`, but a hand-assembled file may have neither).
     */
    fun read(text: String, format: SurveyFormat, name: String): Survey {
        val survey = Survey(name)

        // A file with no SexyTopo version header is third-party and read the modern way.
        val useLegComments = writtenWithLegComments(text)

        val passageComments = SurvexTherionImporter.parsePassageData(text, format)

        // Only the normal data block: a passage row like "2 - - - - junction" has five fields and
        // no comment character, so parsing the whole file would misread it as a shot and silently
        // drop what was actually a station comment.
        SurvexTherionImporter.parseCentreline(
            normalDataBlock(text, format),
            survey,
            useLegComments,
        )

        SurvexTherionImporter.mergePassageComments(survey, passageComments)

        SurvexTherionImporter.parseMetadata(text, format)?.let { survey.trip = it }

        return survey
    }

    /** Everything under a `data normal` command, up to the next `data` command of any kind. */
    internal fun normalDataBlock(text: String, format: SurveyFormat): String {
        val dataPrefix = "${format.commandChar}data "
        val normalPrefix = "${format.commandChar}data normal"
        val lines = mutableListOf<String>()
        var inBlock = false

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.startsWith(dataPrefix)) {
                inBlock = trimmed.startsWith(normalPrefix)
                continue
            }
            if (inBlock) lines.add(line)
        }

        return lines.joinToString("\n")
    }

    /**
     * Whether a trailing comment on a data line belongs to the leg or the newer station (changed
     * in SexyTopo 1.11.3). A `SexyTopo X.Y.Z` token in a *comment* line decides it; no token at
     * all means a third-party file, read the modern way.
     */
    internal fun writtenWithLegComments(text: String): Boolean {
        val version = versionOf(text) ?: return true
        return version > LEG_COMMENTS_CUTOFF
    }

    /** The version that wrote the file, or null if it does not say. */
    internal fun versionOf(text: String): Triple<Int, Int, Int>? {
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#") && !trimmed.startsWith(";")) continue
            val match = VERSION.find(trimmed) ?: continue
            return Triple(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
        return null
    }

    private operator fun Triple<Int, Int, Int>.compareTo(other: Triple<Int, Int, Int>): Int =
        compareValuesBy(this, other, { it.first }, { it.second }, { it.third })

    private val VERSION = Regex("""SexyTopo\s+(\d+)\.(\d+)\.(\d+)""")

    /** `SexyTopoVersion.LEG_COMMENTS_VERSION_CUTOFF`: 1.11.2 and earlier are the legacy path. */
    private val LEG_COMMENTS_CUTOFF = Triple(1, 11, 2)
}
