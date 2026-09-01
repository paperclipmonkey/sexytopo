package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.io.MetadataJson
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * What a survey is called on disk. Ported from `control/io/SurveyFile` and `SexyTopoConstants`.
 *
 * A survey called Swildons lives in a directory of that name and holds `Swildons.data.json`,
 * `Swildons.plan.json`, `Swildons.ext-elevation.json` and `Swildons.metadata.json`, with an
 * `.autosave` sibling for each.
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
         * Joins a name and an extension, with the Android app's two escape hatches.
         *
         * A leading `.` means the extension brings its own separator; a leading `|` means append
         * with no separator at all, which is how Therion gets `SwildonsP.th2` from a `|P` suffix.
         * Neither is used by the types above, and both are kept because the exporters' extensions
         * do use them and they must agree about what a filename is.
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
 * These are pure orchestration in the Java too - 212 lines with no logic in them - because all the
 * format work is in the translators. Here those are [SurveyJson] and [SketchJson], which are
 * already String-in, String-out, so this layer only has to decide filenames and call a
 * [FileStore].
 *
 * ## One bug not carried across
 *
 * `Loader.loadSketches` applies its autosave swap to the plan sketch and **not** to the extended
 * elevation, so "restore autosave" in the Android app returns the autosaved data, metadata and plan
 * alongside the last *explicitly saved* elevation - silently mixing two points in time. Its log
 * line prints the plan's filename for the elevation file too, which is presumably how it went
 * unnoticed. Both sketches are swapped here, and `SurveyStorageTest` pins it.
 */
object SurveyStorage {

    /** Stamped into files this port writes, so their provenance is visible. */
    const val DEFAULT_VERSION_NAME = "kmp-port"

    /**
     * Whether [path] looks like a survey directory: it contains a `.data.json` named after itself.
     *
     * The Android app's equivalent, `IoUtils.isSurveyDirectory`, is worth a glance - the caller in
     * `SexyTopoActivity.deleteSurvey` inverts it, so "delete survey" there refuses real surveys and
     * accepts anything else.
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
     * Writes the same four files under their `.autosave` names, which nothing in this app does.
     *
     * Ported and kept rather than left out, and unused rather than wired up - both on purpose, so
     * this is the note that says so instead of a reader finding a dead function.
     *
     * The Android app needs autosaves because it holds an unsaved survey in memory and writes the
     * real files when you ask it to: `action_file_save`, `action_file_save_as` and *Restore
     * Autosave* are all in its menu and in the manual this app ships. This port has no unsaved
     * state at all - [save] runs on every edit, so an `.autosave` here would be a copy of what is
     * already on disk, and *Restore Autosave* would offer to recover a survey from itself.
     *
     * It stays because the *reading* half is not redundant: a survey folder written by the Android
     * app can arrive with `.autosave` files in it, newer than the files beside them, and a port
     * that could not prefer them would silently open the older copy. [load] takes the flag for
     * that case. Wiring a caller to it is a question about which one a surveyor meant, not about
     * whether the code works.
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
        // The fourth file, which the Android app reads the active station out of and this port
        // wrote nowhere until now. See `MetadataJson`: a survey written without it opens on
        // Android at the origin rather than at the working end, quietly.
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
     * [restoreAutosave] prefers the `.autosave` sibling of each file where one exists, falling back
     * to the saved version file by file - which is what the Java does, and is the right behaviour:
     * an autosave that only got as far as the data file should still give you the saved sketches
     * rather than nothing. **Nothing in this app passes it, and nothing calls [autosave]** - see
     * the note there.
     *
     * [onProblem] is told when a drawing came back smaller than the file it was read from.
     *
     * Worth a callback rather than nothing, because the consequence outlives the read: the app
     * saves on every change, so a survey opened with three unreadable strokes is *written back*
     * without them the moment anything is edited. The damaged file was at least still damaged;
     * after that it is tidily, permanently short. A surveyor who is told can copy the file
     * somewhere before touching the survey. Default is to say nothing, so every existing caller
     * behaves as it did.
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

        // After the data file, because it overrides what that said - the Android app keeps the
        // active station here and this is the only copy a survey from that app has. Before the
        // sketches only because nothing in them depends on it.
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
