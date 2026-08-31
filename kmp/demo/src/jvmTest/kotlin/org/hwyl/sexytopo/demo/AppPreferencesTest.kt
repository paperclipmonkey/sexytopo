package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.AutoReconnect
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's own settings, and the one thing they currently control.
 */
class AppPreferencesTest {

    @Test
    fun theDefaultIsWhatTheAndroidSettingsScreenShows() {
        // `preferences_general.xml` declares defaultValue="true"; nothing calls setDefaultValues,
        // so the service's own getBoolean(key, false) wins on a fresh install and the screen and
        // the behaviour disagree. This port takes the screen at its word.
        assertTrue(AppPreferences.DEFAULT.buzzOnNewStation)
    }

    @Test
    fun aPreferenceSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(buzzOnNewStation = false))

        assertFalse(AppPreferencesStore.load(store).buzzOnNewStation)
    }

    /**
     * The three sketch-movement preferences keep the Android app's own defaults, which are not all
     * the same: the corners are on, a two-fingered drag is off, and following the survey is off.
     */
    @Test
    fun theSketchMovementDefaultsAreTheAndroidApps() {
        assertTrue(AppPreferences.DEFAULT.hotCorners, "pref_hot_corners defaults to true")
        assertFalse(
            AppPreferences.DEFAULT.twoFingerMove,
            "pref_two_finger_movement defaults to false",
        )
        assertFalse(AppPreferences.DEFAULT.autoRecentre, "AUTO_RECENTRE defaults to false")
    }

    @Test
    fun everyPreferenceSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        // Each one flipped away from its default, so a value that failed to round-trip and fell
        // back to the default would be a failure rather than an accident.
        val flipped =
            AppPreferences(
                buzzOnNewStation = false,
                hotCorners = false,
                twoFingerMove = true,
                autoRecentre = true,
                fadeNonActive = true,
                highlightLatestLeg = false,
                blueWater = false,
                showCrossSections = false,
                pinchToZoom = false,
                showSplays = false,
                showSketch = false,
                showStationLabels = false,
                showGrid = false,
                snapToLines = true,
                showCompass = false,
                fullScreen = true,
                theme = AppTheme.DARK,
            )
        AppPreferencesStore.save(store, flipped)

        assertEquals(flipped, AppPreferencesStore.load(store))
    }

    /**
     * The settings screen shows three preferences and the drawing menu sets a fourth. Building the
     * saved value from the three on screen resets the fourth to its default with nothing to say so
     * — which is what happened: turning *Follow the survey* on and then adjusting a tolerance
     * turned it off again.
     */
    @Test
    fun savingTheSettingsScreenLeavesThePreferencesItDoesNotShowAlone() {
        val current = AppPreferences(autoRecentre = true)
        val saved =
            preferencesFrom(
                current,
                buzzOnNewStation = false,
                hotCorners = false,
                twoFingerMove = true,
                autoReconnect = false,
                autoReconnectWindow = "15",
                azimuthInDms = false,
                inclinationInDms = false,
                manualControls = true,
            )

        assertTrue(saved.autoRecentre, "the drawing menu's preference is not this screen's to reset")
        assertFalse(saved.buzzOnNewStation)
        assertFalse(saved.hotCorners)
        assertTrue(saved.twoFingerMove)
    }

    /**
     * And a half-typed window keeps the value that was there rather than resetting it.
     *
     * The field is a text box, so it passes through every prefix of a number on the way to one.
     * Falling back to the *default* rather than to the current value would quietly overwrite a
     * surveyor's forty minutes with fifteen the moment they cleared the box to retype it.
     */
    @Test
    fun aHalfTypedReconnectWindowKeepsTheValueItHad() {
        val current = AppPreferences(autoReconnect = true, autoReconnectWindowMinutes = 40)
        val saved =
            preferencesFrom(
                current,
                buzzOnNewStation = true,
                hotCorners = true,
                twoFingerMove = false,
                autoReconnect = true,
                autoReconnectWindow = "",
                azimuthInDms = false,
                inclinationInDms = false,
                manualControls = true,
            )

        assertEquals(40, saved.autoReconnectWindowMinutes)
    }

    /** Both reconnect settings round-trip, and the window is clamped rather than trusted. */
    @Test
    fun theReconnectSettingsSurviveTheAppBeingClosed() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(
            store,
            AppPreferences(autoReconnect = true, autoReconnectWindowMinutes = 40),
        )

        val reopened = AppPreferencesStore.load(store)
        assertTrue(reopened.autoReconnect)
        assertEquals(40, reopened.autoReconnectWindowMinutes)
        assertEquals(AutoReconnect(true, 40), reopened.reconnection)

        // A negative window is not a shorter one: every deadline would already be past, so the
        // first failure would be retried and the second would give up — which reads on screen as
        // the setting being on and doing next to nothing.
        assertEquals(
            0,
            AppPreferencesStore.parse("autoReconnectWindowMinutes=-5").autoReconnectWindowMinutes,
        )
        assertEquals(
            AppPreferencesStore.MAX_WINDOW_MINUTES,
            AppPreferencesStore
                .parse("autoReconnectWindowMinutes=99999999")
                .autoReconnectWindowMinutes,
        )
    }

    /**
     * The two angle-entry preferences round-trip, and are off by default.
     *
     * `pref_deg_mins_secs` and `pref_inc_deg_mins_secs`, both false upstream: a DistoX reports a
     * decimal and most surveys are shot with one. They are two switches rather than one, as they
     * are there, because plenty of clinometers read in degrees while the compass beside them
     * reads in minutes.
     */
    @Test
    fun typingAnglesInMinutesIsAChoiceThatSurvivesTheAppBeingClosed() {
        assertFalse(AppPreferences.DEFAULT.azimuthInDms)
        assertFalse(AppPreferences.DEFAULT.inclinationInDms)

        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(azimuthInDms = true))

        val reopened = AppPreferencesStore.load(store)
        assertTrue(reopened.azimuthInDms)
        assertFalse(reopened.inclinationInDms, "one switch does not turn the other on")
        assertEquals(AngleEntry(azimuthInDms = true), reopened.angleEntry)
    }

    /** Off, as `pref_auto_reconnect` is: chasing a radio is the surveyor's decision to make. */
    @Test
    fun chasingALostInstrumentIsOffUntilAskedFor() {
        assertFalse(AppPreferences.DEFAULT.autoReconnect)
        assertEquals(15, AppPreferences.DEFAULT.autoReconnectWindowMinutes)
    }

    @Test
    fun anAbsentFileMeansTheDefaults() {
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.load(InMemoryFileStore()))
    }

    /** A corrupt preferences file must not stop the app opening. */
    @Test
    fun rubbishReadsAsTheDefaults() {
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.parse("!!! not settings !!!"))
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.parse(""))
        // Not "true" and not "false": a typo, or something a later version wrote. `toBoolean`
        // would read this as false, which silently turns a setting off.
        assertEquals(AppPreferences.DEFAULT, AppPreferencesStore.parse("buzzOnNewStation=yes"))
    }

    @Test
    fun aFileFromALaterVersionStillLoads() {
        val loaded = AppPreferencesStore.parse("buzzOnNewStation=false\nsomethingNew=42\n")
        assertFalse(loaded.buzzOnNewStation)
    }

    /**
     * The buzz has to happen when the *station* is made, not on every reading — a buzz per shot
     * would be three per leg and would stop meaning anything.
     */
    @Test
    fun theCallbackFiresOnceAStationIsMadeAndNotBefore() {
        val survey = Survey("Test")
        val session = SurveySession(survey)
        var buzzes = 0
        session.onStationCreated = { buzzes++ }

        // The simulator shoots each leg three times, as a surveyor does; only the third promotes.
        session.takeReading()
        assertEquals(0, buzzes, "a buzz on the first reading of a leg")
        session.takeReading()
        assertEquals(0, buzzes, "a buzz on the second reading of a leg")
        session.takeReading()

        assertEquals(1, buzzes, "expected one buzz for one station, not one per reading")
        assertEquals(2, survey.getAllStations().size)
    }

    // -------------------------------------------------------------------------------------
    // The last two toggles on the drawing menu
    // -------------------------------------------------------------------------------------

    @Test
    fun theTwoRemainingDrawingMenuDefaultsAreTheAndroidApps() {
        val defaults = AppPreferences.DEFAULT

        assertTrue(defaults.showCrossSections, "SketchPreferences.Toggle.SHOW_X_SECTIONS is on")
        assertTrue(defaults.pinchToZoom, "SketchPreferences.Toggle.PINCH_TO_ZOOM is on")
    }

    @Test
    fun hidingCrossSectionsAlsoStopsThemBeingTapped() {
        // The Java's own "special case: can't tap on invisible X-sections". Both ways of making
        // them invisible count: hiding the whole sketch, and hiding the sections.
        assertTrue(DisplayOptions().crossSectionsAreTouchable, "both on by default")
        assertFalse(DisplayOptions(showCrossSections = false).crossSectionsAreTouchable)
        assertFalse(DisplayOptions(showSketch = false).crossSectionsAreTouchable)
    }

    // -------------------------------------------------------------------------------------
    // The six that used not to be preferences at all
    // -------------------------------------------------------------------------------------

    /**
     * Five of these were `mutableStateOf` on [DemoState] until the drawing menu was split, so a
     * surveyor who turned the splays off got them back on the next run. All five are persisted
     * `SketchPreferences.Toggle`s in the Android app, and the values here are that enum's.
     */
    /**
     * Off, because a first run that hides its own menu is a first run nobody gets out of — and
     * because that is what `GeneralPreferences.isImmersiveModeOn` defaults to.
     */
    @Test
    fun fullScreenIsOffUntilSomebodyAsksForIt() {
        assertFalse(AppPreferences.DEFAULT.fullScreen)
    }

    @Test
    fun theSketchToggleDefaultsAreTheAndroidApps() {
        val defaults = AppPreferences.DEFAULT

        assertTrue(defaults.showSplays, "SHOW_SPLAYS is on")
        assertTrue(defaults.showSketch, "SHOW_SKETCH is on")
        assertTrue(defaults.showStationLabels, "SHOW_STATION_LABELS is on")
        assertTrue(defaults.showGrid, "SHOW_GRID is on")
        assertFalse(defaults.snapToLines, "SNAP_TO_LINES is off")
        assertTrue(defaults.showCompass, "SHOW_COMPASS is on")
    }

    /**
     * The point of the move, asserted rather than assumed: turning one off writes a file.
     *
     * A round trip through the store rather than a check that the field changed, because the field
     * always changed — what did not happen was the save, and only the store can tell them apart.
     */
    @Test
    fun aSketchToggleReachesTheFile() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(showGrid = false, snapToLines = true))

        val reopened = AppPreferencesStore.load(store)
        assertFalse(reopened.showGrid, "the grid came back on when the app reopened")
        assertTrue(reopened.snapToLines, "snapping went off again when the app reopened")
    }

    /**
     * The two menu groups are `drawing.xml`'s own, and between them they are every toggle.
     *
     * Worth a test because the menu is now assembled from two lists in a different file from the
     * one that draws it: an item added to neither would simply not appear, and nothing else here
     * would notice.
     */
    @Test
    fun theDrawingMenuOffersEveryToggleOnce() {
        val labels = (DISPLAY_TOGGLES + BEHAVIOUR_TOGGLES).map { it.label }

        assertEquals(labels.size, labels.toSet().size, "a toggle is listed twice")
        assertEquals(4, BEHAVIOUR_TOGGLES.size, "drawing.xml's behaviour group has four items")
        // Seven of drawing.xml's display group — all but `buttonShowConnections`, which has no
        // cross-survey links behind it here — plus the latest-leg mark, which the Android app
        // keeps on its general settings screen rather than this menu.
        assertEquals(8, DISPLAY_TOGGLES.size)
    }

    // -------------------------------------------------------------------------------------
    // The theme, which was a session-only toggle
    // -------------------------------------------------------------------------------------

    /**
     * Dark mode was a `var` on [DemoState] flipped straight from the menu, so it came back light
     * every time the app was reopened. In a cave that is not cosmetic: the phone is the brightest
     * thing down there, the OS kills a backgrounded app while it is in a pocket between stations,
     * and the surveyor gets a full-brightness white page in the face at the next one.
     */
    @Test
    fun theThemeSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(theme = AppTheme.DARK))

        assertEquals(AppTheme.DARK, AppPreferencesStore.load(store).theme)
    }

    /** `GeneralPreferences.getTheme` reads `pref_theme` with `"auto"` as its fallback. */
    @Test
    fun theDefaultIsToFollowThePhone() {
        assertEquals(AppTheme.AUTO, AppPreferences.DEFAULT.theme)
    }

    /**
     * The three values are `settings_theme_values`, spelt the same way, because this file is a
     * survey folder that an Android install may also read.
     */
    @Test
    fun theStoredValuesAreTheAndroidApps() {
        assertEquals(listOf("auto", "light", "dark"), AppTheme.entries.map { it.key })
        assertTrue("theme=dark" in AppPreferencesStore.format(AppPreferences(theme = AppTheme.DARK)))
    }

    /**
     * Automatic follows the platform; the other two overrule it.
     *
     * Which is the reason a checkbox would not have done. Auto on a phone answers "is it evening",
     * and a cave is dark at noon — so a surveyor has to be able to say *dark* at eleven in the
     * morning and have the app believe them.
     */
    @Test
    fun automaticFollowsThePhoneAndTheOthersDoNot() {
        assertTrue(AppTheme.AUTO.isDark(systemDark = true))
        assertFalse(AppTheme.AUTO.isDark(systemDark = false))

        assertTrue(AppTheme.DARK.isDark(systemDark = false), "dark means dark in daylight too")
        assertFalse(AppTheme.LIGHT.isDark(systemDark = true), "light means light at night too")
    }

    // -------------------------------------------------------------------------------------
    // The four selections, which were the same defect in four more places
    // -------------------------------------------------------------------------------------

    /**
     * Close the app and open it again, for real, over one in-memory store.
     *
     * Not a check that a file was written: that is the half that was never in doubt. A second
     * [DemoState] over the same store is the reading half as well, which is what a surveyor
     * actually does when the OS kills the app in their pocket.
     */
    private fun reopen(library: SurveyLibrary): DemoState =
        DemoState(
            exampleSurvey = ExampleSurvey.create(),
            initialProjection = Projection2D.PLAN,
            initialSystemDark = false,
            initialTool = null,
            initialMode = SurveyMode.EXAMPLE,
            initialScreen = Screen.SKETCH,
            library = library,
        ).also { it.loadSettings() }

    /**
     * The serious one. `SurveyManager.getInputMode` reads `inputMode` out of `generalPrefs` on the
     * Android app's way in; this port held it in a `var` that started at FORWARD every run.
     *
     * A surveyor working back down a passage on backsights, whose phone is killed in a pocket
     * between stations, came back to foresights — and the field bar only says anything when the
     * mode is *not* FORWARD, so the state it came back in is the one that looks normal. Every leg
     * after that is turned end for end, and there is nothing in the numbers to show it happened.
     */
    @Test
    fun theInputModeSurvivesTheAppBeingClosed() {
        val library = SurveyLibrary(InMemoryFileStore())
        reopen(library).chooseInputMode(InputMode.BACKWARD)

        assertEquals(InputMode.BACKWARD, reopen(library).inputMode)
    }

    /** `pref_sketch_brush_colour` and `pref_sketch_symbol`, which `SketchPreferences` stores. */
    @Test
    fun theBrushAndTheSymbolSurviveTheAppBeingClosed() {
        val library = SurveyLibrary(InMemoryFileStore())
        val state = reopen(library)
        state.pickColour(Colour.BLUE, SketchEditor())
        state.chooseSymbol(Symbol.WATER_FLOW)

        val reopened = reopen(library)
        assertEquals(Colour.BLUE, reopened.brushColour)
        assertEquals(Symbol.WATER_FLOW, reopened.symbol)
    }

    /** `pref_sketch_sketch_tool`: the app opens on the tool the surveyor was using. */
    @Test
    fun theToolSurvivesTheAppBeingClosed() {
        val library = SurveyLibrary(InMemoryFileStore())
        reopen(library).chooseTool(SketchTool.ERASE)

        assertEquals(SketchTool.ERASE, reopen(library).tool)
    }

    /**
     * But not a tool that was armed for one touch.
     *
     * Five of the eleven are entered for the duration of a gesture or of a single tap — a pinch, a
     * hot-corner pan, the three cross-section drags. An app that opened with *the next touch drops
     * a cross-section* still armed would drop one under the surveyor's first touch.
     */
    @Test
    fun aToolArmedForOneTouchIsNotRestored() {
        val library = SurveyLibrary(InMemoryFileStore())
        reopen(library).chooseTool(SketchTool.POSITION_CROSS_SECTION)

        assertEquals(SketchTool.MOVE, reopen(library).tool)
    }

    /** Every tool the toolbar lights comes back; nothing else does. */
    @Test
    fun theToolsThatComeBackAreTheOnesOnTheToolbar() {
        for (tool in SketchTool.entries) {
            val restored = AppPreferencesStore.restorableTool(tool.name)
            if (tool in AppPreferencesStore.RESTORABLE_TOOLS) {
                assertEquals(tool, restored, "$tool is on the toolbar and should come back")
            } else {
                assertEquals(SketchTool.MOVE, restored, "$tool is a one-shot and should not")
            }
        }
    }

    /**
     * A name this version does not know reads as the default rather than throwing.
     *
     * `SketchPreferences` reads its three through `valueOf`, so an Android install meeting a file
     * that names a tool it has dropped would throw on the way into the sketch screen. Nothing
     * about a preference should be able to stop the app opening a survey.
     */
    @Test
    fun aSelectionNameThisVersionDoesNotKnowReadsAsTheDefault() {
        val parsed =
            AppPreferencesStore.parse(
                "inputMode=SIDEWAYS\ntool=TELEPORT\nbrushColour=CHARTREUSEISH\nsymbol=DRAGON\n",
            )

        assertEquals(InputMode.FORWARD, parsed.inputMode)
        assertEquals(SketchTool.MOVE, parsed.tool)
        assertEquals(Colour.BLACK, parsed.brushColour)
        assertEquals(Symbol.ENTRANCE, parsed.symbol)
    }

    /**
     * And a tool the caller named beats the saved one.
     *
     * The headless renderer photographs the drawing tool by asking for it. Without this, the
     * screenshots in this repository would show whichever tool the machine that built them
     * happened to have saved.
     */
    @Test
    fun aToolTheCallerNamedIsNotOverwrittenByTheSavedOne() {
        val library = SurveyLibrary(InMemoryFileStore())
        reopen(library).chooseTool(SketchTool.ERASE)

        val asked =
            DemoState(
                exampleSurvey = ExampleSurvey.create(),
                initialProjection = Projection2D.PLAN,
                initialSystemDark = false,
                initialTool = SketchTool.DRAW,
                initialMode = SurveyMode.EXAMPLE,
                initialScreen = Screen.SKETCH,
                library = library,
            )
        asked.loadSettings()

        assertEquals(SketchTool.DRAW, asked.tool)
    }

    /**
     * And it still happens once the surveyor is working on their own survey.
     *
     * The callback was wired up in `loadSettings`, which runs once when the app opens. Every route
     * to a real survey — new, open, import, delete-the-open-one — goes through `adopt`, which
     * builds a *fresh* `SurveySession`, and a fresh session has no callback. So the buzz and the
     * station counter both worked on the demo cave and stopped the moment the surveyor made a
     * survey of their own, which is every real use of the app. See finding 50.
     */
    @Test
    fun theStationCounterSurvivesOpeningASurvey() {
        val state = reopen(SurveyLibrary(InMemoryFileStore()))
        state.newSurvey("Swildons")

        repeat(3) { state.session.takeReading() }

        assertEquals(
            1,
            state.stationsCreated,
            "a station was made and nothing on the app noticed",
        )
    }

    /** A theme a later version invented leaves the surveyor on the default, not on no screen. */
    @Test
    fun anUnknownThemeReadsAsTheDefault() {
        assertEquals(AppTheme.AUTO, AppPreferencesStore.parse("theme=solarized").theme)
        assertEquals(AppTheme.AUTO, AppPreferencesStore.parse("theme=").theme)
    }

    /**
     * The menu row for the theme is a submenu, and *< Back* from it goes to Settings.
     *
     * A one-line rule with a test on it because the previous rule — always back to the top — was
     * right until this page existed, and a surveyor thrown to the top of the menu after choosing a
     * theme has to walk back down two rows to try the other one.
     */
    @Test
    fun backFromTheThemeListGoesToSettingsAndNotTheTop() {
        assertEquals(MenuPage.SETTINGS, MenuPage.THEME.parent)
        for (page in MenuPage.entries.filter { it != MenuPage.THEME }) {
            assertEquals(MenuPage.TOP, page.parent, "$page")
        }
    }
}
