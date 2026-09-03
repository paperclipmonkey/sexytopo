package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.io.MetadataJson
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * What a survey is called on disk. Ported from `control/io/SurveyFile` and `SexyTopoConstants`.
 *
 * A survey called Swildons holds `Swildons.data.json`, `Swildons.plan.json`,
 * `Swildons.ext-elevation.json` and `Swildons.metadata.json`, with an `.autosave` sibling for each.
 */
enum class SurveyFileType(val extension: String, val autosaves: Boolean = true) {
    DATA("data.json"),
    METADATA("metadata.json"),
    PLAN_SKETCH("plan.json"),
    EXTENDED_ELEVATION_SKETCH("ext-elevation.json"),

    /** The activity log. The only type with no autosave partner. */
    LOG("log", autosaves = false),
    ;

    fun filenameFor(surveyName: String): String = withExtension(surveyName, extension)

    fun autosaveFilenameFor(surveyName: String): String =
        if (autosaves) {
            withExtension(filenameFor(surveyName), AUTOSAVE_EXTENSION)
        } else {
            filenameFor(surveyName)
        }

    companion object {
        const val AUTOSAVE_EXTENSION = "autosave"

        /** The four files that make up a saved survey. `LOG` is not one of them. */
        val ALL_DATA_TYPES =
            listOf(DATA, METADATA, PLAN_SKETCH, EXTENDED_ELEVATION_SKETCH)

        /**
         * Joins a name and an extension, with the Android app's two escape hatches: a leading `.`
         * means the extension brings its own separator, a leading `|` appends with none at all
         * (how Therion gets `SwildonsP.th2` from a `|P` suffix). Neither is used above, but the
         * exporters' extensions do use them.
         */
        fun withExtension(filename: String, extension: String): String =
            when {
                extension.startsWith(".") -> filename + extension
                extension.startsWith("|") -> filename + extension.substring(1)
                else -> "$filename.$extension"
            }
    }
}

/** Subdirectories of a survey's own directory. Ported from `control/io/SurveyDirectory`. */
enum class SurveySubdirectory(val directoryName: String?) {
    /** The survey directory itself. */
    TOP(null),

    /** Where an imported file is kept, so the original is never lost. */
    IMPORT_SOURCE("Import Source"),

    /** Where exports are written, one subdirectory per format. */
    EXPORT("Exported"),
}

/**
 * Saving and loading surveys. Ported from `control/io/basic/Saver` and `Loader`.
 *
 * ## One bug not carried across
 *
 * `Loader.loadSketches` applies its autosave swap to the plan sketch and **not** to the extended
 * elevation, so "restore autosave" in the Android app returns the autosaved data, metadata and
 * plan alongside the last *explicitly saved* elevation — silently mixing two points in time. Both
 * sketches are swapped here, and `SurveyStorageTest` pins it.
 */
object SurveyStorage {

    /** Stamped into files this port writes, so their provenance is visible. */
    const val DEFAULT_VERSION_NAME = "kmp-port"

    /**
     * Whether [path] looks like a survey directory: it contains a `.data.json` named after itself.
     *
     * The Android app's equivalent is inverted at its one call site —
     * `SexyTopoActivity.deleteSurvey` — so "delete survey" there refuses real surveys and accepts
     * anything else.
     */
    fun isSurveyDirectory(store: FileStore, path: List<String>): Boolean {
        if (!store.isDirectory(path)) return false
        val name = path.lastOrNull() ?: return false
        return store.exists(path + SurveyFileType.DATA.filenameFor(name))
    }

    fun save(
        store: FileStore,
        survey: Survey,
        directory: List<String>,
        versionName: String = DEFAULT_VERSION_NAME,
        versionCode: Int = 0,
    ) = writeAll(store, survey, directory, versionName, versionCode, autosave = false)

