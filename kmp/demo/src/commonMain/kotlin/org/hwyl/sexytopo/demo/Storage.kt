package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationReading
import org.hwyl.sexytopo.shared.io.CalibrationJson
import org.hwyl.sexytopo.shared.io.LogJson
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.PhotoStore
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.io.store.SurveyZip
import org.hwyl.sexytopo.shared.log.LogMessage
import org.hwyl.sexytopo.shared.log.LogType
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * Somewhere for surveys to live that outlasts the app being closed.
 *
 * The shared [SurveyStorage] does all the work; this only says *where*. On the web that is the
 * browser's own storage, so an installed web app with no signal can still open and keep a survey.
 */
expect fun platformFileStore(): FileStore

/** Where surveys are kept. A single directory, since there is no folder picker here. */
val SURVEYS_ROOT = listOf("surveys")

/**
 * One handle on that directory, for the parts of the app that deal in files rather than surveys.
 *
 * [SurveyLibrary] takes its own and deals in surveys, which is the right shape for four JSON files
 * that share a name and the wrong one for a photograph. Photographs need a path, so they need this.
 *
 * Shared rather than made where it is wanted, because on a machine with nowhere writable
 * `platformFileStore` falls back to an [InMemoryFileStore] and a second call is then a second
 * *empty* store. Two handles would mean a photograph written through one and looked for through
 * the other, which is a pin pointing at nothing on exactly the machines least able to explain why.
 *
 * Lazily, since a top-level property is initialised with the module that holds it and on the web
 * that can be before there is a document to ask about storage.
 */
internal val surveyFileStore: FileStore by lazy { platformFileStore() }

/**
 * The survey as a zip, with the photographs its pins point at.
 *
 * [SurveyZip] deliberately does not know where a photograph is kept — its job is naming and
 * packing — so somebody has to join the two, and this is the one place that does. Both ways out of
 * the app, the File menu's share and the export screen's own button, come through here, so a
 * survey shared from one is the same file as a survey shared from the other.
 *
 * A photograph that will not read is left out rather than allowed to take the export down with it.
 * Losing one picture from a zip is a bad afternoon; losing the survey is a lost trip.
 */
internal fun surveyArchive(survey: Survey): ByteArray =
    SurveyZip.archive(survey) { photoId ->
        runCatching {
            PhotoStore.load(surveyFileStore, SURVEYS_ROOT + survey.name, survey.name, photoId)
        }
            .getOrNull()
    }

/**
 * Saving and reopening surveys, wrapped so callers never have to think about paths.
 *
 * Failure is swallowed deliberately: browser storage can be full, disabled, or unavailable in a
 * private window, and none of those should take the app down mid-survey. [lastError] carries the
 * reason so the UI can say so quietly.
 */
class SurveyLibrary(private val store: FileStore = platformFileStore()) {

    var lastError: String? = null
        internal set

    /**
     * A problem that did not stop the operation: something came through, but not all of it.
     *
     * Separate from [lastError] because the difference matters to a surveyor. "It failed" means try
     * again; "it worked but the drawing was unreadable" means the file you were sent is damaged and
     * trying again will not help.
     */
    var lastWarning: String? = null
        internal set

    fun list(): List<String> =
        runCatching { SurveyStorage.listSurveys(store, SURVEYS_ROOT) }
            .onFailure { lastError = it.message }
            .getOrDefault(emptyList())

    fun save(survey: Survey): Boolean =
        runCatching {
            SurveyStorage.save(store, survey, SURVEYS_ROOT + survey.name)
            lastError = null
        }.onFailure { lastError = it.message ?: "could not save" }.isSuccess

    fun open(name: String): Survey? {
        lastWarning = null
        return runCatching {
            SurveyStorage.load(store, SURVEYS_ROOT + name) { lastWarning = it }
        }.onFailure { lastError = it.message }.getOrNull()
    }

    fun delete(name: String): Boolean =
        runCatching { store.delete(SURVEYS_ROOT + name) }
            .onFailure { lastError = it.message }
            .getOrDefault(false)

    fun exists(name: String): Boolean =
        runCatching { SurveyStorage.isSurveyDirectory(store, SURVEYS_ROOT + name) }
            .getOrDefault(false)

    /** Files at the storage root that might be surveys somebody has put there to import. */
    fun importCandidates(): List<String> = SurveyImport.candidates(store)

    fun import(fileName: String): Survey? {
        lastWarning = null
        lastError = null
        return SurveyImport.import(this, store, fileName).also {
            // SurveyImport already sets a specific message for anything that threw; this generic
            // one is only for the message-less failures.
            if (it == null && lastError == null) lastError = "could not read $fileName"
        }
    }

    /** Where the instrument's calibration lives, in the Android app's own JSON format. */
    private val CALIBRATION_PATH = listOf("calibration.json")

    /** The surveying tolerances, which outlive any one survey. */
    fun loadSettings(): SurveySettings = SurveySettingsStore.load(store)

    /** The app's own preferences, which outlive everything. */
    fun loadPreferences(): AppPreferences = AppPreferencesStore.load(store)

    fun savePreferences(preferences: AppPreferences): Boolean =
        AppPreferencesStore.save(store, preferences).also {
            if (!it) lastError = "could not save preferences"
        }

    fun saveSettings(settings: SurveySettings): Boolean =
        SurveySettingsStore.save(store, settings).also {
            if (!it) lastError = "could not save settings"
        }

    /** The calibration readings, which belong to the *instrument* and sit beside the settings. */
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
     * else has gone wrong, and using the app's one error line to report a failure to save it would
     * be its own small joke.
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
     * Suffixes with a number rather than a timestamp: a caver reading a list wants "Swildons 2".
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
