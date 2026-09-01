package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.MetadataJson
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.export.SurveyFormat
import org.hwyl.sexytopo.shared.io.imports.PocketTopoImporter
import org.hwyl.sexytopo.shared.io.imports.PocketTopoTxtImporter
import org.hwyl.sexytopo.shared.io.imports.SurveyImporter
import org.hwyl.sexytopo.shared.io.imports.XviImporter
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.SurveyFileType
import org.hwyl.sexytopo.shared.io.store.SurveyStorage
import org.hwyl.sexytopo.shared.model.sketch.Sketch
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

    /** A Therion tracing image: a drawing with no centreline under it. */
    private fun isTracing(fileName: String) = fileName.endsWith(".xvi", ignoreCase = true)

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
                    isTracing(it) ||
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

        // A loose data file is only ever part of a survey, so the drawings beside it come too —
        // and if one of them is there and will not parse, the library is told, because a survey
        // that arrives with an empty plan and no explanation is the same silent loss this whole
        // area has just been fixed for.
        if (!isSurveyFolder(store, fileName) && isNative(fileName)) {
            library.lastWarning = withSketchesBesideIt(store, fileName, survey)
        }
        // The same idea for a Therion project: the `.th` carries the centreline and the tracing
        // images beside it carry the two drawings, so importing the one without the others is the
        // silent loss this app has already been fixed for once, in its own format.
        if (formatOf(fileName) == SurveyFormat.THERION) {
            library.lastWarning = withTracingsBesideIt(store, fileName, survey)
        }
        // An empty survey means the file parsed but held nothing this app understands — a Therion
        // file that is all `scrap`, say. Importing it would put a survey with no legs in the
        // library and look like success.
        //
        // A tracing image is the exception, and it took writing this to notice: an `.xvi` has no
        // centreline in it *by definition*, so the guard would have thrown away every one of them
        // as empty. It is offered if it brought a drawing, which is the whole of what it holds.
        if (survey.origin.onwardLegs.isEmpty() &&
            !(isTracing(fileName) && survey.planSketch.pathDetails.isNotEmpty())
        ) {
            return null
        }
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
            isTracing(fileName) -> XviImporter.read(text, name)
            format != null -> SurveyImporter.read(text, format, name)
            isPocketTopo(fileName) -> PocketTopoTxtImporter.read(text, name)
            else -> SurveyJson.parse(text)
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
    private fun withSketchesBesideIt(
        store: FileStore,
        fileName: String,
        survey: Survey,
    ): String? {
        val base = fileName.dropExtension(".json").dropExtension(".data")
        val unreadable = mutableListOf<String>()
        var dropped = 0

        fun read(type: SurveyFileType, into: (Sketch) -> Unit) {
            val name = type.filenameFor(base)
            val text = runCatching { store.readText(listOf(name)) }.getOrNull() ?: return
            // Present but unreadable is the case worth reporting. Absent is ordinary — plenty of
            // surveys are handed over as their data file alone — but a drawing that is *there* and
            // will not parse means the file somebody sent is damaged, and silently importing a
            // survey with an empty plan tells them the opposite.
            runCatching { SketchJson.read(text, survey) }
                .onSuccess {
                    into(it.sketch)
                    // And the partial case, which is the likelier one. The reader drops a damaged
                    // stroke rather than the drawing, so a sketch can arrive short without
                    // arriving empty — and a drawing three strokes short looks exactly like a
                    // drawing that was drawn three strokes short.
                    dropped += it.dropped
                }
                .onFailure { unreadable += name }
        }

        read(SurveyFileType.PLAN_SKETCH) { survey.planSketch = it }
        read(SurveyFileType.EXTENDED_ELEVATION_SKETCH) { survey.elevationSketch = it }

        // And the working end. It is in the metadata file rather than the data file when the
        // survey came from the Android app, which keeps it nowhere else. Not reported when it is
        // missing or will not parse, unlike the drawings above: a survey that opens at the
        // entrance of the cave rather than where somebody stopped has lost a convenience, and a
        // warning about it beside a perfectly good centreline would read as a damaged import.
        runCatching { store.readText(listOf(SurveyFileType.METADATA.filenameFor(base))) }
            .getOrNull()
            ?.let { MetadataJson.apply(survey, it) }

        return when {
            unreadable.isNotEmpty() ->
                "the centreline came in but ${unreadable.joinToString(" and ")} could not be read"
            dropped > 0 -> "the drawing came in $dropped ${plural(dropped)} short"
            else -> null
        }
    }

    /**
     * The tracing images beside a Therion file, which is where its drawings live.
     *
     * A Therion project is a `.th` and a `.th2` scrap per drawing, and the scrap is traced over an
     * `.xvi` background image. `TherionImporter` in the Android app reads the `.th` for the
     * centreline and then any `.xvi` beside it for the plan and the extended elevation; this port
     * read the `.th` and stopped, so a Therion project imported as a bare centreline.
     *
     * Files are matched by name rather than by a fixed suffix, because the suffix is the
     * surveyor's to choose: this app writes `Name.plan.xvi` and `Name.ee.xvi` by default, the
     * Android app writes `Nameplan` and `Nameee`, and somebody who typed `P` and `EE` into the
     * Therion settings gets those. So anything called `Name*.xvi` is a tracing of this survey, and
     * what follows the name decides which drawing it is: ending in `ee` is the elevation and
     * everything else is the plan, which also lets a lone `Name.xvi` in as the plan.
     */
    private fun withTracingsBesideIt(
        store: FileStore,
        fileName: String,
        survey: Survey,
    ): String? {
        val base = fileName.dropExtension(".th")
        val tracings =
            runCatching { store.list(emptyList()) }
                .getOrNull()
                .orEmpty()
                .filter {
                    isTracing(it) && it.startsWith(base, ignoreCase = true) && it != fileName
                }
                .sorted()
        if (tracings.isEmpty()) return null

        val unreadable = mutableListOf<String>()
        var drawings = 0
        for (name in tracings) {
            val text = runCatching { store.readText(listOf(name)) }.getOrNull()
            if (text == null) {
                unreadable += name
                continue
            }
            val sketch = runCatching { XviImporter.sketchFrom(text) }.getOrNull()
            if (sketch == null || sketch.pathDetails.isEmpty()) {
                unreadable += name
                continue
            }
            drawings++
            val middle = name.dropExtension(".xvi").drop(base.length).lowercase()
            if (middle.endsWith("ee")) survey.elevationSketch = sketch else survey.planSketch = sketch
        }

        return when {
            unreadable.isNotEmpty() ->
                "the centreline came in but ${unreadable.joinToString(" and ")} could not be read"
            else -> null
        }
    }

    private fun plural(dropped: Int) = if (dropped == 1) "mark" else "marks"

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
            .dropExtension(".xvi")
            .ifBlank { "Imported survey" }

    /** [String.removeSuffix], but a file called `CAVE.SVX` is the same file as `cave.svx`. */
    private fun String.dropExtension(extension: String): String =
        if (endsWith(extension, ignoreCase = true)) dropLast(extension.length) else this
}
