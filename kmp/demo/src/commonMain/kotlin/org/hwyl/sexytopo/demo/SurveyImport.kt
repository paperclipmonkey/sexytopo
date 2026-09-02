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
 * Needs no file picker: whatever a survey file lands in the app's own storage root gets offered
 * for import. On iOS that is drag and drop, via `UIFileSharingEnabled`; the browser has no such
 * folder, so [canPickFiles] platforms put the chosen file into the store root themselves.
 *
 * Only the root is scanned, not the `surveys/` tree: a folder this app wrote is already a survey.
 */
object SurveyImport {

    /** What the file's extension says it is, or null if this app cannot read it. */
    internal fun formatOf(fileName: String): SurveyFormat? =
        when {
            fileName.endsWith(".svx", ignoreCase = true) -> SurveyFormat.SURVEX
            // `.th2` is the *drawing*, which this app exports but has no importer for, so it is
            // deliberately not matched here.
            fileName.endsWith(".th", ignoreCase = true) -> SurveyFormat.THERION
            else -> null
        }

    /**
     * The parts of a saved survey that are not the survey: the two sketches and the version stamp.
     *
     * These have to be recognised so they can be *left out* — every file in a saved survey ends
     * `.json`, so a rule of "any `.json` is a survey" also offered `Swildons.plan.json` beside
     * `Swildons.data.json`, and picking it parsed a drawing as a centreline and failed.
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

    /** PocketTopo's text export (its Export menu), the only one of these that brings a drawing too. */
    private fun isPocketTopo(fileName: String) = fileName.endsWith(".txt", ignoreCase = true)

    /** PocketTopo's own binary file, which its Save writes rather than its Export. */
    private fun isPocketTopoBinary(fileName: String) = fileName.endsWith(".top", ignoreCase = true)

    /** A Therion tracing image: a drawing with no centreline under it. */
    private fun isTracing(fileName: String) = fileName.endsWith(".xvi", ignoreCase = true)

    /**
     * Whether a `.txt` at the root is a PocketTopo export rather than somebody's notes.
     *
     * The only one of these decided by *looking*: every other extension here belongs to a survey
     * format and nothing else, whereas `.txt` belongs to everything. The check is the first
     * non-blank line, which a PocketTopo export always spends on a section header.
     */
    private fun looksLikePocketTopo(store: FileStore, fileName: String): Boolean {
        val text = runCatching { store.readText(listOf(fileName)) }.getOrNull() ?: return false
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return first == "TRIP" || first == "DATA"
    }

    /**
     * Files at the storage root that look like they might be surveys.
     *
     * Named rather than parsed at this point: parsing every file to decide whether to list it would
     * read the lot on every open of the dialog. A file that turns out not to be a survey fails at
     * [import], which says so.
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
     * A survey often arrives as a zip, and unzipping it leaves a folder with the survey's four
     * files inside; listing only files made that folder invisible. [SurveyStorage.isSurveyDirectory]
     * decides, the same test the library itself uses. The app's own `surveys/` directory is
     * excluded, since everything in it is already in the library.
     */
    private fun isSurveyFolder(store: FileStore, name: String): Boolean =
        name != SURVEYS_ROOT.single() &&
            store.isDirectory(listOf(name)) &&
            SurveyStorage.isSurveyDirectory(store, listOf(name))

    /**
     * Reads one, names it something not already taken, and saves it into the library.
     *
     * The name comes from the file rather than from the survey's own name field: the field is what
     * the survey was called on the phone that wrote it, and the filename is what the person who
     * sent it called it — which is the one the surveyor just looked at.
     */
    fun import(library: SurveyLibrary, store: FileStore, fileName: String): Survey? {
        val name = nameFor(fileName)
        val survey =
            if (isSurveyFolder(store, fileName)) {
                // A folder goes through the loader the library itself uses, which already reads
                // all four files. Nothing here needs to know how.
                runCatching { SurveyStorage.load(store, listOf(fileName)) }
                    .onFailure { library.lastError = it.message }
                    .getOrNull()
            } else {
                // The exception's own message, not the generic one `SurveyLibrary.import` falls
                // back to below: a Survex or Therion file with an illegal reading in it gets that
                // reading's own error rather than a generic "could not read" one.
                runCatching { parse(store, fileName, name) }
                    .onFailure { library.lastError = it.message }
                    .getOrNull()
            } ?: return null

        // A loose data file is only ever part of a survey, so the drawings beside it come too —
        // and if one of them is there and will not parse, the library is told rather than silently
        // importing a survey with an empty plan.
        if (!isSurveyFolder(store, fileName) && isNative(fileName)) {
            library.lastWarning = withSketchesBesideIt(store, fileName, survey)
        }
        // The same idea for a Therion project: the `.th` carries the centreline and the tracing
        // images beside it carry the two drawings.
        if (formatOf(fileName) == SurveyFormat.THERION) {
            library.lastWarning = withTracingsBesideIt(store, fileName, survey)
        }
        // An empty survey means the file parsed but held nothing this app understands — a Therion
        // file that is all `scrap`, say. Importing it would put a survey with no legs in the
        // library and look like success.
        //
        // A tracing image is the exception: an `.xvi` has no centreline in it *by definition*, so
        // the guard would otherwise throw away every one of them as empty. It is offered if it
        // brought a drawing, which is the whole of what it holds.
        if (survey.origin.onwardLegs.isEmpty() &&
            !(isTracing(fileName) && survey.planSketch.pathDetails.isNotEmpty())
        ) {
            return null
        }
        survey.name = library.uniqueName(name)
        return if (library.save(survey)) survey else null
    }

    /** Whichever reader the extension calls for. `.top` is the only one that asks for raw bytes. */
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
     * stamp — and this importer takes one file at a time, so without this the sketches would be
     * silently dropped. [SurveyStorage] already reads all four; only the *loose file* path did not.
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
            // Present but unreadable is worth reporting; absent is ordinary (plenty of surveys are
            // handed over as their data file alone).
            runCatching { SketchJson.read(text, survey) }
                .onSuccess {
                    into(it.sketch)
                    // The reader drops a damaged stroke rather than the whole drawing, so a sketch
                    // can arrive short without arriving empty.
                    dropped += it.dropped
                }
                .onFailure { unreadable += name }
        }

        read(SurveyFileType.PLAN_SKETCH) { survey.planSketch = it }
        read(SurveyFileType.EXTENDED_ELEVATION_SKETCH) { survey.elevationSketch = it }

        // The working end, from the metadata file. Not reported when missing or unparseable,
        // unlike the drawings above: losing it is a lost convenience, not a damaged import.
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
     * `TherionImporter` in the Android app reads the `.th` for the centreline and then any `.xvi`
     * beside it for the plan and elevation; this port read the `.th` and stopped.
     *
     * Files are matched by name rather than a fixed suffix, since the suffix is the surveyor's to
     * choose (`Name.plan.xvi`, `Nameplan`, or whatever else they typed into the Therion settings):
     * anything called `Name*.xvi` is a tracing of this survey, and ending in `ee` makes it the
     * elevation rather than the plan.
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
