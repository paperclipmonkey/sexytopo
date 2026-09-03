package org.hwyl.sexytopo.shared.sketch

/**
 * How big everything on the drawing is: `preferences_sketching.xml`'s numeric group.
 *
 * These are the values the Android app **uses** — `GeneralPreferences`' own fallbacks — which for
 * four of the eight are *not* the values its settings screen shows as the default: nothing calls
 * `PreferenceManager.setDefaultValues`, so on a fresh install the key is simply absent and the
 * getter's own fallback wins. Where they differ, the value taken here is the one actually drawn.
 *
 * | Preference | Shown on the screen | Used by the code |
 * | --- | --- | --- |
 * | `pref_sketch_line_width` | 1.5 | 1.5 |
 * | `pref_leg_width` | 2 | 2 |
 * | `pref_splay_width` | 1 | 1 |
 * | `pref_station_diameter` | 10 | 10 |
 * | `pref_station_label_font_size_sp` | 8 | **10** |
 * | `pref_legend_font_size_sp` | 8 | **10** |
 * | `pref_survey_symbol_size` | 35 | **25** |
 * | `pref_survey_text_tool_font_size` | 50 | **16**, and see below |
 *
 * The last row is worse than a disagreement. The screen writes `pref_survey_text_tool_font_size`
 * and `GeneralPreferences.getTextStartingSizeSp` reads `pref_survey_text_tool_font_size_sp` — a
 * different key — so upstream that preference does nothing at all, whatever it is set to.
 */
data class SketchStyle(
    /** A drawn stroke's width, in dp. `pref_sketch_line_width`. */
    val sketchLineWidthDp: Float = DEFAULT_SKETCH_LINE_WIDTH_DP,
    /** A leg's width, in dp. `pref_leg_width`. */
    val legWidthDp: Float = DEFAULT_LEG_WIDTH_DP,
    /** A splay's width, in dp. `pref_splay_width`. */
    val splayWidthDp: Float = DEFAULT_SPLAY_WIDTH_DP,
    /** How big a station is drawn, across, in dp. `pref_station_diameter`. */
    val stationDiameterDp: Float = DEFAULT_STATION_DIAMETER_DP,
    /** A station's name, in scale-independent pixels. `pref_station_label_font_size_sp`. */
    val stationLabelSizeSp: Float = DEFAULT_STATION_LABEL_SIZE_SP,
    /** The scale bar and the survey's name, likewise. `pref_legend_font_size_sp`. */
    val legendSizeSp: Float = DEFAULT_LEGEND_SIZE_SP,
    /** How big a symbol is stamped, on screen, before the zoom converts it. `pref_survey_symbol_size`. */
    val symbolSizeDp: Float = DEFAULT_SYMBOL_SIZE_DP,
    /** How big a written label starts. `pref_survey_text_tool_font_size`. */
    val textSizeSp: Float = DEFAULT_TEXT_SIZE_SP,
) {
    /** Half of [stationDiameterDp]: the arm of the cross a station is drawn as. */
    val stationRadiusDp: Float
        get() = stationDiameterDp / 2f

    companion object {
        const val DEFAULT_SKETCH_LINE_WIDTH_DP = 1.5f
        const val DEFAULT_LEG_WIDTH_DP = 2.0f
        const val DEFAULT_SPLAY_WIDTH_DP = 1.0f
        const val DEFAULT_STATION_DIAMETER_DP = 10.0f
        const val DEFAULT_STATION_LABEL_SIZE_SP = 10.0f
        const val DEFAULT_LEGEND_SIZE_SP = 10.0f
        const val DEFAULT_SYMBOL_SIZE_DP = 25.0f
        const val DEFAULT_TEXT_SIZE_SP = 16.0f

        /**
         * The bounds every one of these is held inside.
         *
         * Not limits the Android app has: it takes any number its text field will accept, and an
         * extreme value produces a drawing with no way to tell a settings screen did it. Clamped on
         * the way in rather than trusted.
         */
        const val SMALLEST = 0.5f
        const val LARGEST = 60.0f

        val DEFAULT = SketchStyle()

        /** Held inside [SMALLEST]..[LARGEST], with anything unreadable falling back to [fallback]. */
        fun size(typed: String?, fallback: Float): Float =
            typed?.trim()?.toFloatOrNull()?.coerceIn(SMALLEST, LARGEST) ?: fallback
    }
}
