package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.graph.Projection2D

/**
 * What a Therion export is called and what goes in it: `preferences_export_therion.xml`, as data.
 *
 * Ten preferences, and the reason they exist is that a Therion project is not one file. A survey
 * leaves this app as a `.th` of centreline, a `.thconfig` that builds it, and — per drawing — a
 * `.th2` scrap file and the `.xvi` background image the scrap is traced over. Every one of those
 * refers to the others *by name*, so a surveyor whose project is laid out differently from the
 * app's assumptions gets a set of files that xtherion opens with a missing-image complaint and no
 * background at all. Which is to say: these are not cosmetic, they are whether the project builds.
 *
 * [Th2Exporter.Options] has carried seven of them since the scrap exporter was ported and nothing
 * ever set any. The three that were not represented at all are the two filename suffixes and
 * [xviFolder], and they are the three that decide what the files are *called*.
 */
data class TherionExport(
    /** `pref_therion_plan_suffix`. Goes in the plan's filename: `Name.plan.th2`. */
    val planSuffix: String = DEFAULT_PLAN_SUFFIX,
    /** `pref_therion_ee_suffix`, for the extended elevation. */
    val elevationSuffix: String = DEFAULT_ELEVATION_SUFFIX,
    /**
     * `pref_therion_xvi_folder`: where the surveyor keeps background images, if not beside the
     * scraps.
     *
     * Empty by default, which means "beside them". Anything else is written into the `.th2`'s
     * image reference, because that is the half that decides whether xtherion finds the picture.
     */
    val xviFolder: String = "",
    /** `pref_therion_plan_scrap_suffix`. Names the scrap *inside* the file: `name-plan`. */
    val planScrapSuffix: String = "-plan",
    /** `pref_therion_ee_scrap_suffix`. */
    val elevationScrapSuffix: String = "-ee",
    /** `pref_therion_plan_xs_suffix`. `#` is where the station's name goes. */
    val planCrossSectionSuffix: String = "PX#",
    /** `pref_therion_ee_xs_suffix`. */
    val elevationCrossSectionSuffix: String = "EEX#",
    /** `pref_therion_cross_sections`: write the cross-section scraps at all. */
    val crossSections: Boolean = true,
    /** `pref_therion_export_symbols`. */
    val symbols: Boolean = true,
    /** `pref_therion_export_text`. */
    val labels: Boolean = true,
) {
    /** The filename suffix for one drawing. */
    fun suffixFor(projection: Projection2D): String =
        if (projection == Projection2D.PLAN) planSuffix else elevationSuffix

    /** What one drawing's file is called: see [fileName]. */
    fun fileNameFor(base: String, projection: Projection2D, extension: String): String =
        fileName(base, suffixFor(projection), extension)

    /** The path the `.th2` should name for its background image. */
    fun xviReference(base: String, projection: Projection2D): String {
        val name = fileNameFor(base, projection, "xvi")
        val folder = xviFolder.trim().trim('/')
        return if (folder.isEmpty()) name else "$folder/$name"
    }

    /** These options as the scrap exporter's, with the image reference filled in. */
    fun th2Options(xviFileName: String?): Th2Exporter.Options =
        Th2Exporter.Options(
            planScrapSuffix = planScrapSuffix,
            elevationScrapSuffix = elevationScrapSuffix,
            planCrossSectionSuffix = planCrossSectionSuffix,
            elevationCrossSectionSuffix = elevationCrossSectionSuffix,
            crossSections = crossSections,
            labels = labels,
            symbols = symbols,
            xviFileName = xviFileName,
        )

    companion object {
        const val DEFAULT_PLAN_SUFFIX = ".plan"
        const val DEFAULT_ELEVATION_SUFFIX = ".ee"

        val DEFAULT = TherionExport()

        /**
         * `TherionExporter.buildExtension` and `SurveyFile.withExtension`, which are one rule
         * written in two places and are easier to read as one.
         *
         * The rule is about *whether a dot goes in*, and it has three answers because a surveyor
         * might reasonably type any of three things:
         *
         * | suffix    | result           | why                                      |
         * |-----------|------------------|------------------------------------------|
         * | `""`      | `Name.th2`       | no suffix, no separator to argue about   |
         * | `".plan"` | `Name.plan.th2`  | the dot is theirs, so it is not doubled  |
         * | `"P."`    | `NameP.th2`      | trailing dot is the extension separator  |
         * | `"P"`     | `NameP.th2`      | no dot typed, so none is added           |
         *
         * The Java carries the third and fourth cases through a `"|"` marker prepended to the
         * extension string and stripped again three files away. There is nothing to port about
         * that; what has to be preserved is the answer.
         */
        fun fileName(base: String, suffix: String, extension: String): String =
            when {
                suffix.isEmpty() -> "$base.$extension"
                suffix.startsWith(".") -> "$base$suffix.$extension"
                suffix.endsWith(".") -> "$base$suffix$extension"
                else -> "$base$suffix.$extension"
            }
    }
}
