package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.sketch.SketchStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How big everything on the drawing is: `preferences_sketching.xml`'s numeric group.
 *
 * The rendering half — that a wider leg really does put more ink on the page — is
 * [DrawingSizeTest], which draws the same survey twice through headless Skia. This is the
 * arithmetic and the file.
 */
class SketchStyleTest {

    /**
     * The defaults are the ones the app **draws with**, which for four of the eight are not the
     * ones its settings screen shows.
     *
     * Nothing calls `PreferenceManager.setDefaultValues`, so on a fresh install the key is absent
     * and the getter's own fallback wins. Where the two disagree, what a surveyor sees on the rock
     * is the getter's.
     */
    @Test
    fun theDefaultsAreTheOnesTheAppDrawsWith() {
        val style = SketchStyle.DEFAULT

        assertEquals(1.5f, style.sketchLineWidthDp, "pref_sketch_line_width")
        assertEquals(2.0f, style.legWidthDp, "pref_leg_width")
        assertEquals(1.0f, style.splayWidthDp, "pref_splay_width")
        assertEquals(10.0f, style.stationDiameterDp, "pref_station_diameter")

        assertEquals(10.0f, style.stationLabelSizeSp, "getStationLabelFontSizeSp, not the screen's 8")
        assertEquals(10.0f, style.legendSizeSp, "getLegendFontSizeSp, not the screen's 8")
        assertEquals(25.0f, style.symbolSizeDp, "getSymbolStartingSizeDp, not the screen's 35")
        assertEquals(16.0f, style.textSizeSp, "getTextStartingSizeSp, not the screen's 50")
    }

    @Test
    fun everySizeSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        val chosen =
            SketchStyle(
                sketchLineWidthDp = 3f,
                legWidthDp = 4f,
                splayWidthDp = 2f,
                stationDiameterDp = 16f,
                stationLabelSizeSp = 14f,
                legendSizeSp = 13f,
                symbolSizeDp = 40f,
                textSizeSp = 22f,
            )
        AppPreferencesStore.save(store, AppPreferences(sketchStyle = chosen))

        assertEquals(chosen, AppPreferencesStore.load(store).sketchStyle)
    }

    /**
     * A size is held inside bounds rather than trusted.
     *
     * The Android app takes whatever its text field will accept, and neither end is recoverable
     * from the drawing: a leg width of zero draws a cave with no centreline in it, and a station
     * diameter of two thousand draws one dot over the whole screen. In both cases the surveyor has
     * to *know* that a settings screen did it, because nothing on the plan says so.
     */
    @Test
    fun aSizeIsHeldInsideBoundsRatherThanTrusted() {
        val parsed = AppPreferencesStore.parse("legWidthDp=0\nstationDiameterDp=2000\n")

        assertEquals(SketchStyle.SMALLEST, parsed.sketchStyle.legWidthDp)
        assertEquals(SketchStyle.LARGEST, parsed.sketchStyle.stationDiameterDp)
    }

    @Test
    fun anUnreadableSizeReadsAsTheDefault() {
        val parsed = AppPreferencesStore.parse("legWidthDp=fat\nsplayWidthDp=\n")

        assertEquals(SketchStyle.DEFAULT_LEG_WIDTH_DP, parsed.sketchStyle.legWidthDp)
        assertEquals(SketchStyle.DEFAULT_SPLAY_WIDTH_DP, parsed.sketchStyle.splayWidthDp)
    }

    /**
     * A half-typed field keeps the value it had rather than resetting to the app's default.
     *
     * Every one of these boxes passes through the empty string when somebody clears it to retype
     * — so falling back to the default there would silently undo a surveyor's chosen line width
     * the moment they edited a different field and pressed Save.
     */
    @Test
    fun aHalfTypedSizeKeepsTheValueItHad() {
        val current = SketchStyle(legWidthDp = 5f)
        val saved =
            styleFrom(
                current,
                legWidth = "",
                splayWidth = "1",
                lineWidth = "1.5",
                stationDiameter = "10",
                stationLabel = "10",
                legend = "10",
                symbol = "25",
                text = "16",
            )

        assertEquals(5f, saved.legWidthDp)
    }

    /**
     * The eraser's own preference, which the engine has always implemented and nothing ever set.
     *
     * `SketchEditor.eraseAt` takes `deletePathFragments` and splits the stroke, and the canvas
     * never passed it — so the app had the behaviour and not the choice.
     */
    @Test
    fun theEraserRuleIsAChoiceRatherThanAConstant() {
        assertTrue(AppPreferences.DEFAULT.deletePathFragments, "on, as the Android app ships it")
        assertTrue(DisplayOptions().deletePathFragments, "and the canvas gets the same default")

        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(deletePathFragments = false))
        assertFalse(AppPreferencesStore.load(store).deletePathFragments)
    }
}
