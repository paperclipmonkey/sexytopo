package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationChoice
import org.hwyl.sexytopo.shared.comms.AutoReconnect
import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.sketch.SketchDefaults
import org.hwyl.sexytopo.shared.sketch.SketchStyle
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.InputMode
import org.hwyl.sexytopo.shared.survey.LrudMode

/**
 * Light, dark, or whatever the phone says. `pref_theme`'s three values, under their own names.
 *
 * A cave is dark and a phone screen is the brightest thing in it, so this is not a matter of
 * taste underground: a white page at full brightness costs a surveyor their night vision and the
 * battery they need to get out. The Android app's default is [AUTO] and so is this one, but auto
 * on a phone means "is it evening" and not "am I underground", which is why the other two exist.
 */
enum class AppTheme(
    /** What `pref_theme` stores, so a reader can match the two up. */
    val key: String,
    /** What the menu row says. */
    val label: String,
) {
    /** Follow the phone: `MODE_NIGHT_FOLLOW_SYSTEM`. */
    AUTO("auto", "Automatic"),

    /** `MODE_NIGHT_NO`. */
    LIGHT("light", "Light"),

    /** `MODE_NIGHT_YES`. */
    DARK("dark", "Dark"),
    ;

    /** Whether to draw dark, given what the platform reports. */
    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            AUTO -> systemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        /** Unknown text reads as the default rather than throwing: see [AppPreferencesStore]. */
        fun of(key: String?): AppTheme? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The settings that are about the app rather than about surveying.
 *
 * A separate file from the tolerances for the same reason the Android app has a separate
 * `preferences_general.xml`: these do not change what a reading means, and somebody adjusting their
 * instrument's tolerances is not looking for them.
 */
data class AppPreferences(
    /** Buzz when three readings promote to a station. `pref_vibrate_on_new_station`. */
    val buzzOnNewStation: Boolean = DEFAULT_BUZZ_ON_NEW_STATION,
    /** A touch in a corner of the sketch pans it, whatever tool is selected. `pref_hot_corners`. */
    val hotCorners: Boolean = DEFAULT_HOT_CORNERS,
    /** A two-fingered drag pans the sketch, likewise. `pref_two_finger_movement`. */
    val twoFingerMove: Boolean = DEFAULT_TWO_FINGER_MOVE,
    /** Put the view back on the active station each time one is made. `AUTO_RECENTRE`. */
    val autoRecentre: Boolean = DEFAULT_AUTO_RECENTRE,
    /** Draw everything but the working end at a fifth alpha. `FADE_NON_ACTIVE`. */
    val fadeNonActive: Boolean = DEFAULT_FADE_NON_ACTIVE,
    /** Draw the leg just taken in magenta. `pref_highlight_latest_leg`. */
    val highlightLatestLeg: Boolean = DEFAULT_HIGHLIGHT_LATEST_LEG,
    /** Stamp the water symbol in blue whatever colour the brush is. `BLUE_WATER`. */
    val blueWater: Boolean = DEFAULT_BLUE_WATER,
    /** Draw cross-sections on the plan, and let them be tapped. `SHOW_X_SECTIONS`. */
    val showCrossSections: Boolean = DEFAULT_SHOW_CROSS_SECTIONS,
    /** Draw them the old way: no frame, no drag bar, no editing. `pref_legacy_cross_sections`. */
    val legacyCrossSections: Boolean = DEFAULT_LEGACY_CROSS_SECTIONS,
    /** Let two fingers zoom the drawing and the 3D view. `PINCH_TO_ZOOM`. */
    val pinchToZoom: Boolean = DEFAULT_PINCH_TO_ZOOM,
    /** Draw the splay shots. `SHOW_SPLAYS`. */
    val showSplays: Boolean = DEFAULT_SHOW_SPLAYS,
    /** Draw the sketch over the centreline. `SHOW_SKETCH`. */
    val showSketch: Boolean = DEFAULT_SHOW_SKETCH,
    /** Write each station's name beside it. `SHOW_STATION_LABELS`. */
    val showStationLabels: Boolean = DEFAULT_SHOW_STATION_LABELS,
    /** Draw the metre grid behind everything. `SHOW_GRID`. */
    val showGrid: Boolean = DEFAULT_SHOW_GRID,
    /** Jump a new stroke to the end of a nearby one. `SNAP_TO_LINES`. */
    val snapToLines: Boolean = DEFAULT_SNAP_TO_LINES,
    /** Draw the north arrow on the plan. `SHOW_COMPASS`. */
    val showCompass: Boolean = DEFAULT_SHOW_COMPASS,
    /** Give the drawing the app bar's height as well. `action_fullscreen`. */
    val fullScreen: Boolean = DEFAULT_FULL_SCREEN,
    /** Light, dark, or what the phone says. `pref_theme`. */
    val theme: AppTheme = DEFAULT_THEME,
    /** How the instrument is being held. The `inputMode` key in `generalPrefs`. */
    val inputMode: InputMode = DEFAULT_INPUT_MODE,
    /** The tool the toolbar has lit. `pref_sketch_sketch_tool`. */
    val tool: SketchTool = DEFAULT_TOOL,
    /** The brush. `pref_sketch_brush_colour`. */
    val brushColour: Colour = DEFAULT_BRUSH_COLOUR,
    /** Which symbol the stamp will place. `pref_sketch_symbol`. */
    val symbol: Symbol = DEFAULT_SYMBOL,
    /** Chase an instrument that drops out. `pref_auto_reconnect`. */
    val autoReconnect: Boolean = AutoReconnect.DEFAULT_ENABLED,
    /** For how long, from the first failure of a run. `pref_auto_reconnect_window`. */
    val autoReconnectWindowMinutes: Int = AutoReconnect.DEFAULT_WINDOW_MINUTES,
    /**
     * Whether the eraser rubs out part of a wall line or the whole of it.
     * `pref_delete_path_fragments`, on by default.
     *
     * The engine has done this since the sketch was ported — `SketchEditor.eraseAt` takes the
     * flag and splits the stroke — and nothing ever passed it anything but the default, so the
     * app had the behaviour and not the choice. Finding 53.
     */
    val deletePathFragments: Boolean = SketchDefaults.DELETE_PATH_FRAGMENTS_DEFAULT,
    /** How big everything on the drawing is. `preferences_sketching.xml`'s numeric group. */
    val sketchStyle: SketchStyle = SketchStyle.DEFAULT,
    /**
     * Offer the buttons that put a reading in by hand. `pref_manual_controls`.
     *
     * On, as upstream has it. The Android app applies it to the two floating buttons on its table
     * view; here it is the *Add reading* button on the field bar, which is the same control.
     */
    val manualControls: Boolean = true,
    /**
     * Offer the four passage measurements beside the reading. `pref_lrud_fields`, off upstream.
     *
     * For a compass-and-tape survey this is the whole workflow in one dialog: stand at the
     * station, measure the four walls, shoot on. Without it the passage size is a second dialog on
     * a station the surveyor has already walked away from.
     */
    val lrudFields: Boolean = false,
    /** Type a bearing as degrees, minutes and seconds. `pref_deg_mins_secs`. */
    val azimuthInDms: Boolean = false,
    /** And an inclination. `pref_inc_deg_mins_secs`, a separate preference upstream. */
    val inclinationInDms: Boolean = false,
    /**
     * What goes into an SVG export: `preferences_export_svg.xml`, seventeen settings of it.
     *
     * The exporter has taken all seventeen since it was ported and every caller passed
     * [SvgExporter.Options.DEFAULT], so the app had the whole feature and offered none of it -
     * finding 59, and the same shape as findings 48, 49 and 53. Whether the drawing carries a
     * grid, a legend, the team's names or the splays is not a matter of taste: it is what the
     * person receiving the file can do with it, and it is decided once, at export.
     */
    val svgExport: SvgExporter.Options = SvgExporter.Options.DEFAULT,
    /**
     * What a Therion export is called and what goes in it: `preferences_export_therion.xml`.
     *
     * The same story as [svgExport] and the reason both were found in one sweep -
     * [org.hwyl.sexytopo.shared.io.export.Th2Exporter.Options] has carried seven of these ten
     * since the scrap exporter was ported, and every caller passed the defaults. The three that
     * were not represented at all are the ones that decide what the files are *called*, which for
     * a format whose files refer to each other by name is the difference between a project that
     * builds and a pile of files.
     */
    val therionExport: TherionExport = TherionExport.DEFAULT,
    /**
     * Which calibration fit to run. `pref_calibration_algorithm`.
     *
     * The port had the *choice* on the calibration screen and not the *setting*: a chip that reset
     * to Linear every time the dialog opened. That is finding 49's shape once more, and it is
     * worse here than most, because a surveyor recalibrating an X310 does fifty-six shots and then
     * has to remember to move a chip before pressing Solve.
     */
    val calibrationAlgorithm: CalibrationChoice = CalibrationChoice.DEFAULT,
    /**
     * Write every instrument frame to the log, decoded or not. `pref_developer_mode`.
     *
     * Upstream declares this key, gives it a preference screen of its own, and reads it nowhere -
     * `isDeveloperModeOn` has no callers at all. This is what it does here: the trace that tells a
     * surveyor whether an instrument that seems to be shooting is reaching the app, which is
     * otherwise indistinguishable from a radio that never connected.
     */
    val developerMode: Boolean = false,
    /**
     * Which bearing left and right are taken square to. `pref_lrud_direction`.
     *
     * Upstream reads this key and has no settings entry for it at all - see the note in the
     * README about preferences nobody can reach - so this offers a choice the Android app makes
     * for you. [LrudMode.SURVEY] bisects the corner at a bend, which is what most cavers mean by a
     * left-hand wall; [LrudMode.SHOT] takes it square to the outgoing leg alone.
     */
    val lrudMode: LrudMode = LrudMode.DEFAULT,
) {
    /** The two above as the value [LocalAngleEntry] carries. */
    val angleEntry: AngleEntry
        get() = AngleEntry(azimuthInDms, inclinationInDms)

    /** The two above as the value [ReconnectionPolicy] reads. */
    val reconnection: AutoReconnect
        get() = AutoReconnect(autoReconnect, autoReconnectWindowMinutes)

    companion object {
        /**
         * On, which is what `preferences_general.xml` says and what the Android settings screen
         * shows — see the note in [AppPreferencesStore] about the two disagreeing.
         */
        const val DEFAULT_BUZZ_ON_NEW_STATION = true

        /** `GeneralPreferences.isHotCornersModeActive` reads this key defaulting to true. */
        const val DEFAULT_HOT_CORNERS = true

        /**
         * Off, as in `GeneralPreferences.isTwoFingerModeActive`.
         *
         * A surveyor holding the phone in one hand rests a second finger on the glass more often
         * than they mean to pan with it, which is presumably why the Android app ships this off
         * while shipping the corners on. Pinch-to-zoom is not gated on it: that is always live, in
         * this port as in the original, where `ScaleGestureDetector` runs ahead of every tool.
         */
        const val DEFAULT_TWO_FINGER_MOVE = false

        /**
         * Off, as `SketchPreferences.Toggle.AUTO_RECENTRE` is.
         *
         * Worth turning on for a long passage, though, and worth knowing why: without it this port
         * re-fits the *whole cave* as the survey grows, so by the fiftieth station the working end
         * is a few pixels across and the surveyor is pinching in after every leg. Auto-recentre
         * keeps the active station in the middle at the zoom they chose instead.
         */
        const val DEFAULT_AUTO_RECENTRE = false

        /**
         * Off, as `SketchPreferences.Toggle.FADE_NON_ACTIVE` is.
         *
         * What it is for: in a cave of any size the plan is a page of red lines that all look
         * alike, and the question a surveyor keeps asking is "where am I on this". Fading
         * everything that does not hang off the working station answers it without moving the
         * view, which matters when the sketch around you is the part you are drawing.
         */
        const val DEFAULT_FADE_NON_ACTIVE = false

        /** On, as `GeneralPreferences.isHighlightLatestLegModeOn` is. */
        const val DEFAULT_HIGHLIGHT_LATEST_LEG = true

        /**
         * On, as `SketchPreferences.Toggle.BLUE_WATER` is.
         *
         * Water is drawn blue by convention on every cave survey ever published, and a surveyor
         * who has the brush set to black for wall outlines should not have to remember to change
         * it and change it back to stamp a stream.
         */
        const val DEFAULT_BLUE_WATER = true

        /**
         * On, as `SketchPreferences.Toggle.SHOW_X_SECTIONS` is.
         *
         * Turning it off does two things in the Android app, not one: the sections stop being
         * drawn *and* stop being touchable — "special case: can't tap on invisible X-sections",
         * as `handleCrossSectionBodyTap` puts it. A port that hid them and left the hit test live
         * would open an editor from a tap on apparently blank paper.
         */
        const val DEFAULT_SHOW_CROSS_SECTIONS = true

        /**
         * Off, as `GeneralPreferences.isLegacyCrossSectionsOn` has it.
         *
         * The Android app kept the old drawing behind a setting when it gained the editable frame,
         * which is the polite thing to do to a surveyor who has drawn a hundred sections one way.
         * It is offered here for the same reason and not because the port needs it - the frame is
         * the default in both.
         */
        const val DEFAULT_LEGACY_CROSS_SECTIONS = false

        /**
         * On, as `SketchPreferences.Toggle.PINCH_TO_ZOOM` is.
         *
         * Worth having off for anyone drawing with a stylus and a resting hand, which is what a
         * second contact usually is then. It gates the *zoom* and not the two-fingered pan, which
         * is its own preference — see [DEFAULT_TWO_FINGER_MOVE].
         */
        const val DEFAULT_PINCH_TO_ZOOM = true

        /**
         * The five below were session-only in this port until the drawing menu was split, which
         * is to say a surveyor who turned the splays off got them back on the next run. All five
         * are `SketchPreferences.Toggle`s in the Android app — persisted, with these defaults —
         * and there was never a reason for them to behave differently here. The reason they were
         * missed is worth writing down: they were reached through [DemoState] rather than through
         * this class, so nothing about the code drew attention to the fact that they went nowhere.
         */
        const val DEFAULT_SHOW_SPLAYS = true

        /** On, as `SketchPreferences.Toggle.SHOW_SKETCH` is. */
        const val DEFAULT_SHOW_SKETCH = true

        /** On, as `SketchPreferences.Toggle.SHOW_STATION_LABELS` is. */
        const val DEFAULT_SHOW_STATION_LABELS = true

        /** On, as `SketchPreferences.Toggle.SHOW_GRID` is. */
        const val DEFAULT_SHOW_GRID = true

        /**
         * Off, as `SketchPreferences.Toggle.SNAP_TO_LINES` is — the shared model's own default,
         * so the drawing code and the preference cannot say different things.
         *
         * A passage wall is drawn as a series of strokes and the joins between them are where a
         * drawing looks amateur; worse, a wall with a gap in it is one no tracing tool can fill.
         * The app still ships it off, because snapping when you did not mean to is its own
         * annoyance.
         */
        const val DEFAULT_SNAP_TO_LINES = SketchDefaults.SNAP_TO_LINES_DEFAULT

        /**
         * On, as `SketchPreferences.Toggle.SHOW_COMPASS` is.
         *
         * The arrow this draws does not swing with the phone — that needs a magnetometer this port
         * does not have — but a plan does not need one to be true: `Projection2D.PLAN` maps the
         * northing to minus the screen y, so north really is up. What the toggle is for is the
         * surveyor who wants the corner of the screen back.
         */
        const val DEFAULT_SHOW_COMPASS = true

        /**
         * Off, as `GeneralPreferences.isImmersiveModeOn` is.
         *
         * Worth more than it sounds in landscape, which is how a wide passage gets drawn: the
         * app's own chrome is about half a phone screen turned sideways, and the app bar is the
         * part a surveyor mid-stroke has no use for. It is off by default because a first run
         * that hides its own menu is a first run nobody gets out of.
         */
        const val DEFAULT_FULL_SCREEN = false

        /**
         * Follow the phone, as `GeneralPreferences.getTheme` does with its `"auto"` fallback.
         *
         * Unlike the vibration key above, this one really is the app's behaviour and not just what
         * its settings screen claims: `getString("pref_theme", "auto")` supplies the default at
         * the point of reading, so a fresh install with no key follows the system either way.
         */
        val DEFAULT_THEME = AppTheme.AUTO

        /**
         * Foresights, as `SurveyManager.getInputMode` defaults to.
         *
         * The one setting here that changes what a *number means* rather than how it looks, which
         * is why losing it is the worst of the group — see finding 49.
         */
        val DEFAULT_INPUT_MODE = InputMode.DEFAULT

        /** Pan, as `SketchTool.DEFAULT` is. */
        val DEFAULT_TOOL = SketchTool.DEFAULT

        /** Black, as `BrushColour.DEFAULT` is. */
        val DEFAULT_BRUSH_COLOUR = Colour.BLACK

        /**
         * The first symbol on the palette.
         *
         * `Symbol.DEFAULT` is `TEXT` in the Android app, which is a value of *its* symbol enum;
         * this port has no such value, because writing a label is a tool of its own here rather
         * than a symbol you stamp. So the default is the first thing on the palette instead.
         */
        val DEFAULT_SYMBOL = Symbol.ENTRANCE

        val DEFAULT = AppPreferences()
    }
}

/**
 * Remembering them between runs, in the same plain `key=value` shape as [SurveySettingsStore].
 *
 * ## The default is the one the Android settings screen shows, not the one it uses
 *
 * `preferences_general.xml` declares `android:defaultValue="true"` for
 * `pref_vibrate_on_new_station`, so the checkbox appears ticked. But nothing in the app calls
 * `PreferenceManager.setDefaultValues`, so on a fresh install the key is simply absent, and
 * `NewStationNotificationService` reads it with `getBoolean(key, false)` — which returns false.
 * The settings screen therefore says vibration is on while it is off, until somebody toggles it
 * twice. This port takes the screen at its word.
 */
object AppPreferencesStore {

    val PATH = listOf("preferences.txt")

    fun format(preferences: AppPreferences): String =
        buildString {
            appendLine("buzzOnNewStation=${preferences.buzzOnNewStation}")
            appendLine("hotCorners=${preferences.hotCorners}")
            appendLine("twoFingerMove=${preferences.twoFingerMove}")
            appendLine("autoRecentre=${preferences.autoRecentre}")
            appendLine("fadeNonActive=${preferences.fadeNonActive}")
            appendLine("highlightLatestLeg=${preferences.highlightLatestLeg}")
            appendLine("blueWater=${preferences.blueWater}")
            appendLine("showCrossSections=${preferences.showCrossSections}")
            appendLine("legacyCrossSections=${preferences.legacyCrossSections}")
            appendLine("pinchToZoom=${preferences.pinchToZoom}")
            appendLine("showSplays=${preferences.showSplays}")
            appendLine("showSketch=${preferences.showSketch}")
            appendLine("showStationLabels=${preferences.showStationLabels}")
            appendLine("showGrid=${preferences.showGrid}")
            appendLine("snapToLines=${preferences.snapToLines}")
            appendLine("showCompass=${preferences.showCompass}")
            appendLine("fullScreen=${preferences.fullScreen}")
            appendLine("theme=${preferences.theme.key}")
            appendLine("inputMode=${preferences.inputMode.name}")
            appendLine("tool=${preferences.tool.name}")
            appendLine("brushColour=${preferences.brushColour.name}")
            appendLine("symbol=${preferences.symbol.name}")
            appendLine("autoReconnect=${preferences.autoReconnect}")
            appendLine("autoReconnectWindowMinutes=${preferences.autoReconnectWindowMinutes}")
            appendLine("deletePathFragments=${preferences.deletePathFragments}")
            val style = preferences.sketchStyle
            appendLine("sketchLineWidthDp=${style.sketchLineWidthDp}")
            appendLine("legWidthDp=${style.legWidthDp}")
            appendLine("splayWidthDp=${style.splayWidthDp}")
            appendLine("stationDiameterDp=${style.stationDiameterDp}")
            appendLine("stationLabelSizeSp=${style.stationLabelSizeSp}")
            appendLine("legendSizeSp=${style.legendSizeSp}")
            appendLine("symbolSizeDp=${style.symbolSizeDp}")
            appendLine("textSizeSp=${style.textSizeSp}")
            appendLine("manualControls=${preferences.manualControls}")
            appendLine("lrudFields=${preferences.lrudFields}")
            appendLine("calibrationAlgorithm=${preferences.calibrationAlgorithm.key}")
            appendLine("lrudMode=${preferences.lrudMode.name}")
            appendLine("developerMode=${preferences.developerMode}")
            appendLine("azimuthInDms=${preferences.azimuthInDms}")
            appendLine("inclinationInDms=${preferences.inclinationInDms}")
            val svg = preferences.svgExport
            appendLine("svgWhiteBackground=${svg.whiteBackground}")
            appendLine("svgShowGrid=${svg.showGrid}")
            appendLine("svgShowSketch=${svg.showSketch}")
            appendLine("svgShowSymbols=${svg.showSymbols}")
            appendLine("svgShowCrossSections=${svg.showCrossSections}")
            appendLine("svgShowCentreline=${svg.showCentreline}")
            appendLine("svgShowSplays=${svg.showSplays}")
            appendLine("svgShowStations=${svg.showStations}")
            appendLine("svgShowLegend=${svg.showLegend}")
            appendLine("svgShowNorthArrow=${svg.showNorthArrow}")
            appendLine("svgShowScaleBar=${svg.showScaleBar}")
            appendLine("svgShowTeam=${svg.showTeam}")
            appendLine("svgShowCopyright=${svg.showCopyright}")
            appendLine("svgShowTagline=${svg.showTagline}")
            appendLine("svgSketchStrokeWidth=${svg.sketchStrokeWidth}")
            appendLine("svgLegStrokeWidth=${svg.legStrokeWidth}")
            appendLine("svgSplayStrokeWidth=${svg.splayStrokeWidth}")
            val therion = preferences.therionExport
            appendLine("therionPlanSuffix=${therion.planSuffix}")
            appendLine("therionElevationSuffix=${therion.elevationSuffix}")
            appendLine("therionXviFolder=${therion.xviFolder}")
            appendLine("therionPlanScrapSuffix=${therion.planScrapSuffix}")
            appendLine("therionElevationScrapSuffix=${therion.elevationScrapSuffix}")
            appendLine("therionPlanCrossSectionSuffix=${therion.planCrossSectionSuffix}")
            appendLine("therionElevationCrossSectionSuffix=${therion.elevationCrossSectionSuffix}")
            appendLine("therionCrossSections=${therion.crossSections}")
            appendLine("therionSymbols=${therion.symbols}")
            appendLine("therionLabels=${therion.labels}")
        }

    fun parse(text: String): AppPreferences {
        val values =
            text.lineSequence()
                .mapNotNull { line ->
                    val key = line.substringBefore('=', "").trim()
                    if (key.isEmpty() || '=' !in line) null else key to line.substringAfter('=').trim()
                }
                .toMap()

        return AppPreferences(
            // `toBooleanStrictOrNull` rather than `toBoolean`, which reads anything that is not
            // "true" as false - including a typo, and including a value a later version wrote.
            buzzOnNewStation =
                values["buzzOnNewStation"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_BUZZ_ON_NEW_STATION,
            hotCorners =
                values["hotCorners"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_HOT_CORNERS,
            twoFingerMove =
                values["twoFingerMove"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_TWO_FINGER_MOVE,
            autoRecentre =
                values["autoRecentre"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_AUTO_RECENTRE,
            fadeNonActive =
                values["fadeNonActive"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_FADE_NON_ACTIVE,
            highlightLatestLeg =
                values["highlightLatestLeg"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_HIGHLIGHT_LATEST_LEG,
            blueWater =
                values["blueWater"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_BLUE_WATER,
            showCrossSections =
                values["showCrossSections"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_SHOW_CROSS_SECTIONS,
            legacyCrossSections =
                values["legacyCrossSections"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_LEGACY_CROSS_SECTIONS,
            pinchToZoom =
                values["pinchToZoom"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_PINCH_TO_ZOOM,
            showSplays =
                values["showSplays"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_SHOW_SPLAYS,
            showSketch =
                values["showSketch"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_SHOW_SKETCH,
            showStationLabels =
                values["showStationLabels"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_SHOW_STATION_LABELS,
            showGrid =
                values["showGrid"]?.toBooleanStrictOrNull() ?: AppPreferences.DEFAULT_SHOW_GRID,
            snapToLines =
                values["snapToLines"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_SNAP_TO_LINES,
            showCompass =
                values["showCompass"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_SHOW_COMPASS,
            fullScreen =
                values["fullScreen"]?.toBooleanStrictOrNull()
                    ?: AppPreferences.DEFAULT_FULL_SCREEN,
            // Not an enum lookup that throws: this file is written by whatever version of the app
            // last ran, and a value a later one invented should leave the surveyor on the default
            // rather than on a screen that will not open.
            theme = AppTheme.of(values["theme"]) ?: AppPreferences.DEFAULT_THEME,
            // All four by name, and all four tolerant of a name this version does not know.
            // `SketchPreferences` reads its three through `valueOf`, which throws — so an Android
            // install that met a preferences file naming a tool it had dropped would crash on the
            // way into the sketch screen rather than fall back. Nothing here should be able to
            // stop the app opening a survey.
            inputMode =
                InputMode.entries.firstOrNull { it.name == values["inputMode"] }
                    ?: AppPreferences.DEFAULT_INPUT_MODE,
            tool = restorableTool(values["tool"]),
            brushColour =
                values["brushColour"]?.let(Colour::fromNameOrNull)
                    ?: AppPreferences.DEFAULT_BRUSH_COLOUR,
            symbol =
                Symbol.entries.firstOrNull { it.name == values["symbol"] }
                    ?: AppPreferences.DEFAULT_SYMBOL,
            autoReconnect =
                values["autoReconnect"]?.toBooleanStrictOrNull() ?: AutoReconnect.DEFAULT_ENABLED,
            // Coerced rather than merely parsed. A negative window is not a shorter one: it would
            // make every deadline already past, so the *first* failure would be retried and the
            // second would give up — which reads on screen as auto-reconnect being on and doing
            // almost nothing.
            autoReconnectWindowMinutes =
                values["autoReconnectWindowMinutes"]?.toIntOrNull()?.coerceIn(0, MAX_WINDOW_MINUTES)
                    ?: AutoReconnect.DEFAULT_WINDOW_MINUTES,
            deletePathFragments =
                values["deletePathFragments"]?.toBooleanStrictOrNull()
                    ?: SketchDefaults.DELETE_PATH_FRAGMENTS_DEFAULT,
            sketchStyle =
                SketchStyle(
                    sketchLineWidthDp =
                        SketchStyle.size(
                            values["sketchLineWidthDp"],
                            SketchStyle.DEFAULT_SKETCH_LINE_WIDTH_DP,
                        ),
                    legWidthDp =
                        SketchStyle.size(values["legWidthDp"], SketchStyle.DEFAULT_LEG_WIDTH_DP),
                    splayWidthDp =
                        SketchStyle.size(values["splayWidthDp"], SketchStyle.DEFAULT_SPLAY_WIDTH_DP),
                    stationDiameterDp =
                        SketchStyle.size(
                            values["stationDiameterDp"],
                            SketchStyle.DEFAULT_STATION_DIAMETER_DP,
                        ),
                    stationLabelSizeSp =
                        SketchStyle.size(
                            values["stationLabelSizeSp"],
                            SketchStyle.DEFAULT_STATION_LABEL_SIZE_SP,
                        ),
                    legendSizeSp =
                        SketchStyle.size(values["legendSizeSp"], SketchStyle.DEFAULT_LEGEND_SIZE_SP),
                    symbolSizeDp =
                        SketchStyle.size(values["symbolSizeDp"], SketchStyle.DEFAULT_SYMBOL_SIZE_DP),
                    textSizeSp =
                        SketchStyle.size(values["textSizeSp"], SketchStyle.DEFAULT_TEXT_SIZE_SP),
                ),
            manualControls = values["manualControls"]?.toBooleanStrictOrNull() ?: true,
            lrudFields = values["lrudFields"]?.toBooleanStrictOrNull() ?: false,
            // By name, and tolerant of a name this version does not know, for the same reason as
            // the theme and the tool: nothing in a preferences file written by another version
            // should be able to stop the app opening.
            calibrationAlgorithm =
                CalibrationChoice.of(values["calibrationAlgorithm"]) ?: CalibrationChoice.DEFAULT,
            lrudMode =
                LrudMode.entries.firstOrNull { it.name == values["lrudMode"] } ?: LrudMode.DEFAULT,
            developerMode = values["developerMode"]?.toBooleanStrictOrNull() ?: false,
            azimuthInDms = values["azimuthInDms"]?.toBooleanStrictOrNull() ?: false,
            inclinationInDms = values["inclinationInDms"]?.toBooleanStrictOrNull() ?: false,
            svgExport = svgExportFrom(values),
            therionExport = therionExportFrom(values),
        )
    }

    /**
     * The ten Therion export settings, each falling back to the exporter's own default.
     *
     * The suffixes are read *without* trimming and without rejecting anything, on purpose. An
     * empty suffix is a real choice - it means "no suffix" and the Android app takes it - and a
     * value this version does not expect is still a filename the surveyor typed. The one thing a
     * bad value here can do is name a file oddly, which is visible on the export screen before
     * anything is saved; refusing it would instead silently give them the default and a project
     * that does not match their other trips.
     */
    private fun therionExportFrom(values: Map<String, String>): TherionExport {
        val default = TherionExport.DEFAULT
        fun text(key: String, fallback: String) = values[key] ?: fallback
        fun flag(key: String, fallback: Boolean) = values[key]?.toBooleanStrictOrNull() ?: fallback
        return TherionExport(
            planSuffix = text("therionPlanSuffix", default.planSuffix),
            elevationSuffix = text("therionElevationSuffix", default.elevationSuffix),
            xviFolder = text("therionXviFolder", default.xviFolder),
            planScrapSuffix = text("therionPlanScrapSuffix", default.planScrapSuffix),
            elevationScrapSuffix =
                text("therionElevationScrapSuffix", default.elevationScrapSuffix),
            planCrossSectionSuffix =
                text("therionPlanCrossSectionSuffix", default.planCrossSectionSuffix),
            elevationCrossSectionSuffix =
                text("therionElevationCrossSectionSuffix", default.elevationCrossSectionSuffix),
            crossSections = flag("therionCrossSections", default.crossSections),
            symbols = flag("therionSymbols", default.symbols),
            labels = flag("therionLabels", default.labels),
        )
    }

    /**
     * The seventeen SVG export settings, each falling back to the exporter's own default.
     *
     * Read through [SvgExporter.Options.DEFAULT] rather than through literals so that the
     * exporter stays the one place that says what a default export looks like: a new option added
     * there arrives here already right, and a changed default cannot end up meaning two things.
     */
    private fun svgExportFrom(values: Map<String, String>): SvgExporter.Options {
        val default = SvgExporter.Options.DEFAULT
        fun flag(key: String, fallback: Boolean) = values[key]?.toBooleanStrictOrNull() ?: fallback
        return SvgExporter.Options(
            whiteBackground = flag("svgWhiteBackground", default.whiteBackground),
            showGrid = flag("svgShowGrid", default.showGrid),
            showSketch = flag("svgShowSketch", default.showSketch),
            showSymbols = flag("svgShowSymbols", default.showSymbols),
            showCrossSections = flag("svgShowCrossSections", default.showCrossSections),
            showCentreline = flag("svgShowCentreline", default.showCentreline),
            showSplays = flag("svgShowSplays", default.showSplays),
            showStations = flag("svgShowStations", default.showStations),
            showLegend = flag("svgShowLegend", default.showLegend),
            showNorthArrow = flag("svgShowNorthArrow", default.showNorthArrow),
            showScaleBar = flag("svgShowScaleBar", default.showScaleBar),
            showTeam = flag("svgShowTeam", default.showTeam),
            showCopyright = flag("svgShowCopyright", default.showCopyright),
            showTagline = flag("svgShowTagline", default.showTagline),
            sketchStrokeWidth = strokeWidth(values["svgSketchStrokeWidth"], default.sketchStrokeWidth),
            legStrokeWidth = strokeWidth(values["svgLegStrokeWidth"], default.legStrokeWidth),
            splayStrokeWidth = strokeWidth(values["svgSplayStrokeWidth"], default.splayStrokeWidth),
        )
    }

    /**
     * A stroke width as typed, clamped rather than merely parsed.
     *
     * Zero is not a thinner line, it is an invisible one, and an SVG whose centreline is drawn at
     * width 0 looks to the surveyor exactly like an export that lost the survey. The Android app
     * accepts it - `getInt` parses whatever the `EditTextPreference` stored - so this is a
     * departure, and a deliberate one: nothing typed into a text box should be able to produce a
     * blank drawing that the app then reports as saved.
     */
    internal fun strokeWidth(typed: String?, fallback: Int): Int =
        typed?.trim()?.toIntOrNull()?.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH) ?: fallback

    /** Thin, but drawn. See [strokeWidth]. */
    internal const val MIN_STROKE_WIDTH = 1

    /** At [SvgExporter.SCALE] pixels to the metre, a line a metre wide. Nobody wants more. */
    internal const val MAX_STROKE_WIDTH = 50

    /**
     * A tool worth reopening the app on — which is not every tool.
     *
     * Five of them are entered for the duration of a gesture or of one tap: a pinch, a hot-corner
     * pan, the three cross-section drags. Saving those is meaningless, and restoring one is worse
     * than meaningless — an app that opens with *the next touch drops a cross-section* armed is a
     * cross-section dropped by the surveyor's first touch. So only the toolbar's own tools come
     * back, and anything else reads as the default.
     *
     * The Android app has the same hole and does not notice it, because `setSelectedSketchTool` is
     * only reached from its toolbar handler; this port's [DemoState] sets the tool directly for
     * the cross-section gestures, so the rule has to be written down rather than assumed.
     */
    internal fun restorableTool(name: String?): SketchTool {
        val tool = SketchTool.entries.firstOrNull { it.name == name } ?: return AppPreferences.DEFAULT_TOOL
        return if (tool in RESTORABLE_TOOLS) tool else AppPreferences.DEFAULT_TOOL
    }

    /**
     * A day. Not a limit the Android app has, and not one a surveyor will meet — it is here so
     * that a typo in a text field cannot ask the radio to keep trying for a hundred years.
     */
    internal const val MAX_WINDOW_MINUTES = 24 * 60

    /** The six the toolbar lights, plus the symbol stamp the palette arms. */
    internal val RESTORABLE_TOOLS =
        setOf(
            SketchTool.MOVE,
            SketchTool.DRAW,
            SketchTool.ERASE,
            SketchTool.TEXT,
            SketchTool.SELECT,
            SketchTool.SYMBOL,
        )

    /** Never throws: browser storage can be disabled, and a missing file means the defaults. */
    fun load(store: FileStore): AppPreferences =
        runCatching { store.readText(PATH)?.let(::parse) }.getOrNull() ?: AppPreferences.DEFAULT

    fun save(store: FileStore, preferences: AppPreferences): Boolean =
        runCatching { store.writeText(PATH, format(preferences)) }.isSuccess
}
