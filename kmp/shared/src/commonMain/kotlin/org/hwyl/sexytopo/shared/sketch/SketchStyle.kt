package org.hwyl.sexytopo.shared.sketch

/**
 * How big everything on the drawing is: `preferences_sketching.xml`'s numeric group.
 *
 * ## Why a surveyor changes these
 *
 * A plan read on a desk and a plan read at arm's length under a helmet, by a head torch, through
 * a scratched screen, with the phone in a dry bag, are not the same picture. A hairline centreline
 * that is perfectly legible on the first is guesswork on the second, and the surveyor who needs it
 * heavier needs it heavier *now*, at the station, not when they get home.
 *
 * ## Where the numbers come from, and a warning about the ones on screen
 *
 * These are the values the Android app **uses** — `GeneralPreferences`' own fallbacks — which for
 * four of the eight are *not* the values its settings screen shows as the default. The screen and
 * the code disagree, in the same way and for the same reason as `pref_vibrate_on_new_station`
 * (see `AppPreferencesStore`): nothing calls `PreferenceManager.setDefaultValues`, so on a fresh
 * install the key is simply absent and the getter's own fallback wins. Where they differ, the
 * value taken here is the one that is actually drawn.
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
 * different key — so upstream that preference does nothing at all, whatever it is set to. Reported
 * as finding 52.
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
    /** Half of [stationDiameterDp]: the port draws a filled dot where the Java draws a cross. */
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
         * Not limits the Android app has: it takes any number its text field will accept, and a
         * leg width of zero draws a cave with no centreline in it while a station diameter of two
         * thousand draws one dot over the whole screen. Neither is recoverable from the drawing —
         * the surveyor has to know that a settings screen did it — so the value is clamped on the
         * way in rather than trusted.
         */
        const val SMALLEST = 0.5f
        const val LARGEST = 60.0f

        val DEFAULT = SketchStyle()

        /** Held inside [SMALLEST]..[LARGEST], with anything unreadable falling back to [fallback]. */
        fun size(typed: String?, fallback: Float): Float =
            typed?.trim()?.toFloatOrNull()?.coerceIn(SMALLEST, LARGEST) ?: fallback
    }
}
