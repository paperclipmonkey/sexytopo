package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.export.SvgExporter
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seventeen things a surveyor can decide about the drawing that leaves the cave.
 *
 * The exporter has taken all of them since it was ported. What it had never had was a caller that
 * passed anything but the default, which is finding 59 and the reason these checks exist: an
 * option nothing sets is indistinguishable, from the app, from an option that does not work.
 */
class SvgExportOptionsTest {

    private val survey = ExampleSurvey.create()

    private fun svg(options: SvgExporter.Options): String =
        exportText(survey, ExportFormat.SVG, Projection2D.PLAN, today = "2026-01-01", options)

    /**
     * The default export, so that each check below is against a file that *does* contain the thing
     * it then turns off. Without this, every one of them would pass on an exporter that had
     * quietly stopped drawing anything at all.
     */
    @Test
    fun theDefaultDrawingHasEverythingInIt() {
        val out = svg(SvgExporter.Options.DEFAULT)
        for (group in
            listOf("background", "grid", "sketch", "cross-sections", "centreline", "splays",
                "stations", "legend", "scale-bar", "north-arrow")) {
            assertTrue("id=\"$group\"" in out, "a default export should contain the $group")
        }
    }

    /**
     * Each option, one at a time, against the group it removes.
     *
     * One test rather than ten because the interesting failure is not "the grid switch is broken",
     * it is "the switches are wired to the wrong things" - which only shows up when they are all
     * checked against each other.
     */
    @Test
    fun eachSwitchTakesOutItsOwnPartOfTheDrawing() {
        val default = SvgExporter.Options.DEFAULT
        val cases: List<Pair<String, SvgExporter.Options>> =
            listOf(
                "background" to default.copy(whiteBackground = false),
                "grid" to default.copy(showGrid = false),
                "sketch" to default.copy(showSketch = false),
                "cross-sections" to default.copy(showCrossSections = false),
                "centreline" to default.copy(showCentreline = false),
                "splays" to default.copy(showSplays = false),
                "stations" to default.copy(showStations = false),
                "legend" to default.copy(showLegend = false),
                "scale-bar" to default.copy(showScaleBar = false),
                "north-arrow" to default.copy(showNorthArrow = false),
            )
        for ((group, options) in cases) {
            assertFalse(
                "id=\"$group\"" in svg(options),
                "turning $group off should take it out of the file",
            )
        }
    }

    /** The three that are text in the legend rather than a group of their own. */
    @Test
    fun theCreditsCanBeTakenOutOfTheLegend() {
        val default = SvgExporter.Options.DEFAULT
        val full = svg(default)
        assertTrue("SexyTopo" in full, "the tagline credits the app by name")

        assertFalse(
            "SexyTopo" in svg(default.copy(showTagline = false)),
            "turning the tagline off should stop the drawing crediting the app",
        )
    }

    /** Turning the legend off takes the four things inside it with it, however they are set. */
    @Test
    fun theLegendCarriesTheFourThingsInsideIt() {
        val out =
            svg(
                SvgExporter.Options.DEFAULT.copy(
                    showLegend = false,
                    showScaleBar = true,
                    showNorthArrow = true,
                    showTeam = true,
                    showTagline = true,
                ),
            )
        assertFalse("id=\"scale-bar\"" in out, "no legend means no scale bar")
        assertFalse("id=\"north-arrow\"" in out, "no legend means no north arrow")
        assertFalse("SexyTopo" in out, "no legend means no tagline")
    }

    /**
     * A width the surveyor typed actually reaches the file.
     *
     * The centreline at width 9 rather than 2, which is a number that cannot appear in a default
     * export by accident.
     */
    @Test
    fun aChosenLegWidthIsTheOneDrawn() {
        val out = svg(SvgExporter.Options.DEFAULT.copy(legStrokeWidth = 9))
        assertTrue("stroke-width=\"9\"" in out, "the leg width should be the one asked for")
    }

    @Test
    fun everySvgOptionSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        // Every one flipped away from its default, so a value that failed to round-trip would come
        // back as the default and fail rather than pass by luck.
        val flipped =
            SvgExporter.Options(
                whiteBackground = false,
                showGrid = false,
                showSketch = false,
                showSymbols = false,
                showCrossSections = false,
                showCentreline = false,
                showSplays = false,
                showStations = false,
                showLegend = false,
                showNorthArrow = false,
                showScaleBar = false,
                showTeam = false,
                showCopyright = false,
                showTagline = false,
                sketchStrokeWidth = 3,
                legStrokeWidth = 7,
                splayStrokeWidth = 5,
            )
        AppPreferencesStore.save(store, AppPreferences(svgExport = flipped))

        assertEquals(flipped, AppPreferencesStore.load(store).svgExport)
    }

    /**
     * The defaults are what the Android app *exports*, which is not what its settings screen says.
     *
     * `preferences_export_svg.xml` gives the grid `android:defaultValue="false"`, so the checkbox
     * on the settings screen appears unticked; nothing calls `PreferenceManager.setDefaultValues`,
     * so on a fresh install the key is absent and `isExportSvgGridEnabled`'s own fallback of true
     * decides. The file has a grid in it. Same story for the background, whose fallback is
     * `"white"` - and `SvgExportOptions` declares both fields false, which is dead code, because
     * its no-argument constructor is never called.
     */
    @Test
    fun theDefaultsAreWhatTheAndroidAppExportsRatherThanWhatItsScreenShows() {
        assertTrue(
            AppPreferences.DEFAULT.svgExport.whiteBackground,
            "getExportSvgBackgroundColour falls back to white",
        )
        assertTrue(
            AppPreferences.DEFAULT.svgExport.showGrid,
            "isExportSvgGridEnabled falls back to true, whatever the settings screen shows",
        )
    }

    /**
     * A stroke width of zero is not a thinner line, it is no line.
     *
     * The Android app takes it: `getExportSvgStrokeWidth` parses whatever the text box stored and
     * hands it to the exporter, so typing 0 there produces an SVG whose centreline is invisible -
     * which looks exactly like an export that lost the survey. This port clamps instead.
     */
    @Test
    fun aWidthThatWouldDrawNothingIsRefused() {
        assertEquals(
            AppPreferencesStore.MIN_STROKE_WIDTH,
            AppPreferencesStore.strokeWidth("0", fallback = 2),
        )
        assertEquals(
            AppPreferencesStore.MIN_STROKE_WIDTH,
            AppPreferencesStore.strokeWidth("-4", fallback = 2),
        )
        assertEquals(
            AppPreferencesStore.MAX_STROKE_WIDTH,
            AppPreferencesStore.strokeWidth("100000", fallback = 2),
        )
    }

    /**
     * Half-typed rubbish keeps what was there rather than resetting to the app's default.
     *
     * These are text boxes, so every value passes through the empty string on the way to being
     * retyped; falling back to the default there would quietly undo a surveyor's chosen width the
     * moment they touched a different field and pressed Save.
     */
    @Test
    fun aHalfTypedWidthKeepsTheValueItHad() {
        assertEquals(7, AppPreferencesStore.strokeWidth("", fallback = 7))
        assertEquals(7, AppPreferencesStore.strokeWidth("  ", fallback = 7))
        assertEquals(7, AppPreferencesStore.strokeWidth("2.5", fallback = 7))
    }

    /** A file written by a version that had never heard of these still opens on its defaults. */
    @Test
    fun aPreferencesFileFromBeforeTheseExistedStillLoads() {
        val loaded = AppPreferencesStore.parse("theme=dark\nshowGrid=false\n")

        assertEquals(SvgExporter.Options.DEFAULT, loaded.svgExport)
    }
}
