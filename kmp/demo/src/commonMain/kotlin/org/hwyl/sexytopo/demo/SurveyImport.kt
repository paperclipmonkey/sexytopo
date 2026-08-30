package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.imports.PocketTopoImporter
import org.hwyl.sexytopo.shared.io.imports.PocketTopoTxtImporter
import org.hwyl.sexytopo.shared.io.imports.SurveyImporter
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
     * What the file's extension says it is, or null if this app cannot read it.
     *
     * Survex and Therion are here because they are the two formats a caver is actually handed by
     * somebody else: a club's existing survey of the cave they are about to extend. JSON is this
     * app's own.
     */
    internal fun formatOf(fileName: String): SurveyFormat? =
        when {
            fileName.endsWith(".svx", ignoreCase = true) -> SurveyFormat.SURVEX
            // Therion's own extension. `.th2` is the *drawing*, which this app exports but has no
            // importer for, so it is deliberately not matched: offering it and then failing to
            // read it is worse than not offering it.
            fileName.endsWith(".th", ignoreCase = true) -> SurveyFormat.THERION
            else -> null
        }

    private fun isNative(fileName: String) = fileName.endsWith(".json", ignoreCase = true)

    /**
     * PocketTopo's text export, which is the only one of these that brings a *drawing* in too.
     *
     * Not PocketTopo's own `.top`, which is binary and would need a byte-level [FileStore]; this is
     * the file its Export menu writes.
     */
    private fun isPocketTopo(fileName: String) = fileName.endsWith(".txt", ignoreCase = true)

    /** PocketTopo's own binary file, which its Save writes rather than its Export. */
    private fun isPocketTopoBinary(fileName: String) = fileName.endsWith(".top", ignoreCase = true)

    /**
     * Whether a `.txt` at the root is a PocketTopo export rather than somebody's notes.
     *
     * The only one of these that is decided by *looking*, and the exception is worth stating: every
     * other extension here belongs to a survey format and nothing else, whereas `.txt` belongs to
     * everything. On a phone whose Documents folder is visible in the Files app — which is the whole
     * mechanism this import uses — offering every text file as a survey would bury the one that is.
     *
     * The check is the first non-blank line, which a PocketTopo export always spends on a section
     * header. Reading a few text files at the storage root is bounded work; parsing them would not
     * be, which is why nothing else here does it.
     */
    private fun looksLikePocketTopo(store: FileStore, fileName: String): Boolean {
        val text = runCatching { store.readText(listOf(fileName)) }.getOrNull() ?: return false
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return first == "TRIP" || first == "DATA"
    }

    /**
     * Files at the storage root that look like they might be surveys.
     *
     * Named rather than parsed at this point, because parsing every file in a directory to decide
     * whether to list it would read the lot on every open of the dialog. A file that turns out not
     * to be a survey fails at [import], which says so.
     */
    fun candidates(store: FileStore): List<String> =
        runCatching {
            store.list(emptyList()).filter {
                isNative(it) ||
                    formatOf(it) != null ||
                    isPocketTopoBinary(it) ||
                    (isPocketTopo(it) && looksLikePocketTopo(store, it))
            }
        }.getOrDefault(emptyList())

    /**
     * Reads one, names it something not already taken, and saves it into the library.
     *
     * The name comes from the file rather than from the survey's own name field, because the field
     * is what the survey was called on the phone that wrote it and the filename is what the person
     * who sent it called it — and when they differ, the filename is the one the surveyor just
     * looked at. That is doubly true of Survex and Therion, whose name comes from a `*begin`
     * inside the file that a hand-assembled one may not have at all.
     */
    fun import(library: SurveyLibrary, store: FileStore, fileName: String): Survey? {
        val name = nameFor(fileName)
        val survey = runCatching { parse(store, fileName, name) }.getOrNull() ?: return null
        // An empty survey means the file parsed but held nothing this app understands — a Therion
        // file that is all `scrap`, say. Importing it would put a survey with no legs in the
        // library and look like success.
        if (survey.origin.onwardLegs.isEmpty()) return null
        survey.name = library.uniqueName(name)
        return if (library.save(survey)) survey else null
    }

    /**
     * Whichever reader the extension calls for.
     *
     * `.top` is the only one that asks the store for bytes; everything else this app imports is
     * text, which is why [FileStore.readBytes] exists for exactly this line.
     */
    private fun parse(store: FileStore, fileName: String, name: String): Survey? {
        if (isPocketTopoBinary(fileName)) {
            val bytes = store.readBytes(listOf(fileName)) ?: return null
            return PocketTopoImporter.read(bytes, name)
        }
        val text = store.readText(listOf(fileName)) ?: return null
        val format = formatOf(fileName)
        return when {
            format != null -> SurveyImporter.read(text, format, name)
            isPocketTopo(fileName) -> PocketTopoTxtImporter.read(text, name)
            else -> SurveyJson.parse(text)
        }
    }

    /**
     * Strips the extensions SexyTopo puts on, so `Swildons.data.json` imports as `Swildons` rather
     * than as `Swildons.data`.
     */
    internal fun nameFor(fileName: String): String =
        fileName
            .dropExtension(".json")
            // Innermost last: the app writes `Name.data.autosave.json`, so `.autosave` has to come
            // off before `.data` is even the suffix. Stripping in the other order leaves
            // "Swildons.data", which is not a cave anybody has heard of.
            .dropExtension(".autosave")
            .dropExtension(".data")
            .dropExtension(".svx")
            .dropExtension(".th")
            .dropExtension(".txt")
            .dropExtension(".top")
            .ifBlank { "Imported survey" }

    /** [String.removeSuffix], but a file called `CAVE.SVX` is the same file as `cave.svx`. */
    private fun String.dropExtension(extension: String): String =
        if (endsWith(extension, ignoreCase = true)) dropLast(extension.length) else this
}
