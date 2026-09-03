package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm

/**
 * Remembering the surveying tolerances between runs.
 *
 * Plain `key=value` lines rather than JSON, because the demo module does not depend on a
 * serialisation library and six numbers do not justify adding one. Unknown keys are ignored and
 * unparseable values fall back to the default, so a file written by a later version still loads
 * and a corrupted one degrades to the app's out-of-the-box behaviour rather than refusing to start.
 */
object SurveySettingsStore {

    val PATH = listOf("settings.txt")

    fun format(settings: SurveySettings): String =
        buildString {
            appendLine("algorithm=${settings.legAmalgamationAlgorithm.name}")
            appendLine("maxDistanceDelta=${settings.maxDistanceDelta}")
            appendLine("maxAngleDelta=${settings.maxAngleDelta}")
            appendLine("maxEndpointDelta=${settings.maxEndpointDelta}")
            appendLine("maxPairwiseError=${settings.maxPairwiseError}")
            appendLine("numberOfRepeatsForNewStation=${settings.numberOfRepeatsForNewStation}")
        }

    fun parse(text: String): SurveySettings {
        val values =
            text.lineSequence()
                .mapNotNull { line ->
                    val key = line.substringBefore('=', "").trim()
                    if (key.isEmpty() || '=' !in line) null else key to line.substringAfter('=').trim()
                }
                .toMap()

        val defaults = SurveySettings.DEFAULT
        return SurveySettings(
            legAmalgamationAlgorithm =
                LegAmalgamationAlgorithm.entries.firstOrNull { it.name == values["algorithm"] }
                    ?: defaults.legAmalgamationAlgorithm,
            maxDistanceDelta = values["maxDistanceDelta"]?.toFloatOrNull() ?: defaults.maxDistanceDelta,
            maxAngleDelta = values["maxAngleDelta"]?.toFloatOrNull() ?: defaults.maxAngleDelta,
            maxEndpointDelta = values["maxEndpointDelta"]?.toFloatOrNull() ?: defaults.maxEndpointDelta,
            maxPairwiseError = values["maxPairwiseError"]?.toFloatOrNull() ?: defaults.maxPairwiseError,
            numberOfRepeatsForNewStation =
                values["numberOfRepeatsForNewStation"]?.toIntOrNull()?.takeIf { it >= 1 }
                    ?: defaults.numberOfRepeatsForNewStation,
        )
    }

    /** Never throws: browser storage can be disabled, and a missing file just means the defaults. */
    fun load(store: FileStore): SurveySettings =
        runCatching { store.readText(PATH)?.let(::parse) }.getOrNull() ?: SurveySettings.DEFAULT

    fun save(store: FileStore, settings: SurveySettings): Boolean =
        runCatching { store.writeText(PATH, format(settings)) }.isSuccess
}
