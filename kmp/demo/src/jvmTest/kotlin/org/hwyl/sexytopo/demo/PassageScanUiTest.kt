package org.hwyl.sexytopo.demo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scan button on the cross-section editor, on a machine that has no scanner.
 *
 * Which is every machine a test runs on, and is the point rather than a limitation: the JVM build
 * reports no scanner, so what is checked here is the half a surveyor meets when their device
 * cannot do it. That half is easy to get wrong in a way nobody notices — a dead button — and it is
 * the half that produces bug reports, because the iOS Simulator lands in exactly this state.
 *
 * What no test here can reach is a scan actually happening. `PassageScanner.available` is false on
 * the JVM, so `scan()` is never called and nothing exercises ARKit. The arithmetic a scan feeds is
 * covered on its own in `PassageScanTest`, which is why that was written against synthetic passages
 * rather than against a recording: between the two, everything but the sensor is checked.
 */
@OptIn(ExperimentalTestApi::class)
class PassageScanUiTest {

    private fun survey(): Survey =
        Survey("Swildons").also {
            SurveyBuilder.updateWithNewStation(it, Leg(6f, 90f, 0f))
            SurveyBuilder.updateWithNewStation(it, Leg(2f, 180f, -80f))
        }

    private fun section(survey: Survey) =
        CrossSectionDetail(Coord2D(4f, 4f), CrossSection(survey.activeStation, 90f))

    @Test
    fun theEditorOffersToScanThePassage() = runComposeUiTest {
        val survey = survey()
        setContent {
            CrossSectionEditor(
                survey = survey,
                detail = section(survey),
                darkMode = false,
                onCancel = {},
                onDone = {},
            )
        }

        onNodeWithTag("cross-section-scan").assertIsDisplayed()
    }

    /**
     * Pressing it where there is no scanner says why, rather than doing nothing.
     *
     * The whole reason the button stays pressable when it is dimmed. A surveyor on the simulator,
     * or on a desktop, presses it and gets a sentence; without this they get silence and file "the
     * scan button does nothing", which is a true bug report about a working app.
     */
    @Test
    fun pressingItWithNoScannerSaysWhyRatherThanNothing() = runComposeUiTest {
        val survey = survey()
        setContent {
            CrossSectionEditor(
                survey = survey,
                detail = section(survey),
                darkMode = false,
                onCancel = {},
                onDone = {},
            )
        }

        onNodeWithTag("cross-section-scan").performClick()
        waitForIdle()

        onNodeWithTag("cross-section-scan-message").assertIsDisplayed()
    }

    /**
     * The desktop's own reason is a sentence, and one that says what to do instead.
     *
     * Asserted on the string rather than only on its presence because an empty message would still
     * satisfy the check above while telling the surveyor nothing, and because the contract these
     * all keep — empty means there *is* a scanner — makes a blank answer here actively misleading.
     */
    @Test
    fun theDesktopSaysItHasNoScannerAndWhereToScanInstead() {
        val reason = whyNoScanner()

        assertTrue(reason.isNotBlank(), "the desktop build reported a scanner it has not got")
        assertTrue(
            reason.contains("lidar", ignoreCase = true) ||
                reason.contains("iPhone", ignoreCase = true),
            "the reason should say where scanning does work, and says: $reason",
        )
    }

    /**
     * A scan draws walls into the section's own sketch, in the section's own frame.
     *
     * The wiring between the two halves, checked without a sensor by handing the arithmetic the
     * points a sensor would have given it. It is deliberately not a test of `PassageScan` — that
     * has its own — but of the join: that what comes back is added to *this* section's drawing, in
     * metres about the station, and can therefore be drawn over and rubbed out like anything else.
     */
    @Test
    fun scannedWallsBecomeStrokesInTheSectionsOwnSketch() {
        val survey = survey()
        val detail = section(survey)
        val editor = org.hwyl.sexytopo.shared.sketch.SketchEditor(detail.sketch)

        val outlines =
            org.hwyl.sexytopo.shared.sketch.PassageScan.outlines(
                roundPassage(radius = 1.5f, bearing = detail.crossSection.angle),
                detail.crossSection.angle,
            )
        assertEquals(1, outlines.size, "a passage scanned all the way round should be one wall")

        // Exactly what CrossSectionEditor does with what the scanner hands it.
        for (outline in outlines) {
            editor.startPath(outline.first())
            for (point in outline.drop(1)) editor.extendPath(point)
            editor.finishPath()
        }

        assertEquals(1, detail.sketch.pathDetails.size, "the scan drew no wall into the section")
        val drawn = detail.sketch.pathDetails.single().path
        assertTrue(drawn.size >= 4, "the wall was simplified away to ${drawn.size} points")
        for (point in drawn) {
            val distance = kotlin.math.sqrt(point.x * point.x + point.y * point.y)
            assertTrue(
                distance > 1.4f && distance < 1.6f,
                "a 1.5m passage was drawn ${distance}m from the station",
            )
        }

        // And it is one undo step per wall, like every other stroke in this app.
        assertTrue(editor.undo(), "a scanned wall could not be undone")
        assertTrue(
            detail.sketch.pathDetails.isEmpty(),
            "undoing a scanned wall left ${detail.sketch.pathDetails.size} behind",
        )
    }

    /** A tube of rock round the station, as the points a scanner would report. */
    private fun roundPassage(radius: Float, bearing: Float) =
        buildList {
            val radians = bearing.toDouble() * kotlin.math.PI / 180
            for (i in 0 until 720) {
                val angle = i / 720.0 * 2 * kotlin.math.PI
                val across = radius * kotlin.math.cos(angle)
                val down = radius * kotlin.math.sin(angle)
                for (j in -4..4) {
                    val along = j * 0.05
                    add(
                        org.hwyl.sexytopo.shared.model.graph.Coord3D(
                            (across * kotlin.math.cos(radians) + along * kotlin.math.sin(radians))
                                .toFloat(),
                            (-across * kotlin.math.sin(radians) + along * kotlin.math.cos(radians))
                                .toFloat(),
                            (-down).toFloat(),
                        ),
                    )
                }
            }
        }
}
