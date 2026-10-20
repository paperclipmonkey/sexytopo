package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cross-sections on the extended elevation, upstream 1.13.0's: made from a station's menu there,
 * vertical (across a passage) or horizontal (a level slice, for a shaft), and turned to face left
 * or right since the elevation has no bearings.
 *
 * The menu cases are `ViewContextMenuTest`'s and `ViewContextTest`'s. The Android app shows and
 * hides items of one inflated menu per view; here [stationActionsFor] builds the list a view
 * offers, so the same questions are asked of that list.
 */
class CrossSectionOnTheElevationTest {

    /** Origin 1 and stations 2, 3, 4 due north, 5m apart. */
    private fun passage(): Survey {
        val survey = Survey("T")
        repeat(3) { SurveyBuilder.updateWithNewStation(survey, Leg(5f, 0f, 0f)) }
        return survey
    }

    private fun actions(
        survey: Survey,
        name: String,
        projection: Projection2D,
        fromTable: Boolean = false,
    ) =
        stationActionsFor(
            survey,
            survey.getStationByName(name)!!,
            projection,
            survey.getSketch(projection),
            fromTable,
        )

    private val creating =
        setOf(
            StationAction.CROSS_SECTION_CREATE,
            StationAction.CROSS_SECTION_CREATE_VERTICAL,
            StationAction.CROSS_SECTION_CREATE_HORIZONTAL,
        )

    // From `ViewContextTest`

    @Test
    fun onlyTheExtendedElevationCanMakeHorizontalSections() {
        assertTrue(canCreateHorizontalCrossSection(Projection2D.EXTENDED_ELEVATION))
        for (projection in Projection2D.entries - Projection2D.EXTENDED_ELEVATION) {
            assertFalse(canCreateHorizontalCrossSection(projection), projection.name)
        }
    }

    // From `ViewContextMenuTest`

    @Test
    fun thePlanOffersSectionsWithSetDirection() {
        val survey = passage()
        val editor = SketchEditor(survey.planSketch)
        editor.addCrossSection(sectionFor(survey, survey.getStationByName("2")!!), Coord2D(9f, 0f))

        assertContains(actions(survey, "2", Projection2D.PLAN), StationAction.CROSS_SECTION_SET_DIRECTION)
    }

    @Test
    fun theElevationOffersSectionsWithSetDirection() {
        val survey = passage()
        val editor = SketchEditor(survey.elevationSketch)
        editor.addCrossSection(sectionFor(survey, survey.getStationByName("2")!!), Coord2D(9f, 0f))

        val offered = actions(survey, "2", Projection2D.EXTENDED_ELEVATION)
        assertContains(offered, StationAction.CROSS_SECTION_EDIT)
        assertContains(offered, StationAction.CROSS_SECTION_SET_DIRECTION)
        assertContains(offered, StationAction.CROSS_SECTION_DELETE)
    }

    @Test
    fun theElevationKeepsItsOtherRows() {
        val offered = actions(passage(), "2", Projection2D.EXTENDED_ELEVATION)

        assertContains(offered, StationAction.DIRECTION_LEFT)
        assertFalse(StationAction.SHOW_IN_ELEVATION in offered)
    }

    @Test
    fun thePlanOffersOnlyThePlainNewSection() {
        val offered = actions(passage(), "2", Projection2D.PLAN)

        assertEquals(listOf(StationAction.CROSS_SECTION_CREATE), offered.filter { it in creating })
    }

    @Test
    fun theElevationOffersVerticalAndHorizontal() {
        val offered = actions(passage(), "2", Projection2D.EXTENDED_ELEVATION)

        assertEquals(
            listOf(
                StationAction.CROSS_SECTION_CREATE_VERTICAL,
                StationAction.CROSS_SECTION_CREATE_HORIZONTAL,
            ),
            offered.filter { it in creating },
        )
    }

    /**
     * The views without sections, `TABLE`, `ELEVATION` and `THREE_D`: the table is the menu opened
     * from it, and the other two elevations and the 3D view have no station menu here at all.
     */
    @Test
    fun theTableOffersNoSections() {
        for (projection in listOf(Projection2D.PLAN, Projection2D.EXTENDED_ELEVATION)) {
            val offered = actions(passage(), "2", projection, fromTable = true)
            assertTrue(offered.none { it.name.startsWith("CROSS_SECTION") }, projection.name)
        }
        assertFalse(hasCrossSections(Projection2D.ELEVATION_NS))
        assertFalse(hasCrossSections(Projection2D.ELEVATION_EW))
        assertFalse(hasCrossSections(Projection2D.CROSS_SECTION))
    }

    /** `configureMenuVisibility` disables Set Direction for a horizontal section; here it goes. */
    @Test
    fun aHorizontalSectionHasNoDirectionToSet() {
        val survey = passage()
        val editor = SketchEditor(survey.elevationSketch)
        editor.addCrossSection(
            CrossSection.horizontal(survey.getStationByName("2")!!),
            Coord2D(9f, 0f),
        )

        val offered = actions(survey, "2", Projection2D.EXTENDED_ELEVATION)
        assertFalse(StationAction.CROSS_SECTION_SET_DIRECTION in offered)
        assertContains(offered, StationAction.CROSS_SECTION_DELETE)
        assertTrue(offered.none { it in creating })
    }

