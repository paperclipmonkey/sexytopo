package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationChoice
import org.hwyl.sexytopo.shared.comms.AutoReconnect
import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.sketch.SketchDefaults
import org.hwyl.sexytopo.shared.sketch.SketchStyle
import org.hwyl.sexytopo.shared.survey.amalgamation.LegAmalgamationAlgorithm
import org.hwyl.sexytopo.shared.survey.SurveySettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every setting starts where the Android app starts it.
 *
 * A default is the one value every surveyor gets without ever opening the settings, so a default
 * that differs is a difference everybody sees. This reads the app's `preferences_*.xml` — the
 * screens — and `SketchPreferences.Toggle` — the drawing menu — and holds each of this port's
 * defaults to them.
 *
 * With one complication that is the Android app's, not this port's. The screens declare
 * `android:defaultValue`, but nothing ever calls `PreferenceManager.setDefaultValues`, so on a
 * fresh install the key is absent and each getter's own fallback wins instead; and for eleven of
 * them the two disagree. The screen says the compass is off by default and the app draws it on;
 * the screen says the symbol size is 35 and the app draws 25. What a surveyor gets is the getter,
 * so that is the oracle here — listed under [codeWins] with both values, so the disagreement is
 * on record rather than silently resolved. `SketchStyleTest` reached the same conclusion for its
 * four numbers first.
 *
 * Two settings this port takes the *screen*'s side on deliberately, and one it has no counterpart
 * for; those are the other two lists.
 */
class PreferenceDefaultsTest {

    private val xmlDir = File("../../app/src/main/res/xml")

    /** Every key with a declared default across the preference screens, as the XML spells it. */
    private val screenDefaults: Map<String, String> by lazy {
        val found = mutableMapOf<String, String>()
        xmlDir.listFiles { f -> f.name.startsWith("preferences") && f.extension == "xml" }
            .orEmpty()
            .forEach { file ->
                Regex("""<\w+Preference\b(.*?)(?:/>|>)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(file.readText())
                    .forEach { match ->
                        val attrs = match.groupValues[1]
                        val key = Regex("""android:key="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                        val value = Regex("""android:defaultValue="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                        if (key != null && value != null) found[key] = value
                    }
            }
        found
    }

    /**
     * Where the getter's fallback and the screen's default disagree, and which the app really
     * uses: `GeneralPreferences.getX(key, fallback)` on the left, `android:defaultValue` on the
     * right. The port follows the left.
     */
    private val codeWins =
        mapOf(
            "pref_calibration_algorithm" to ("linear" to "auto"),
            "pref_export_svg_grid" to ("true" to "false"),
            "pref_survey_text_tool_font_size" to ("16" to "50"),
            "pref_survey_symbol_size" to ("25" to "35"),
            "pref_station_label_font_size_sp" to ("10" to "8"),
            "pref_legend_font_size_sp" to ("10" to "8"),
        )

    /**
     * Where this port takes the screen at its word instead. The buzz is the one that matters: the
     * service's own `getBoolean(key, false)` means the app has never once buzzed on a fresh
     * install while its settings screen said it would, and a switch that is shown on is on here.
     */
    private val screenWins = setOf("pref_vibrate_on_new_station")

    /** Settings with no counterpart here, and why — the README's "last four" and the export folder. */
    private val notHere =
        mapOf(
            "pref_orientation" to "locking the screen is the host's, not the Kotlin's",
            "pref_anti_alias" to "Compose has no switch for it",
            "pref_export_folder_name" to "exports go to the platform's save dialog, not a folder",
            "pref_export_type_subfolders" to "exports go to the platform's save dialog, not a folder",
        )

