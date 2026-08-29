package org.hwyl.sexytopo.demo

import androidx.compose.ui.graphics.Color

/**
 * SexyTopo's own colours, taken from `app/src/main/res/values/colors.xml` and its `values-night`
 * override.
 *
 * These are not a designer's reinterpretation. The point of the demo is to let somebody who knows
 * the Android app look at an iPhone and recognise it, and half of recognising an app is its
 * colours — the green panels, the red centreline, the salmon splays. Restyling them to whatever
 * Material 3 generates would have made the port look like a different program that happens to draw
 * caves.
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

    /** `innerPanelBackground` / `dividerColour`. */
    val innerPanel = Color(0xFFDDDDDD)
    val innerPanelNight = Color(0xFF2A2A2A)
    val divider = Color(0xFFA9A9A9)
    val dividerNight = Color(0xFF7F7F7F)

    // -- The survey itself ----------------------------------------------------------------

    /** `leg`. Bright red, which is not what most people guess a cave survey looks like. */
    val leg = Color(0xFFFF0000)
    val legNight = Color(0xFFFF4444)

    /** `legLatest`: the most recently shot leg, so the surveyor can see what just arrived. */
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
     * `legend`: the scale bar, the compass and the survey label — and nothing else.
     *
     * Not station names. `GraphView` draws those with `stationPaint`, i.e. in [station], which is
     * the one label colour a SexyTopo user would actually recognise: red numerals beside red dots.
     */
    val legend = Color(0xFF000000)
    val legendNight = Color(0xFFD3D3D3)

    /** `crossSectionConnection` and `crossSectionIndicator`. */
    val crossSectionConnection = Color(0xFFC0C0C0)
    val crossSectionIndicator = Color(0xFF8B0000)
    val crossSectionIndicatorNight = Color(0xFFFF0000)

    // -- Text ------------------------------------------------------------------------------

    val bodyText = Color(0xFF7F7F7F)
    val bodyTextNight = Color(0xFFBFBFBF)
}

/**
 * The dimensions the Android layout uses, so the toolbar is the size a thumb expects.
 *
 * `toolbar_button_height` is 40dp and the grid is nine columns by two rows — that shape is as much
 * of the app's identity as its colours are.
 */
object SexyTopoDimens {
    /** `R.dimen.toolbar_button_height`. */
    const val TOOLBAR_BUTTON_HEIGHT_DP = 40

    /** `activity_graph.xml`'s `android:columnCount`. */
    const val TOOLBAR_COLUMNS = 9
}
