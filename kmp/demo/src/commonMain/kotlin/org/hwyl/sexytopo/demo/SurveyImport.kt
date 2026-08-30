package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Bringing a survey in from outside.
 *
 * The mechanism is the same on every platform and needs no file picker: whatever a survey file
 * lands in the app's own storage root gets offered for import. On iOS that is literally drag and
 * drop — `UIFileSharingEnabled` publishes the app's Documents directory to the Files app, so a
 * `.data.json` AirDropped from somebody else, or restored from a backup, appears there and then
 * appears here. Android's external files directory works the same way. The browser has no such
 * folder, so [canPickFiles] platforms put the chosen file into the store root themselves and the
 * rest of this is shared.
 *
 * Only the root is scanned, not the `surveys/` tree: a folder this app wrote is already a survey
 * and shows up in the library without any of this.
 */
object SurveyImport {

    /**
     * Files at the storage root that look like they might be surveys.
     *
     * Named rather than parsed at this point, because parsing every JSON file in a directory to
     * decide whether to list it would read the lot on every open of the dialog. A file that turns
     * out not to be a survey fails at [import], which says so.
     */
    fun candidates(store: FileStore): List<String> =
        runCatching {
            store.list(emptyList()).filter { it.endsWith(".json", ignoreCase = true) }
        }.getOrDefault(emptyList())

    /**
     * Reads one, names it something not already taken, and saves it into the library.
     *
     * The name comes from the file rather than from the JSON's own `name` field, because the
     * field is what the survey was called on the phone that wrote it and the filename is what the
     * person who sent it called it — and when they differ, the filename is the one the surveyor
     * just looked at.
     */
    fun import(library: SurveyLibrary, store: FileStore, fileName: String): Survey? {
        val text = runCatching { store.readText(listOf(fileName)) }.getOrNull() ?: return null
        val survey = runCatching { SurveyJson.parse(text) }.getOrNull() ?: return null
        survey.name = library.uniqueName(nameFor(fileName))
        return if (library.save(survey)) survey else null
    }

    /**
     * Strips the extensions SexyTopo puts on, so `Swildons.data.json` imports as `Swildons` rather
     * than as `Swildons.data`.
     */
    internal fun nameFor(fileName: String): String =
        fileName
            .removeSuffix(".json")
            // Innermost last: the app writes `Name.data.autosave.json`, so `.autosave` has to come
            // off before `.data` is even the suffix. Stripping in the other order leaves
            // "Swildons.data", which is not a cave anybody has heard of.
            .removeSuffix(".autosave")
            .removeSuffix(".data")
            .ifBlank { "Imported survey" }
}