    /** Each sketch has its own, so a section on the plan says nothing about the elevation's. */
    @Test
    fun aSectionOnThePlanLeavesTheElevationFreeToHaveOne() {
        val survey = passage()
        val station = survey.getStationByName("2")!!
        SketchEditor(survey.planSketch).addCrossSection(sectionFor(survey, station), Coord2D(9f, 0f))

        assertContains(
            actions(survey, "2", Projection2D.EXTENDED_ELEVATION),
            StationAction.CROSS_SECTION_CREATE_HORIZONTAL,
        )
        assertNull(crossSectionAt(survey.elevationSketch, station))
    }

    /** `setCrossSectionCreateTitle`: "New Vertical Section ↕ passage", "... ↔ shaft". */
    @Test
    fun theElevationsNewSectionRowsCarryTheirGlyphAndHint() {
        val vertical = assertNotNull(crossSectionCreateHint(StationAction.CROSS_SECTION_CREATE_VERTICAL))
        val horizontal =
            assertNotNull(crossSectionCreateHint(StationAction.CROSS_SECTION_CREATE_HORIZONTAL))

        assertEquals("↕", vertical.glyph)
        assertEquals(Strings.menuCrossSectionHintVertical, vertical.text)
        assertEquals("↔", horizontal.glyph)
        assertEquals(Strings.menuCrossSectionHintHorizontal, horizontal.text)
        assertNull(crossSectionCreateHint(StationAction.CROSS_SECTION_CREATE))
    }

    @Test
    fun theEditorIsTitledForTheKindOfSection() {
        assertEquals(
            Strings.titleCrossSectionVertical,
            CrossSectionLabels.title(CrossSection.Orientation.VERTICAL),
        )
        assertEquals(
            Strings.titleCrossSectionHorizontal,
            CrossSectionLabels.title(CrossSection.Orientation.HORIZONTAL),
        )
    }

    // Making one: `handleNewCrossSection` with an orientation

    private fun state(): DemoState =
        DemoState(
            exampleSurvey = ExampleSurvey.create(),
            initialProjection = Projection2D.EXTENDED_ELEVATION,
            initialSystemDark = false,
            initialTool = null,
            initialMode = SurveyMode.LIVE,
            initialScreen = Screen.SKETCH,
        )

    @Test
    fun askingForAHorizontalSectionArmsTheToolForOne() {
        val station = passage().getStationByName("2")!!
        val state = state()
        state.chooseTool(SketchTool.DRAW)

        state.beginCrossSection(station, CrossSection.Orientation.HORIZONTAL)

        assertEquals(SketchTool.POSITION_CROSS_SECTION, state.tool)
        assertEquals(CrossSection.Orientation.HORIZONTAL, state.crossSectioningOrientation)

        // One shot, and the next section asked for without saying is vertical again
        state.finishCrossSection()
        assertEquals(SketchTool.DRAW, state.tool)
        assertEquals(CrossSection.Orientation.VERTICAL, state.crossSectioningOrientation)
    }

    // Setting its direction: `getRotationAngle` on the elevation

    private fun elevationDrag(survey: Survey, orientation: CrossSection.Orientation): SectionDrag {
        val station = survey.getStationByName("2")!!
        val editor = SketchEditor(survey.elevationSketch)
        val detail = editor.addCrossSection(sectionFor(survey, station, orientation), Coord2D(9f, 0f))
        return SectionDrag(
            SectionDragMode.ROTATE,
            detail,
            from = Coord2D(9f, 0f),
            pivot = Coord2D(5f, 0f),
            elevation = survey,
        )
    }

    @Test
    fun onTheElevationASectionSnapsToFaceTheSideTheFingerIsOn() {
        val survey = passage()
        val drag = elevationDrag(survey, CrossSection.Orientation.VERTICAL)

        // The survey heads north, drawn to the right: right is along it, left is back against it
        assertEquals(0f, drag.movedTo(Coord2D(8f, -3f)).azimuth)
        assertEquals(180f, drag.movedTo(Coord2D(2f, 4f)).azimuth)
        // Straight above the station is neither side
        assertNull(drag.movedTo(Coord2D(5f, -3f)).azimuth)
    }

    @Test
    fun onTheElevationFacingRightFollowsTheWayTheSurveyIsDrawn() {
        val survey = passage()
        SurveyUpdater.setExtendedElevationDirection(
            survey,
            survey.origin,
            ExtendedElevationDirection.LEFT,
        )
        val drag = elevationDrag(survey, CrossSection.Orientation.VERTICAL)

        // Drawn to the left, facing right on the page is looking back down the survey
        assertEquals(180f, drag.movedTo(Coord2D(8f, 0f)).azimuth)
    }

