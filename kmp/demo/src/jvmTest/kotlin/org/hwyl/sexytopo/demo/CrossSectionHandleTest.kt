package org.hwyl.sexytopo.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Station
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Hit-testing the bar you drag a cross-section by.
 *
 * The bar has been drawn since the frame went in, and until now nothing tested where a finger
 * landed against it, so the three grip marks that say "drag me" were decoration. This is the test
 * for the thing that makes them true.
 *
 * Everything here is in screen pixels, because that is what
 * [findCrossSectionHandleAt] works in: the rectangles come from the draw pass, already projected.
 */
class CrossSectionHandleTest {

    private fun section(name: String) =
        CrossSectionDetail(Coord2D.ORIGIN, CrossSection(Station(name), 0f))

    /** A bar 200 wide and 8 tall, as the app draws it, with its top-left at (100, 300). */
    private fun bar(left: Float = 100f, top: Float = 300f, height: Float = 8f) =
        Rect(left, top, left + 200f, top + height)

    private val reach = 24f

    @Test
    fun aPressOnTheBarPicksUpItsSection() {
        val detail = section("2")
        val rects = mapOf(detail to bar())
        assertSame(detail, findCrossSectionHandleAt(rects, Offset(200f, 304f), reach))
    }

    /**
     * The departure from the Android app, and the reason the feature is usable at all: the bar is
     * drawn 8dp tall — about 1.3mm — and the app hit-tests exactly that. Here the rectangle is
     * grown to 24dp before the test.
     */
    @Test
    fun aPressJustAboveTheBarStillPicksItUp() {
        val detail = section("2")
        val rects = mapOf(detail to bar())
        // 296 is above the drawn bar (top 300) and inside the grown reach (bottom 308 - 24 = 284).
        assertSame(detail, findCrossSectionHandleAt(rects, Offset(200f, 296f), reach))
        assertNull(findCrossSectionHandleAt(rects, Offset(200f, 296f), minimumHeightPx = 0f))
    }

    /** The growth is bounded: further up than the reach is the plan, and a press there is a press
     * on the plan. */
    @Test
    fun aPressWellAboveTheBarIsNotOnIt() {
        val rects = mapOf(section("2") to bar())
        assertNull(findCrossSectionHandleAt(rects, Offset(200f, 283f), reach))
    }

    /**
     * And the growth is upwards only. Below the bar is the section's own frame, where a press
     * means "open this section for drawing" — the gesture the port already had. Growing the bar
     * downwards would have bought the drag by breaking the tap.
     */
    @Test
    fun aPressInsideTheFrameBelowTheBarIsNotOnTheBar() {
        val rects = mapOf(section("2") to bar())
        assertNull(findCrossSectionHandleAt(rects, Offset(200f, 309f), reach))
    }

    /** Sideways it is exactly as wide as it looks; the frame's corners are not part of it. */
    @Test
    fun theBarIsOnlyAsWideAsItIsDrawn() {
        val rects = mapOf(section("2") to bar())
        assertNull(findCrossSectionHandleAt(rects, Offset(99f, 304f), reach))
        assertNull(findCrossSectionHandleAt(rects, Offset(301f, 304f), reach))
        assertSame(rects.keys.first(), findCrossSectionHandleAt(rects, Offset(299f, 304f), reach))
    }

    /**
     * A bar already taller than the minimum is left alone rather than being centred, moved or
     * grown further — otherwise a section zoomed right in would have a hit area drifting above
     * its own frame.
     */
    @Test
    fun aBarTallerThanTheReachIsNotGrown() {
        val rects = mapOf(section("2") to bar(height = 40f))
        assertSame(rects.keys.first(), findCrossSectionHandleAt(rects, Offset(200f, 301f), reach))
        assertNull(findCrossSectionHandleAt(rects, Offset(200f, 299f), reach))
    }

    /**
     * Two sections stacked close enough that one bar's reach overlaps the other's: first in the
     * map wins, which is what `GraphView.findCrossSectionHandleAt` does with its LinkedHashMap.
     */
    @Test
    fun overlappingBarsGoToWhicheverWasDrawnFirst() {
        val upper = section("2")
        val lower = section("3")
        val rects = linkedMapOf(upper to bar(top = 300f), lower to bar(top = 318f))
        // 310 is below the upper bar's reach (284..308) and inside the lower bar's (302..326).
        assertSame(lower, findCrossSectionHandleAt(rects, Offset(200f, 310f), reach))
        // 304 is inside both. The upper one was drawn first, so it is the one that moves.
        assertSame(upper, findCrossSectionHandleAt(rects, Offset(200f, 304f), reach))
        // ...and it is the map order that decides, not which is higher up the screen.
        val reversed = linkedMapOf(lower to bar(top = 318f), upper to bar(top = 300f))
        assertSame(lower, findCrossSectionHandleAt(reversed, Offset(200f, 304f), reach))
    }

    /**
     * Nothing drawn, nothing to grab. This is how "show cross-sections: off" and legacy mode are
     * honoured without a second set of conditions: the map is filled by the draw pass, so a
     * section that is not on the screen has no rectangle in it.
     */
    @Test
    fun aSectionThatWasNotDrawnCannotBeGrabbed() {
        assertNull(findCrossSectionHandleAt(emptyMap(), Offset(200f, 304f), reach))
    }
}
