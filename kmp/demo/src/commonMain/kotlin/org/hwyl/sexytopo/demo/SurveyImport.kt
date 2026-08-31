package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.imports.PocketTopoImporter
import org.hwyl.sexytopo.shared.io.imports.PocketTopoTxtImporter
import org.hwyl.sexytopo.shared.io.imports.SurveyImporter
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.SurveyFileType
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
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

    /**
     * The parts of a saved survey that are not the survey: the two sketches and the version stamp.
     *
     * These have to be recognised so they can be *left out*. A survey this app or the Android app
     * wrote is four files, and every one of them ends `.json` — so a rule of "any `.json` is a
     * survey" offered `Swildons.plan.json` as something to import beside `Swildons.data.json`,
     * where picking it would parse a drawing as a centreline and fail.
     */
    private val SURVEY_PARTS =
        listOf(
            SurveyFileType.PLAN_SKETCH,
            SurveyFileType.EXTENDED_ELEVATION_SKETCH,
            SurveyFileType.METADATA,
        )

    private fun isSurveyPart(fileName: String) =
        SURVEY_PARTS.any { fileName.endsWith(".${it.extension}", ignoreCase = true) }

    private fun isNative(fileName: String) =
        fileName.endsWith(".json", ignoreCase = true) && !isSurveyPart(fileName)

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
                isSurveyFolder(store, it) ||
                    isNative(it) ||
                    formatOf(it) != null ||
                    isPocketTopoBinary(it) ||
                    (isPocketTopo(it) && looksLikePocketTopo(store, it))
            }
        }.getOrDefault(emptyList())

    /**
     * A whole survey *directory* somebody has put at the root, which is `action_file_import_directory`.
     *
     * A survey does not usually arrive as a loose file. It arrives as a zip, and unzipping it in
     * the Files app — or on a desktop, or in a browser's download folder — leaves a folder called
     * after the cave with the survey's four files inside. Listing only files made that folder
     * invisible: the app would show an empty import list beside a survey sitting right there.
     *
     * [SurveyStorage.isSurveyDirectory] decides, by looking for the `.data.json` named after the
     * folder — the same test the library itself uses, so a folder this app wrote and a folder the
     * Android app wrote are the same thing.
     *
     * The app's own `surveys/` directory is excluded because everything in it is already in the
     * library; offering it would invite the surveyor to import what they already have.
     */
    private fun isSurveyFolder(store: FileStore, name: String): Boolean =
        name != SURVEYS_ROOT.single() &&
            store.isDirectory(listOf(name)) &&
            SurveyStorage.isSurveyDirectory(store, listOf(name))

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
        val survey =
            if (isSurveyFolder(store, fileName)) {
                // A folder goes through the loader the library itself uses, which has read all
                // four files since the day it was written. Nothing here needs to know how.
                runCatching { SurveyStorage.load(store, listOf(fileName)) }.getOrNull()
            } else {
                runCatching { parse(store, fileName, name) }.getOrNull()
            } ?: return null
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
            else -> SurveyJson.parse(text).also { withSketchesBesideIt(store, fileName, it) }
        }
    }

    /**
     * The two drawings, if whoever sent the survey sent all of it.
     *
     * A SexyTopo survey is four files — `Name.data.json` and its two sketches, plus a version
     * stamp — and this importer takes one file at a time, so `SurveyJson.parse` gets the
     * centreline and the sketches were dropped without a word. That is most of a surveyor's work:
     * the numbers take a minute a station and the drawing takes the whole trip.
     *
     * [SurveyStorage] has read all four for as long as it has existed; it is only the *loose file*
     * path that did not. So this is the same four files, looked for beside the one that was picked
     * rather than inside a survey directory — which is where they land when somebody AirDrops a
     * survey or unzips one into the Files app.
     *
     * A survey sent as its data file alone still imports, with empty sketches, exactly as before.
     */
    private fun withSketchesBesideIt(store: FileStore, fileName: String, survey: Survey) {
        val base = fileName.dropExtension(".json").dropExtension(".data")
        fun sketch(type: SurveyFileType) =
            runCatching { store.readText(listOf(type.filenameFor(base))) }.getOrNull()

        sketch(SurveyFileType.PLAN_SKETCH)?.let {
            runCatching { survey.planSketch = SketchJson.parse(it, survey) }
        }
        sketch(SurveyFileType.EXTENDED_ELEVATION_SKETCH)?.let {
            runCatching { survey.elevationSketch = SketchJson.parse(it, survey) }
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
