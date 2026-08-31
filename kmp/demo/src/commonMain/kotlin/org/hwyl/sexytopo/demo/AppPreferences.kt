package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.sketch.SketchDefaults
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.InputMode

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
) {
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
        )
    }

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
