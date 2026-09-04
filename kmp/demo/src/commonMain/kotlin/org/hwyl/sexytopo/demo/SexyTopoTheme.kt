package org.hwyl.sexytopo.demo

import androidx.compose.ui.graphics.Color

/**
 * SexyTopo's own colours, taken from `app/src/main/res/values/colors.xml` and its `values-night`
 * override, kept exactly rather than restyled to Material 3 defaults.
 *
 * The names are the resource names, so a reader can grep for them in the Android app and find the
 * same value. Where the light and night themes differ, both are here.
 */
object SexyTopoColours {

    // -- Chrome ---------------------------------------------------------------------------

    /** `panelBackground`: the app bar and the button toolbar. */
    val panelBackground = Color(0xFF7FAF7F)
    val panelBackgroundNight = Color(0xFF3A5738)

    /** `lightBackground`: what the survey is drawn on. */
    val canvasBackground = Color(0xFFF5F5F5)
    val canvasBackgroundNight = Color(0xFF121212)

    val onPanel = Color(0xFFFFFFFF)

    /**
     * `buttonHighlight`: what `GraphActivity.selectSketchTool` and `selectBrushColour` tint the
     * background of the selected toolbar button with. Not an alpha wash of [onPanel], which is
     * what this port drew before — the app's own value is opaque, and darker rather than lighter
     * at night.
     */
    val buttonHighlight = Color(0xFFFFFFFF)
    val buttonHighlightNight = Color(0xFFA9A9A9)

    /**
     * `sexyTopoDarkGreen`, which `activity_graph.xml` gives the symbol strip so it reads as a
     * separate band from the button grid below it. The same value in both themes, as upstream.
     */
    val symbolToolbarBackground = Color(0xFF3A5738)

    /**
     * What a symbol on the toolbar is drawn in: the `#000000` every `symbol_uis_*.xml` gives its
     * `strokeColor`, which `Symbol.createDrawable` hands to an `ImageButton` untinted, in both
     * themes. Not [onPanel] — this port drew the glyphs white on the strip's green so they would
     * stand out, and the selected one then sat white on `buttonHighlight`'s white, which is a
     * blank square where the symbol should be. The tool icons beside them are black PNGs on the
     * same green; the glyphs are now the same.
     */
    val symbolGlyph = Color(0xFF000000)

    /** `innerPanelBackground` / `dividerColour`. */
    val innerPanel = Color(0xFFDDDDDD)
    val innerPanelNight = Color(0xFF2A2A2A)
    val divider = Color(0xFFA9A9A9)
    val dividerNight = Color(0xFF7F7F7F)

    // -- The survey table -------------------------------------------------------------------

    /** `tableBackground` and `tableBackgroundAlt`: the alternating row stripes. */
    val tableBackground = Color(0xFFFFFFFF)
    val tableBackgroundNight = Color(0xFF2A2A2A)
    val tableBackgroundAlt = Color(0xFFF5F5F5)
    val tableBackgroundAltNight = Color(0xFF1F1F1F)

    /** `tableHighlight` / `tableHighlightText`: the active station's own cell. */
    val tableHighlight = Color(0xFFFFC107)
    val tableHighlightNight = Color(0xFFFFD54F)
    val tableHighlightText = Color(0xFF000000)

    // -- The survey itself ----------------------------------------------------------------

    /** `leg`. */
    val leg = Color(0xFFFF0000)
    val legNight = Color(0xFFFF4444)

    /** `legLatest`: the most recently shot leg. */
    val legLatest = Color(0xFFFF00FF)

    /** `splay`. */
    val splay = Color(0xFFFF8080)
    val splayNight = Color(0xFFFF8C69)

    /** `station`. */
    val station = Color(0xFF8B0000)
    val stationNight = Color(0xFFFF0000)

    /** `activeStationHighlight`: where the next leg will start from. */
    val activeStation = Color(0xFFFFC107)
    val activeStationNight = Color(0xFFFFD54F)

    /** `grid`. */
    val grid = Color(0xFFD3D3D3)
    val gridNight = Color(0xFF505050)

    /**
     * `hotCorner`: the corner squares that pan the sketch without changing tool. The night value
     * is lighter than the app's own mid-grey, which the Android app never had to answer.
     */
    val hotCorner = Color(0xFF808080)
    val hotCornerNight = Color(0xFFB0B0B0)

    /**
     * `legLatest`: the leg just taken, drawn instead of [leg] so the working end is findable, in
     * full-strength magenta — a colour nothing else in cave surveying uses.
     */
    val latestLeg = Color(0xFFFF00FF)

    /**
     * `legend`: the scale bar, the compass and the survey label — and not station names, which
     * `GraphView` draws in [station].
     */
    val legend = Color(0xFF000000)
    val legendNight = Color(0xFFD3D3D3)

    /** `crossSectionConnection` and `crossSectionIndicator`. */
    val crossSectionConnection = Color(0xFFC0C0C0)

    /**
     * The frame drawn round a cross-section on the plan, and the grip marks on its drag bar.
     * `GraphView` resolves these from the theme, `colorPrimary` and `colorOnPrimary`.
     */
    val crossSectionFrame = Color(0xFF7FAF7F)
    val onCrossSectionFrame = Color(0xFFFFFFFF)
    val crossSectionIndicator = Color(0xFF8B0000)
    val crossSectionIndicatorNight = Color(0xFFFF0000)

    // -- Text ------------------------------------------------------------------------------

    val bodyText = Color(0xFF7F7F7F)
    val bodyTextNight = Color(0xFFBFBFBF)
}

/** The dimensions the Android layout uses, so the toolbar is the size a thumb expects. */
object SexyTopoDimens {
    /** `R.dimen.toolbar_button_height`. */
    const val TOOLBAR_BUTTON_HEIGHT_DP = 40

    /**
     * `activity_graph.xml`'s `android:columnCount`.
     *
     * What the Android layout says, which is not what this port draws: `SketchToolbar` is ten wide,
     * the tenth column being the camera the app has no counterpart for. This stays at nine so
     * `DimensionParityTest` goes on holding it to the XML, which is the whole point of it — a
     * constant that drifted to match the port would stop reporting anything.
     */
    const val TOOLBAR_COLUMNS = 9
}
