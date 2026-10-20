package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.export.SurvexConstants
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * A Survex extended elevation specification read back onto a survey. Ported from `EspecParser`.
 *
 * Stations the file does not mention keep the direction they have, which for a survey just read
 * from a `.svx` is the default.
 */
object EspecParser {

    private val WHITESPACE = Regex("\\s+")

    /**
     * Nothing in here throws: a line that cannot be understood is skipped, as is a station the
     * survey does not have. A damaged `.espec` loses its directions, not the survey it came with.
     */
    fun apply(text: String, survey: Survey) {
        val commentedVertical =
            SurvexConstants.ESPEC_COMMENT_PREFIX + SurvexConstants.ESPEC_VERTICAL

        for (line in text.split("\n")) {
            var trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // A commented-out `*evertical` is the one comment that is read, because it is this
            // app's own: Survex cannot take the two-station form yet, so the exporter hides it from
            // Survex and leaves it for this. Every other comment, a commented `*eleft` included,
            // was commented out by somebody for a reason.
            if (trimmed.startsWith(";")) {
                if (!trimmed.startsWith(commentedVertical)) continue
                trimmed = trimmed.substring(SurvexConstants.ESPEC_COMMENT_PREFIX.length).trim()
            }

            val tokens = trimmed.split(WHITESPACE)
            val command = tokens[0].lowercase()

            when (command) {
                SurvexConstants.ESPEC_START -> continue

                // `*evertical <from> <to>`: the pitch is the leg into the second station, and it
                // goes no further.
                SurvexConstants.ESPEC_VERTICAL -> {
                    if (tokens.size != 3) continue
                    val station = survey.getStationByName(tokens[2]) ?: continue
                    SurveyUpdater.setExtendedElevationDirection(
                        survey,
                        station,
                        ExtendedElevationDirection.VERTICAL,
                    )
                }

                // `*eleft <station>` and `*eright <station>`, which carry on down the passage.
                SurvexConstants.ESPEC_LEFT, SurvexConstants.ESPEC_RIGHT -> {
                    if (tokens.size != 2) continue
                    val station = survey.getStationByName(tokens[1]) ?: continue
                    val direction =
                        if (command == SurvexConstants.ESPEC_LEFT) {
                            ExtendedElevationDirection.LEFT
                        } else {
                            ExtendedElevationDirection.RIGHT
                        }
                    SurveyUpdater.setExtendedElevationDirectionOfSubtree(station, direction)
                }

                else -> continue
            }
        }
    }
}

/**
 * Which `.svx` in a folder to read, and which `.espec` goes with it. Ported from the folder half of
 * `SurvexImporter`, with the questions it asks returned rather than put to a dialog, so whatever
 * shows the files can ask them its own way.
 */
object SurvexFolder {

    /** What importing a folder needs to do next. */
    sealed interface Step {
        /** More than one `.svx`: the surveyor says which. */
        data class ChooseSvx(val svxFiles: List<String>) : Step

        /**
         * `.espec` files, none named after the `.svx`: the surveyor picks one of [especFiles] or
         * none at all, which upstream offers as the last choice in the same list.
         */
        data class ChooseEspec(val svx: String, val especFiles: List<String>) : Step

        /** Nothing left to ask. */
        data class Read(val svx: String, val espec: String?) : Step
    }

    fun isSvx(fileName: String): Boolean =
        fileName.endsWith(".${SurveyFormat.SURVEX.fileExtension}")

    fun isEspec(fileName: String): Boolean =
        fileName.endsWith(".${SurvexConstants.ESPEC_EXTENSION}")

    /** Whether a folder holding these files is one the Survex importer can take. */
    fun canHandle(folder: List<String>): Boolean = folder.any(::isSvx)

    /** The `.espec` with the same name as the `.svx` but for the extension, if there is one. */
    fun matchingEspec(svx: String, especFiles: List<String>): String? {
        val base = svx.substringBeforeLast('.')
        return especFiles.firstOrNull { it.substringBeforeLast('.') == base }
    }

    /**
     * The next step for a folder holding [folder], once [chosenSvx] is known if it had to be asked.
     *
     * One `.svx`, or a chosen one, is read with the `.espec` named after it; with no `.espec` in
     * the folder at all it is read alone, and with only others the surveyor is asked rather than
     * one being guessed at. Null for a folder with no `.svx` in it.
     */
    fun nextStep(folder: List<String>, chosenSvx: String? = null): Step? {
        val svxFiles = folder.filter(::isSvx)
        val svx =
            chosenSvx
                ?: when (svxFiles.size) {
                    0 -> return null
                    1 -> svxFiles.single()
                    else -> return Step.ChooseSvx(svxFiles)
                }

        val especFiles = folder.filter(::isEspec)
        val match = matchingEspec(svx, especFiles)
        return if (match != null || especFiles.isEmpty()) {
            Step.Read(svx, match)
        } else {
            Step.ChooseEspec(svx, especFiles)
        }
    }
}
