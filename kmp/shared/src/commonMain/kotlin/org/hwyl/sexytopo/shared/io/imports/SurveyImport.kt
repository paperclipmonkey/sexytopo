package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * A whole Survex or Therion file, read into a survey.
 *
 * Ported from `SurvexImporter.toSurvey` and `TherionImporter.parseTh`, which do the same four
 * things in the same order and differ only in the format they pass down.
 *
 * The order matters and is not obvious: the passage block is read *before* the centreline, because
 * it is keyed on station names that do not exist yet, and merged *after*, once they do.
 */
object SurveyImporter {

    /**
     * @param name what to call the survey; the file does not reliably say. Survex has a
     *   `*begin <name>` and Therion a `survey <name>`, but a file assembled by hand may have
     *   neither, and the caller usually knows the filename.
     */
    fun read(text: String, format: SurveyFormat, name: String): Survey {
        val survey = Survey(name)

        // Which convention the file was written with. A file with no SexyTopo version header at
        // all is third-party, and is read the modern way: a trailing comment on a data line means
        // that leg, which is what a hand-written file means by it too.
        val useLegComments = writtenWithLegComments(text)

        val passageComments = SurvexTherionImporter.parsePassageData(text, format)

        // Only the normal data block. A passage row — "2 - - - - junction" — has five fields and
        // no comment character, so a parser handed the whole file would read it as a shot whose
        // distance is a hyphen. The Java throws on that; this port would silently skip it, which
        // is worse, because a skipped row is a station comment nobody notices is missing.
        SurvexTherionImporter.parseCentreline(
            normalDataBlock(text, format),
            survey,
            useLegComments,
        )

        SurvexTherionImporter.mergePassageComments(survey, passageComments)

        SurvexTherionImporter.parseMetadata(text, format)?.let { survey.trip = it }

        return survey
    }

    /**
     * Everything under a `data normal` command, up to the next `data` command of any kind.
     *
     * Ported from `SurvexImporter.extractNormalDataBlock`.
     */
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
     * Whether a trailing comment on a data line belongs to the leg or to the newer station.
     *
     * SexyTopo 1.11.3 changed this. Ported from `SexyTopoVersion`: a `SexyTopo X.Y.Z` token in a
     * *comment* line — data lines are ignored, so a cave called "SexyTopo 1.0.0" cannot be mistaken
     * for a version stamp — and no token at all means a third-party file, read the modern way.
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