    /** What this port starts with, by the Android key, rendered the way the XML spells values. */
    private val portDefaults: Map<String, String> by lazy {
        val p = AppPreferences.DEFAULT
        val style = SketchStyle.DEFAULT
        val svg = SvgExporter.Options.DEFAULT
        val th = TherionExport.DEFAULT
        val settings = SurveySettings()
        fun num(value: Float) = if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        mapOf(
            "pref_developer_mode" to p.developerMode.toString(),
            "pref_vibrate_on_new_station" to p.buzzOnNewStation.toString(),
            "pref_hot_corners" to p.hotCorners.toString(),
            "pref_delete_path_fragments" to p.deletePathFragments.toString(),
            "pref_highlight_latest_leg" to p.highlightLatestLeg.toString(),
            "pref_two_finger_movement" to p.twoFingerMove.toString(),
            "pref_legacy_cross_sections" to p.legacyCrossSections.toString(),
            "pref_survey_text_tool_font_size" to num(style.textSizeSp),
            "pref_survey_symbol_size" to num(style.symbolSizeDp),
            "pref_sketch_line_width" to num(style.sketchLineWidthDp),
            "pref_leg_width" to num(style.legWidthDp),
            "pref_splay_width" to num(style.splayWidthDp),
            "pref_station_diameter" to num(style.stationDiameterDp),
            "pref_station_label_font_size_sp" to num(style.stationLabelSizeSp),
            "pref_legend_font_size_sp" to num(style.legendSizeSp),
            "pref_manual_controls" to p.manualControls.toString(),
            "pref_lrud_fields" to p.lrudFields.toString(),
            "pref_deg_mins_secs" to p.azimuthInDms.toString(),
            "pref_inc_deg_mins_secs" to p.inclinationInDms.toString(),
            "pref_leg_amalgamation_algorithm" to
                when (settings.legAmalgamationAlgorithm) {
                    LegAmalgamationAlgorithm.ANGULAR -> "angular"
                    else -> settings.legAmalgamationAlgorithm.name.lowercase()
                },
            "pref_max_distance_delta" to settings.maxDistanceDelta.toString(),
            "pref_max_angle_delta" to settings.maxAngleDelta.toString(),
            "pref_max_endpoint_delta" to settings.maxEndpointDelta.toString(),
            "pref_max_pairwise_error" to settings.maxPairwiseError.toString(),
            "pref_auto_reconnect_window" to AutoReconnect.DEFAULT_WINDOW_MINUTES.toString(),
            "pref_calibration_algorithm" to p.calibrationAlgorithm.let {
                when (it) {
                    CalibrationChoice.LINEAR -> "linear"
                    CalibrationChoice.AUTO -> "auto"
                    CalibrationChoice.NON_LINEAR -> "nonlinear"
                }
            },
            "pref_export_svg_background" to if (svg.whiteBackground) "white" else "transparent",
            "pref_export_svg_legend" to svg.showLegend.toString(),
            "pref_export_svg_north_arrow" to svg.showNorthArrow.toString(),
            "pref_export_svg_scale_bar" to svg.showScaleBar.toString(),
            "pref_export_svg_team" to svg.showTeam.toString(),
            "pref_export_svg_cross_sections" to svg.showCrossSections.toString(),
            "pref_export_svg_symbols" to svg.showSymbols.toString(),
            "pref_export_svg_centreline" to svg.showCentreline.toString(),
            "pref_export_svg_stations" to svg.showStations.toString(),
            "pref_export_svg_splays" to svg.showSplays.toString(),
            "pref_export_svg_grid" to svg.showGrid.toString(),
            "pref_export_svg_tagline" to svg.showTagline.toString(),
            "pref_export_svg_copyright" to svg.showCopyright.toString(),
            "pref_export_svg_stroke_width" to svg.sketchStrokeWidth.toString(),
            "pref_export_svg_leg_width" to svg.legStrokeWidth.toString(),
            "pref_export_svg_splay_width" to svg.splayStrokeWidth.toString(),
            "pref_therion_plan_suffix" to th.planSuffix,
            "pref_therion_ee_suffix" to th.elevationSuffix,
            "pref_therion_xvi_folder" to th.xviFolder,
            "pref_therion_plan_scrap_suffix" to th.planScrapSuffix,
            "pref_therion_ee_scrap_suffix" to th.elevationScrapSuffix,
            "pref_therion_cross_sections" to th.crossSections.toString(),
            "pref_therion_plan_xs_suffix" to th.planCrossSectionSuffix,
            "pref_therion_ee_xs_suffix" to th.elevationCrossSectionSuffix,
            "pref_therion_export_symbols" to th.symbols.toString(),
            "pref_therion_export_text" to th.labels.toString(),
        )
    }

    @Test
    fun everyScreenDefaultIsMatchedOrAccountedFor() {
        assertTrue(screenDefaults.size >= 50, "the preference screens have moved: ${screenDefaults.size} keys read")

        val problems = mutableListOf<String>()
        for ((key, screen) in screenDefaults) {
            if (key in notHere) continue
            val expected = codeWins[key]?.first ?: screen
            val actual = portDefaults[key]
            when {
                actual == null -> problems.add("$key: the app defaults it to \"$screen\" and this port has no default listed for it")
                actual != expected -> problems.add("$key: the app starts at \"$expected\", this port at \"$actual\"")
            }
        }
        assertEquals(emptyList(), problems, "defaults have drifted from the Android app's")
    }

    /**
     * The drawing menu's toggles, whose defaults live in `SketchPreferences.Toggle` rather than in
     * any XML: `SHOW_COMPASS(R.id.buttonShowCompass, true)` and its eleven siblings.
     */
    @Test
    fun theDrawingMenuStartsWhereTheAppStartsIt() {
        val source = File("../../app/src/main/java/org/hwyl/sexytopo/control/util/SketchPreferences.java").readText()
        val toggles =
            Regex("""(\w+)\(R\.id\.\w+,\s*(true|false)\)""").findAll(source).associate {
                it.groupValues[1] to it.groupValues[2].toBoolean()
            }
        assertEquals(12, toggles.size, "SketchPreferences.Toggle has changed shape: $toggles")

        val p = AppPreferences.DEFAULT
        val here =
            mapOf(
                "AUTO_RECENTRE" to p.autoRecentre,
                "SNAP_TO_LINES" to p.snapToLines,
                "FADE_NON_ACTIVE" to p.fadeNonActive,
                "SHOW_GRID" to p.showGrid,
                "SHOW_SPLAYS" to p.showSplays,
                "SHOW_SKETCH" to p.showSketch,
                "SHOW_STATION_LABELS" to p.showStationLabels,
                "SHOW_X_SECTIONS" to p.showCrossSections,
                "SHOW_COMPASS" to p.showCompass,
                "BLUE_WATER" to p.blueWater,
                "PINCH_TO_ZOOM" to p.pinchToZoom,
                // Cross-survey links are a deliberate gap, so its toggle has nowhere to be shown;
                // the value is still held, because the file format carries it.
                "SHOW_CONNECTIONS" to true,
            )
        val wrong = toggles.filter { (name, value) -> here[name] != value }.keys
        assertEquals(emptySet(), wrong, "these toggles start differently here: ${wrong.map { "$it app=${toggles[it]} port=${here[it]}" }}")
        assertEquals(SketchDefaults.SNAP_TO_LINES_DEFAULT, toggles["SNAP_TO_LINES"])
    }

    /** A disagreement between screen and code that the app has since resolved is a stale entry. */
    @Test
    fun theListOfDisagreementsIsStillTrue() {
        val stale = codeWins.filter { (key, pair) -> screenDefaults[key] != pair.second }.keys
        assertEquals(emptySet(), stale, "the screen no longer says this; update codeWins")
    }
}
