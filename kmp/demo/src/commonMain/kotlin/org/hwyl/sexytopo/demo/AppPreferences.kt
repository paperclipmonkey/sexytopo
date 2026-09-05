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
 * Light, dark, or whatever the phone says.
 *
 * A cave is dark and a phone screen is the brightest thing in it: full brightness costs a
 * surveyor their night vision and the battery they need to get out.
 */
enum class AppTheme(
    val key: String,
    val label: String,
) {
    AUTO("auto", "Automatic"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

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

/** The settings that are about the app rather than about surveying. */
data class AppPreferences(
    val buzzOnNewStation: Boolean = DEFAULT_BUZZ_ON_NEW_STATION,
    val hotCorners: Boolean = DEFAULT_HOT_CORNERS,
    val twoFingerMove: Boolean = DEFAULT_TWO_FINGER_MOVE,
    val autoRecentre: Boolean = DEFAULT_AUTO_RECENTRE,
    /** Draw everything but the working end at a fifth alpha. */
    val fadeNonActive: Boolean = DEFAULT_FADE_NON_ACTIVE,
    val highlightLatestLeg: Boolean = DEFAULT_HIGHLIGHT_LATEST_LEG,
    val blueWater: Boolean = DEFAULT_BLUE_WATER,
    val showCrossSections: Boolean = DEFAULT_SHOW_CROSS_SECTIONS,
    val legacyCrossSections: Boolean = DEFAULT_LEGACY_CROSS_SECTIONS,
    val pinchToZoom: Boolean = DEFAULT_PINCH_TO_ZOOM,
    val showSplays: Boolean = DEFAULT_SHOW_SPLAYS,
    val showSketch: Boolean = DEFAULT_SHOW_SKETCH,
    val showStationLabels: Boolean = DEFAULT_SHOW_STATION_LABELS,
    val showGrid: Boolean = DEFAULT_SHOW_GRID,
    val snapToLines: Boolean = DEFAULT_SNAP_TO_LINES,
    val showCompass: Boolean = DEFAULT_SHOW_COMPASS,
    val fullScreen: Boolean = DEFAULT_FULL_SCREEN,
    val theme: AppTheme = DEFAULT_THEME,
    val inputMode: InputMode = DEFAULT_INPUT_MODE,
    val tool: SketchTool = DEFAULT_TOOL,
    val brushColour: Colour = DEFAULT_BRUSH_COLOUR,
    val symbol: Symbol = DEFAULT_SYMBOL,
    val autoReconnect: Boolean = AutoReconnect.DEFAULT_ENABLED,
    /** For how long, from the first failure of a run. */
    val autoReconnectWindowMinutes: Int = AutoReconnect.DEFAULT_WINDOW_MINUTES,
    /**
     * The [org.hwyl.sexytopo.shared.comms.InstrumentProfile] name last connected to, or null.
     *
     * Kept so opening the app puts the surveyor back on the instrument they were using rather than
     * on a chooser. There is nothing sensitive in it — it is the family of device, "BRIC4", not a
     * serial number or an address.
     */
    val lastInstrument: String? = null,
    val deletePathFragments: Boolean = SketchDefaults.DELETE_PATH_FRAGMENTS_DEFAULT,
    val sketchStyle: SketchStyle = SketchStyle.DEFAULT,
    /** `pref_manual_controls`: the *Add reading* button on the field bar. */
    val manualControls: Boolean = true,
    /** `pref_lrud_fields`, off upstream: the four passage measurements beside the reading. */
    val lrudFields: Boolean = false,
    val azimuthInDms: Boolean = false,
    val inclinationInDms: Boolean = false,
    val svgExport: SvgExporter.Options = SvgExporter.Options.DEFAULT,
    val therionExport: TherionExport = TherionExport.DEFAULT,
    val calibrationAlgorithm: CalibrationChoice = CalibrationChoice.DEFAULT,
    /** `pref_developer_mode`. Upstream declares this key but never reads it. */
    val developerMode: Boolean = false,
    /**
     * `pref_lrud_direction`. [LrudMode.SURVEY] bisects the corner at a bend; [LrudMode.SHOT]
     * takes it square to the outgoing leg alone.
     */
    val lrudMode: LrudMode = LrudMode.DEFAULT,
) {
    val angleEntry: AngleEntry
        get() = AngleEntry(azimuthInDms, inclinationInDms)

    val reconnection: AutoReconnect
        get() = AutoReconnect(autoReconnect, autoReconnectWindowMinutes)

    companion object {
        /** What the settings screen shows — see the note in [AppPreferencesStore]. */
        const val DEFAULT_BUZZ_ON_NEW_STATION = true

        const val DEFAULT_HOT_CORNERS = true

        /** Pinch-to-zoom is not gated on this — that is always live. */
        const val DEFAULT_TWO_FINGER_MOVE = false

        /** Without this the port re-fits the whole cave as the survey grows. */
        const val DEFAULT_AUTO_RECENTRE = false

        const val DEFAULT_FADE_NON_ACTIVE = false

        const val DEFAULT_HIGHLIGHT_LATEST_LEG = true

        const val DEFAULT_BLUE_WATER = true

        /** Turning it off hides the sections *and* stops them being touchable. */
        const val DEFAULT_SHOW_CROSS_SECTIONS = true

        const val DEFAULT_LEGACY_CROSS_SECTIONS = false

        const val DEFAULT_PINCH_TO_ZOOM = true

        /**
         * These five were session-only in this port until they were found, reached through
         * [DemoState] rather than through this class, which is why the gap went unnoticed.
         */
        const val DEFAULT_SHOW_SPLAYS = true

        const val DEFAULT_SHOW_SKETCH = true

        const val DEFAULT_SHOW_STATION_LABELS = true

        const val DEFAULT_SHOW_GRID = true

        const val DEFAULT_SNAP_TO_LINES = SketchDefaults.SNAP_TO_LINES_DEFAULT

        /** The arrow doesn't swing with the phone — this port has no magnetometer. */
        const val DEFAULT_SHOW_COMPASS = true

        const val DEFAULT_FULL_SCREEN = false

        val DEFAULT_THEME = AppTheme.AUTO

        /** The one setting here that changes what a number *means* rather than how it looks. */
        val DEFAULT_INPUT_MODE = InputMode.DEFAULT

        val DEFAULT_TOOL = SketchTool.DEFAULT

        val DEFAULT_BRUSH_COLOUR = Colour.BLACK

        /** `Symbol.DEFAULT` is `TEXT` in the Android app; this port has no such symbol value. */
        val DEFAULT_SYMBOL = Symbol.ENTRANCE

        val DEFAULT = AppPreferences()
    }
}

/**
 * Remembering them between runs, in the same plain `key=value` shape as [SurveySettingsStore].
 *
 * ## The default is the one the Android settings screen shows, not the one it uses
 *
 * `preferences_general.xml` declares `pref_vibrate_on_new_station` true, but nothing calls
 * `PreferenceManager.setDefaultValues`, so on a fresh install the key is absent and
 * `NewStationNotificationService` reads it with `getBoolean(key, false)`. This port takes the
 * screen at its word.
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
            // Only when there is one: an empty value would read back as the empty string and
            // match no profile, which is the same as absent but harder to explain in a file.
            preferences.lastInstrument?.let { appendLine("lastInstrument=$it") }
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
            appendLine("therionPlanScraps=${therion.planScrapCount}")
            appendLine("therionElevationScraps=${therion.elevationScrapCount}")
            appendLine("therionStationsInPlanScrap=${therion.stationsInFirstPlanScrap}")
            appendLine(
                "therionStationsInElevationScrap=${therion.stationsInFirstElevationScrap}",
            )
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
            theme = AppTheme.of(values["theme"]) ?: AppPreferences.DEFAULT_THEME,
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
            // Coerced, not just parsed: a negative window would make every deadline already
            // past, so auto-reconnect would appear on but do almost nothing.
            autoReconnectWindowMinutes =
                values["autoReconnectWindowMinutes"]?.toIntOrNull()?.coerceIn(0, MAX_WINDOW_MINUTES)
                    ?: AutoReconnect.DEFAULT_WINDOW_MINUTES,
            lastInstrument = values["lastInstrument"]?.trim()?.takeIf { it.isNotEmpty() },
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
     * The ten Therion export settings and the four from its export dialog, each falling back to
     * the exporter's own default. Suffixes are read without trimming or rejecting anything: an
     * empty suffix is a real choice, not an error.
     */
    private fun therionExportFrom(values: Map<String, String>): TherionExport {
        val default = TherionExport.DEFAULT
        fun text(key: String, fallback: String) = values[key] ?: fallback
        fun flag(key: String, fallback: Boolean) = values[key]?.toBooleanStrictOrNull() ?: fallback
        fun count(key: String, fallback: Int) =
            (values[key]?.trim()?.toIntOrNull() ?: fallback).coerceAtLeast(1)
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
            // Clamped to at least one: zero scraps means a `.th2` with no drawing in it.
            planScrapCount = count("therionPlanScraps", default.planScrapCount),
            elevationScrapCount = count("therionElevationScraps", default.elevationScrapCount),
            stationsInFirstPlanScrap =
                flag("therionStationsInPlanScrap", default.stationsInFirstPlanScrap),
            stationsInFirstElevationScrap =
                flag(
                    "therionStationsInElevationScrap",
                    default.stationsInFirstElevationScrap,
                ),
        )
    }

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
     * Zero is not a thinner line, it is an invisible one. The Android app accepts it; this is a
     * deliberate departure.
     */
    internal fun strokeWidth(typed: String?, fallback: Int): Int =
        typed?.trim()?.toIntOrNull()?.coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH) ?: fallback

    /** Thin, but drawn. See [strokeWidth]. */
    internal const val MIN_STROKE_WIDTH = 1

    /** At [SvgExporter.SCALE] pixels to the metre, a line a metre wide. Nobody wants more. */
    internal const val MAX_STROKE_WIDTH = 50

    /**
     * A tool worth reopening the app on — which is not every tool. Five are entered only for the
     * duration of a gesture (a pinch, a hot-corner pan, the three cross-section drags), and
     * restoring one of those would arm the next touch to drop a cross-section. Only the
     * toolbar's own tools come back.
     */
    internal fun restorableTool(name: String?): SketchTool {
        val tool = SketchTool.entries.firstOrNull { it.name == name } ?: return AppPreferences.DEFAULT_TOOL
        return if (tool in RESTORABLE_TOOLS) tool else AppPreferences.DEFAULT_TOOL
    }

    /** A day: enough that a typo can't ask the radio to keep trying for a hundred years. */
    internal const val MAX_WINDOW_MINUTES = 24 * 60

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
