package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationReading
import org.hwyl.sexytopo.shared.io.CalibrationJson
import org.hwyl.sexytopo.shared.io.LogJson
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.log.LogMessage
import org.hwyl.sexytopo.shared.log.LogType
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * Somewhere for surveys to live that outlasts the app being closed.
 *
 * The shared [SurveyStorage] does all the work; this only says *where*. On the web that is the
 * browser's own storage, which is what makes the app usable in a cave: an installed web app with
 * no signal can still open, record and keep a survey.
 */
expect fun platformFileStore(): FileStore

/** Where surveys are kept. A single directory, since there is no folder picker here. */
val SURVEYS_ROOT = listOf("surveys")

/**
 * Saving and reopening surveys, wrapped so callers never have to think about paths.
 *
 * Failure is swallowed deliberately. Browser storage can be full, disabled, or unavailable in a
 * private window, and none of those should take the app down mid-survey — a surveyor who cannot
 * save still needs the screen they are drawing on. [lastError] carries the reason so the UI can
 * say so quietly.
 */
class SurveyLibrary(private val store: FileStore = platformFileStore()) {

    var lastError: String? = null
        private set

    fun list(): List<String> =
        runCatching { SurveyStorage.listSurveys(store, SURVEYS_ROOT) }
            .onFailure { lastError = it.message }
            .getOrDefault(emptyList())

    fun save(survey: Survey): Boolean =
        runCatching {
            SurveyStorage.save(store, survey, SURVEYS_ROOT + survey.name)
            lastError = null
        }.onFailure { lastError = it.message ?: "could not save" }.isSuccess

    fun open(name: String): Survey? =
        runCatching { SurveyStorage.load(store, SURVEYS_ROOT + name) }
            .onFailure { lastError = it.message }
            .getOrNull()

    fun delete(name: String): Boolean =
        runCatching { store.delete(SURVEYS_ROOT + name) }
            .onFailure { lastError = it.message }
            .getOrDefault(false)

    fun exists(name: String): Boolean =
        runCatching { SurveyStorage.isSurveyDirectory(store, SURVEYS_ROOT + name) }
            .getOrDefault(false)

    /** Files at the storage root that might be surveys somebody has put there to import. */
    fun importCandidates(): List<String> = SurveyImport.candidates(store)

    fun import(fileName: String): Survey? =
        SurveyImport.import(this, store, fileName).also {
            if (it == null) lastError = "could not read $fileName"
        }

    /** Where the instrument's calibration lives, in the Android app's own JSON format. */
    private val CALIBRATION_PATH = listOf("calibration.json")

    /** The surveying tolerances, which outlive any one survey. */
    fun loadSettings(): SurveySettings = SurveySettingsStore.load(store)

    fun saveSettings(settings: SurveySettings): Boolean =
        SurveySettingsStore.save(store, settings).also {
            if (!it) lastError = "could not save settings"
        }

    /**
     * The calibration readings, which belong to the *instrument* rather than to any one survey —
     * so they sit beside the settings at the storage root rather than inside a survey's folder.
     *
     * Worth persisting for one reason: fifty-six shots is a twenty-minute job, and that is long
     * enough for a phone to be dropped, a battery to die, or the app to be killed in a pocket.
     * Losing the run means doing all of it again, in the same cave, in the same cold.
     */
    fun loadCalibration(): List<CalibrationReading> =
        runCatching { store.readText(CALIBRATION_PATH)?.let(CalibrationJson::read) }
            .getOrNull()
            .orEmpty()

    fun saveCalibration(readings: List<CalibrationReading>): Boolean =
        runCatching { store.writeText(CALIBRATION_PATH, CalibrationJson.write(readings)) }
            .isSuccess
            .also { if (!it) lastError = "could not save the calibration" }

    /**
     * The instrument log, at the storage root under the name `Log.getLogFile` gives it.
     *
     * Failure here is swallowed and *not* reported: the log is what somebody reads when something
     * else has gone wrong, and "could not save the log" occupying the one line the app has for
     * telling them what went wrong would be its own small joke.
     */
    fun loadLog(type: LogType): List<LogMessage> =
        runCatching { store.readText(listOf(type.fileName))?.let(LogJson::read) }
            .getOrNull()
            .orEmpty()

    fun saveLog(type: LogType, messages: List<LogMessage>) {
        runCatching { store.writeText(listOf(type.fileName), LogJson.write(messages)) }
    }

    /**
     * A name that is not already taken, so "New survey" twice does not overwrite the first.
     *
     * Suffixes with a number rather than a timestamp: a caver reading a list wants "Swildons 2",
     * not an epoch.
     */
    fun uniqueName(preferred: String): String {
        if (!exists(preferred)) return preferred
        var n = 2
        while (exists("$preferred $n")) n++
        return "$preferred $n"
    }
}

/** Used where nothing persists - the headless renderer and tests. */
class NoStorage : FileStore by InMemoryFileStore()
