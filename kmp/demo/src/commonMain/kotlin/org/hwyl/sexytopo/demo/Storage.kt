package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.model.survey.Survey

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
