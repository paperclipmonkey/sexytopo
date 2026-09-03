package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.graph.Projection2D

/**
 * What a Therion export is called and what goes in it: `preferences_export_therion.xml`, as data.
 *
 * A Therion project is not one file: a `.th` centreline, a `.thconfig` that builds it, and per
 * drawing a `.th2` scrap and `.xvi` background, all referring to each other *by name* — so a
 * mismatch here is why xtherion opens with a missing-image complaint, not a cosmetic detail.
 */
data class TherionExport(
    /** `pref_therion_plan_suffix`. Goes in the plan's filename: `Name.plan.th2`. */
    val planSuffix: String = DEFAULT_PLAN_SUFFIX,
    /** `pref_therion_ee_suffix`, for the extended elevation. */
    val elevationSuffix: String = DEFAULT_ELEVATION_SUFFIX,
    /**
     * `pref_therion_xvi_folder`: where background images live, if not beside the scraps. Empty
     * means "beside them"; anything else goes into the `.th2`'s image reference.
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
    /**
     * How many scraps the plan is written as. This and the next three fields aren't
     * `pref_therion_*` preferences — in the Android app they're asked for in a dialog on every
     * export instead. Remembered here so a surveyor sets them once, like the SVG options.
     */
    val planScrapCount: Int = 1,
    /** As [planScrapCount], for the extended elevation — `eeScrapsInput`. */
    val elevationScrapCount: Int = 1,
    /** `stationsInPlanCheckbox`: whether the plan's stations go in its first scrap. */
    val stationsInFirstPlanScrap: Boolean = true,
    /** `stationsInEeCheckbox`. */
    val stationsInFirstElevationScrap: Boolean = true,
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

    /** How many scraps [projection] is written as. */
    fun scrapCountFor(projection: Projection2D): Int =
        if (projection == Projection2D.PLAN) planScrapCount else elevationScrapCount

    /** Whether [projection]'s stations go in its first scrap. */
    fun stationsInFirstScrapFor(projection: Projection2D): Boolean =
        if (projection == Projection2D.PLAN) {
            stationsInFirstPlanScrap
        } else {
            stationsInFirstElevationScrap
        }

    /**
     * These options as the scrap exporter's, with the image reference filled in. [projection]
     * matters because the scrap count and station placement differ between the two drawings.
     */
    fun th2Options(
        xviFileName: String?,
        projection: Projection2D = Projection2D.PLAN,
    ): Th2Exporter.Options =
        Th2Exporter.Options(
            planScrapSuffix = planScrapSuffix,
            elevationScrapSuffix = elevationScrapSuffix,
            planCrossSectionSuffix = planCrossSectionSuffix,
            elevationCrossSectionSuffix = elevationCrossSectionSuffix,
            crossSections = crossSections,
            labels = labels,
            symbols = symbols,
            scrapCount = scrapCountFor(projection),
            stationsInFirstScrap = stationsInFirstScrapFor(projection),
            xviFileName = xviFileName,
        )

    companion object {
        const val DEFAULT_PLAN_SUFFIX = ".plan"
        const val DEFAULT_ELEVATION_SUFFIX = ".ee"

        val DEFAULT = TherionExport()

        /**
         * `TherionExporter.buildExtension` and `SurveyFile.withExtension`, unified: whether a
         * dot goes in, which has three answers depending on what a surveyor typed:
         *
         * | suffix    | result           | why                                      |
         * |-----------|------------------|------------------------------------------|
         * | `""`      | `Name.th2`       | no suffix, no separator to argue about   |
         * | `".plan"` | `Name.plan.th2`  | the dot is theirs, so it is not doubled  |
         * | `"P."`    | `NameP.th2`      | trailing dot is the extension separator  |
         * | `"P"`     | `NameP.th2`      | no dot typed, so none is added           |
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