    @Test
    fun turningTheSectionOnTheElevationIsSavedAsABearing() {
        val survey = passage()
        val drag = elevationDrag(survey, CrossSection.Orientation.VERTICAL)

        assertTrue(drag.movedTo(Coord2D(1f, 0f)).commit(SketchEditor(survey.elevationSketch)))

        // Nothing new is saved: the bearing back down the survey, which the Android app reads
        // as a section facing left just the same
        val turned = survey.elevationSketch.crossSectionDetails.single()
        assertEquals(180f, turned.crossSection.angle)
        assertSame(drag.detail.sketch, turned.sketch, "the drawing inside is left alone")
    }

    @Test
    fun aHorizontalSectionCannotBeTurned() {
        val survey = passage()
        val drag = elevationDrag(survey, CrossSection.Orientation.HORIZONTAL)
        val editor = SketchEditor(survey.elevationSketch)

        val moved = drag.movedTo(Coord2D(1f, 0f))

        assertFalse(moved.commit(editor), "nothing to turn, so nothing to undo")
        assertFalse(editor.canUndo)
        assertEquals(
            CrossSection.Orientation.HORIZONTAL,
            survey.elevationSketch.crossSectionDetails.single().crossSection.orientation,
        )
        assertSame(drag.detail, moved.preview())
    }

    // The editor's view of a horizontal section

    @Test
    fun aHorizontalSectionsEditorJudgesItsSplaysByHowLevelTheyAre() {
        val survey = passage()
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(2f, 90f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(2f, 0f, 80f))
        val detail =
            SketchEditor(survey.elevationSketch)
                .addCrossSection(CrossSection.horizontal(station), Coord2D(9f, 0f))

        val scene = SurveyScene.forCrossSection(detail, detail.sketch.copy(), survey)

        // The level splay is in the slice; the steep one points out of it, so it is dashed
        assertEquals(listOf(true, false), scene.splays.map { it.inPlane })
    }

    // Drawn on the elevation: rendered, because the question is what is on the screen

    @OptIn(ExperimentalComposeUiApi::class)
    private fun elevationWith(orientation: CrossSection.Orientation): BufferedImage {
        val survey = passage()
        val station = survey.getStationByName("2")!!
        SurveyBuilder.addSplay(survey, station, Leg(2f, 90f, 0f))
        SurveyBuilder.addSplay(survey, station, Leg(2f, 270f, 0f))
        val editor = SketchEditor(survey.elevationSketch)
        editor.addCrossSection(sectionFor(survey, station, orientation), Coord2D(5f, 6f))
        val scene =
            ImageComposeScene(width = 360, height = 640, density = Density(1f)) {
                SurveyCanvas(
                    survey = survey,
                    projection = Projection2D.EXTENDED_ELEVATION,
                    options = DisplayOptions(showGrid = false, showStationLabels = false),
                    editor = editor,
                    canvas = CanvasController(),
                    modifier = Modifier.fillMaxSize(),
                    tool = SketchTool.MOVE,
                    revision = 0,
                )
            }
        val image = try { scene.render() } finally { scene.close() }
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia would not encode")
        return ImageIO.read(ByteArrayInputStream(png.bytes))
    }

    private fun BufferedImage.count(rgb: Int): Int {
        var found = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getRGB(x, y) and 0xFFFFFF == rgb) found++
            }
        }
        return found
    }

    /** `crossSectionHorizontalBox`: a horizontal section's frame is purple, a vertical one's not. */
    @Test
    fun aHorizontalSectionsFrameIsToldApartByItsColour() {
        val horizontalFrame = SexyTopoColours.crossSectionHorizontalFrame.toArgb() and 0xFFFFFF
        val verticalFrame = SexyTopoColours.crossSectionFrame.toArgb() and 0xFFFFFF

        val horizontal = elevationWith(CrossSection.Orientation.HORIZONTAL)
        val vertical = elevationWith(CrossSection.Orientation.VERTICAL)

        assertTrue(horizontal.count(horizontalFrame) > 100, "no purple frame on a horizontal one")
        assertEquals(0, horizontal.count(verticalFrame), "a horizontal one drawn in green")
        assertTrue(vertical.count(verticalFrame) > 100, "no green frame on a vertical one")
        assertEquals(0, vertical.count(horizontalFrame), "a vertical one drawn in purple")
    }

    // The survey settings: a scale for each sketch

    @Test
    fun eachSketchKeepsItsOwnCrossSectionScale() {
        val survey = passage()
        survey.isSaved = true

        assertTrue(applyCrossSectionScales(survey, parseCrossSectionScale("2"), parseCrossSectionScale("3,5")))

        assertEquals(2f, survey.planSketch.crossSectionScale)
        assertEquals(3.5f, survey.elevationSketch.crossSectionScale)
        assertFalse(survey.isSaved)
    }

    @Test
    fun anUnchangedScaleLeavesTheSurveySaved() {
        val survey = passage()
        survey.isSaved = true

        assertFalse(applyCrossSectionScales(survey, 1f, 1f))
        assertTrue(survey.isSaved)
        assertNull(parseCrossSectionScale("0"))
        assertNull(parseCrossSectionScale("big"))
    }
}