    /**
     * Writes the same four files under their `.autosave` names. Nothing in this app calls it
     * directly.
     *
     * The Android app needs autosaves because it holds an unsaved survey in memory; this port has
     * no unsaved state — [save] runs on every edit — so writing one here would just copy what is
     * already on disk.
     *
     * Kept because the *reading* half is not redundant: a survey folder from the Android app can
     * arrive with `.autosave` files newer than the ones beside them, and [load] needs the flag to
     * prefer them.
     */
    fun autosave(
        store: FileStore,
        survey: Survey,
        directory: List<String>,
        versionName: String = DEFAULT_VERSION_NAME,
        versionCode: Int = 0,
    ) = writeAll(store, survey, directory, versionName, versionCode, autosave = true)

    private fun writeAll(
        store: FileStore,
        survey: Survey,
        directory: List<String>,
        versionName: String,
        versionCode: Int,
        autosave: Boolean,
    ) {
        store.createDirectory(directory)
        val name = survey.name

        fun nameFor(type: SurveyFileType) =
            if (autosave) type.autosaveFilenameFor(name) else type.filenameFor(name)

        store.writeText(
            directory + nameFor(SurveyFileType.DATA),
            SurveyJson.write(survey, versionName, versionCode),
        )
        // The fourth file, which the Android app reads the active station out of; see `MetadataJson`.
        store.writeText(
            directory + nameFor(SurveyFileType.METADATA),
            MetadataJson.write(survey, versionName, versionCode),
        )
        store.writeText(
            directory + nameFor(SurveyFileType.PLAN_SKETCH),
            SketchJson.write(survey.planSketch, name),
        )
        store.writeText(
            directory + nameFor(SurveyFileType.EXTENDED_ELEVATION_SKETCH),
            SketchJson.write(survey.elevationSketch, name),
        )
    }

    /**
     * Reads a survey back.
     *
     * [restoreAutosave] prefers the `.autosave` sibling of each file where one exists, falling
     * back file by file to the saved version — matching the Java. **Nothing in this app passes
     * it.**
     *
     * [onProblem] is told when a drawing came back smaller than the file it was read from: the
     * app saves on every change, so a survey opened with unreadable strokes is silently written
     * back without them on the next edit, turning damage permanent. Default is to say nothing, so
     * every existing caller behaves as it did.
     */
    fun load(
        store: FileStore,
        directory: List<String>,
        restoreAutosave: Boolean = false,
        onProblem: (String) -> Unit = {},
    ): Survey {
        require(isSurveyDirectory(store, directory)) {
            "not a survey directory: ${directory.joinToString("/")}"
        }
        val name = directory.last()

        fun read(type: SurveyFileType): String? {
            if (restoreAutosave) {
                val autosaved = store.readText(directory + type.autosaveFilenameFor(name))
                if (autosaved != null) return autosaved
            }
            return store.readText(directory + type.filenameFor(name))
        }

        val survey = read(SurveyFileType.DATA)?.let { SurveyJson.parse(it) } ?: Survey(name)
        // The directory is the survey's name, as in the Android app, where `Survey.setName` is
        // private and the name comes from the folder rather than from inside the file.
        survey.name = name

        // After the data file so it can override the active station; before the sketches, which
        // don't depend on it.
        read(SurveyFileType.METADATA)?.let { MetadataJson.apply(survey, it) }

        var dropped = 0
        read(SurveyFileType.PLAN_SKETCH)?.let {
            val plan = SketchJson.read(it, survey)
            survey.planSketch = plan.sketch
            dropped += plan.dropped
        }
        read(SurveyFileType.EXTENDED_ELEVATION_SKETCH)?.let {
            val elevation = SketchJson.read(it, survey)
            survey.elevationSketch = elevation.sketch
            dropped += elevation.dropped
        }
        if (dropped > 0) {
            val marks = if (dropped == 1) "mark" else "marks"
            onProblem("$dropped $marks of the drawing could not be read")
        }
        return survey
    }

    /** Every survey directory directly inside [path], by name. */
    fun listSurveys(store: FileStore, path: List<String>): List<String> =
        store.list(path).filter { isSurveyDirectory(store, path + it) }
}
