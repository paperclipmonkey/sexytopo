package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.math.getDistance
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchDefaults
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The survey drawing surface, written once in Compose Multiplatform and rendered by Skia on every
 * platform — iOS included.
 *
 * The Android app's equivalent is `control/graph/GraphView`, a 2,199-line custom Android View. Very
 * little of what it does is actually Android-specific, and this file is the evidence: the tool
 * model, the viewport, the hit-testing, the undo stack, the stroke simplification and the eraser's
 * split-rather-than-delete behaviour are all in the shared module, ported from that class and
 * tested on the JVM and on Kotlin/Wasm. What is left here is drawing and touch plumbing.
 *
 * Strokes are captured in survey metres, never in pixels, so a sketch keeps its meaning across zoom
 * levels and round-trips through the shared JSON format unchanged.
 */
@Composable
fun SurveyCanvas(
    survey: Survey,
    projection: Projection2D,
    options: DisplayOptions,
    editor: SketchEditor,
    canvas: CanvasController,
    modifier: Modifier = Modifier,
    tool: SketchTool = SketchTool.MOVE,
    revision: Int = 0,
    onSketchEdit: () -> Unit = {},
    /** Returns true if the station was taken as the new active one. */
    onSelectStation: (String) -> Boolean = { false },
    /**
     * Where a label was asked for, in survey coordinates, together with the on-screen text size to
     * use at the current zoom.
     *
     * A callback rather than a dialog raised from here, because typing needs a keyboard and the
     * canvas is one composable deep inside a layout that has none. The host puts the dialog up and
     * calls [SketchEditor.addText] when the surveyor has typed something.
     */
    onPlaceLabel: (Coord2D, Float) -> Unit = { _, _ -> },
) {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = LocalAppFontFamily.current

    // Reprojecting is pure and cheap; recompute when the survey, projection or sketch changes.
    val scene = remember(survey, projection, revision) { SurveyScene.from(survey, projection) }

    val viewport = canvas.viewport
    val fit = canvas.fit

    // The editor is a plain object, not Compose state, so a stroke in progress has to say when it
    // needs repainting. The viewport says so through the controller's own revision.
    var strokeTick by remember { mutableIntStateOf(0) }

    // Keyed on `tool` as well as `scene`, and that is the whole reason the toolbar works.
    //
    // `Modifier.pointerInput` runs a suspending gesture loop that restarts only when one of its
    // keys changes. Every branch below sits at the same position in the modifier chain, so Compose
    // sees one node: keyed on `scene` alone, picking a new tool swapped the *lambda* and left the
    // previously started loop running, and the canvas went on panning. It looked as though tool
    // selection worked, because switching between the table and a sketch rebuilds `scene` and the
    // loop restarted with whatever tool was current by then - so the fix was always one view
    // switch away, which is exactly how the bug was reported.
    val gestures =
        when (tool) {
            SketchTool.MOVE ->
                Modifier.pointerInput(scene, tool) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        // Zoom about the pinch centre first, then pan, so the point under the
                        // fingers stays under them. adjustZoomBy refuses to leave the shared
                        // viewport's zoom range rather than clamping, exactly as the Java does.
                        canvas.transformBy(
                            centroid.toCoord2D(),
                            panChange.toCoord2D(),
                            zoomChange,
                        )
                    }
                }

            SketchTool.TEXT ->
                Modifier.pointerInput(scene, tool) {
                    // Tap where the label goes. The size is converted from sp on screen into
                    // metres in the survey, exactly as the symbol tool does, so a label keeps its
                    // physical size in the cave rather than its size on the screen it was placed
                    // on — zoom in afterwards and it grows with the passage, which is what makes
                    // it a label on the drawing rather than an annotation on the display.
                    detectTapGestures { offset ->
                        onPlaceLabel(
                            viewport.toSurvey(offset),
                            viewport.toSurveyDistance(SketchDefaults.TEXT_STARTING_SIZE_SP.sp.toPx()),
                        )
                    }
                }

            SketchTool.SELECT ->
                Modifier.pointerInput(scene, tool) {
                    // Tap a station to make it the one the next leg starts from. The reach is the
                    // app's own SELECTION_SENSITIVITY_DP, which is much larger than the eraser's -
                    // a station is a 10dp dot and a cold finger is not precise.
                    detectTapGestures { offset ->
                        val reach =
                            viewport.toSurveyDistance(
                                SketchDefaults.SELECTION_SENSITIVITY_DP.dp.toPx(),
                            )
                        val chosen = scene.stationNearest(viewport.toSurvey(offset), reach)
                        if (chosen != null && onSelectStation(chosen)) onSketchEdit()
                    }
                }

            SketchTool.ERASE ->
                Modifier.pointerInput(scene, tool) {
                    // onPress, not onTap: the Android app erases on touch-*down*, so the eraser is
                    // a tapping tool rather than a rubbing one. onTap would additionally not fire
                    // at all if the finger moved before lifting, so a press-drag-release - which is
                    // exactly what erasing feels like it should be - would quietly erase nothing.
                    detectTapGestures(
                        onPress = { offset ->
                            val erased =
                                editor.eraseAt(
                                    point = viewport.toSurvey(offset),
                                    // The constant is in dp; the viewport thinks in pixels. Passing
                                    // it straight through made the eraser's reach depend on the
                                    // display density, and drew a circle of the wrong size to say
                                    // so.
                                    toleranceInMetres =
                                        viewport.toSurveyDistance(
                                            SketchDefaults.DELETE_DETAILS_WITHIN_DP.dp.toPx(),
                                        ),
                                    pixelsPerMetre = viewport.pixelsPerMetre,
                                    showCrossSections = options.showSketch,
                                )
                            if (erased) onSketchEdit()
                        },
                    )
                }

            else ->
                Modifier.pointerInput(scene, tool) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            editor.startPath(viewport.toSurvey(offset))
                            strokeTick++
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            editor.extendPath(viewport.toSurvey(change.position))
                            strokeTick++
                        },
                        onDragEnd = {
                            // finishPath simplifies the stroke and pushes one undo step; a stroke
                            // of fewer than two points is still committed, as in the original,
                            // because a tap is how you draw a dot.
                            editor.finishPath()
                            onSketchEdit()
                        },
                        onDragCancel = {
                            editor.abandonPath()
                            strokeTick++
                        },
                    )
                }
        }

    // Clipping is stated rather than inherited. `drawGrid` starts its first line at
    // `floor(topLeft.y / spacing) * spacing`, which is by definition at or above the top of the
    // view - the same arithmetic GraphView uses, where an Android View's own clip makes it free. It
    // is free here too today: render the demo with this modifier removed and that line still does
    // not appear, so something up the tree is already clipping. But "something up the tree" is a
    // layout change away from not being true, and the failure it would produce is the cave painting
    // over the app bar. One modifier is a cheap way not to depend on an ancestor for that.
    Box(modifier = modifier.clipToBounds().then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            // Read both counters so a gesture or a toolbar button repaints; the values themselves
            // are not used.
            @Suppress("UNUSED_EXPRESSION")
            canvas.revision
            @Suppress("UNUSED_EXPRESSION")
            strokeTick

            canvas.noteViewSize(size.width, size.height)

            // Fit here rather than from onSizeChanged: this is the first moment the real size is
            // known, and in the single-frame headless render there is no later moment at all.
            //
            // And re-fit as the survey grows, until the surveyor pans or zooms. Live surveying
            // starts from a single station and adds a leg every few readings; a fit that happened
            // once would leave the cave walking off the edge of the screen within a minute, which
            // is precisely the moment somebody is watching. Once they have moved the view
            // themselves it is theirs, and re-fitting under them would be rude.
            //
            // The trigger is the *centreline's* extent, not the whole scene's. A drawn stroke
            // enlarges the scene too, and re-framing the view because somebody drew near the edge
            // would move the paper out from under the pen.
            if (fit.shouldFitTo(scene.surveyBounds) && size.width > 0f && size.height > 0f) {
                viewport.fitTo(scene.bounds, size.width, size.height)
                fit.noteFitted(scene.surveyBounds)
            }

            drawSurvey(scene, options, viewport, textMeasurer, fontFamily, tool)
        }
    }
}

/** Everything the canvas needs, precomputed in survey space. */
class SurveyScene private constructor(
    val stations: List<Pair<String, Coord2D>>,
    val legs: List<Pair<Coord2D, Coord2D>>,
    val splays: List<Pair<Coord2D, Coord2D>>,
    /** The live sketch, not a copy: a stroke in progress grows in place and draws as it goes. */
    val sketch: Sketch,
    /** Which station the next leg will start from; drawn with the app's amber brackets. */
    val activeStationName: String,
    /** Everything drawn, centreline and ink alike — what the opening zoom is fitted to. */
    val bounds: Bounds,
    /**
     * The centreline alone.
     *
     * Kept apart from [bounds] because it answers a different question: "has the *survey* grown",
     * which is worth re-framing the view for, as against "has anything on screen moved", which
     * includes the stroke currently under somebody's finger and is not.
     */
    val surveyBounds: Bounds,
) {
    /**
     * The nearest station within [reach] metres of [point], or null.
     *
     * Nearest rather than first: at a junction several stations sit within a finger's width of each
     * other, and picking whichever happened to come first out of the projection would make the
     * choice feel arbitrary.
     */
    fun stationNearest(point: Coord2D, reach: Float): String? {
        var best: String? = null
        var bestDistance = reach
        for ((name, coord) in stations) {
            val distance = getDistance(point, coord)
            if (distance <= bestDistance) {
                best = name
                bestDistance = distance
            }
        }
        return best
    }

    companion object {
        fun from(survey: Survey, projection: Projection2D): SurveyScene {
            val space = projection.project(survey)

            val stations = space.stationMap.map { (station, coord) -> station.name to coord }
            val legs = mutableListOf<Pair<Coord2D, Coord2D>>()
            val splays = mutableListOf<Pair<Coord2D, Coord2D>>()
            for ((leg, line) in space.legMap) {
                val segment = line.start to line.end
                if (leg.hasDestination()) legs.add(segment) else splays.add(segment)
            }

            val sketch = survey.getSketch(projection)

            val surveyPoints = buildList {
                stations.forEach { add(it.second) }
                legs.forEach { add(it.first); add(it.second) }
                splays.forEach { add(it.first); add(it.second) }
            }
            val surveyBounds = Bounds.of(surveyPoints)

            val bounds =
                Bounds.of(
                    buildList {
                        addAll(surveyPoints)
                        sketch.pathDetails.forEach { addAll(it.path) }
                        sketch.textDetails.forEach { add(it.position) }
                        sketch.symbolDetails.forEach { add(it.position) }
                        sketch.crossSectionDetails.forEach { add(it.position) }
                    },
                )

            return SurveyScene(
                stations,
                legs,
                splays,
                sketch,
                survey.activeStation.name,
                bounds,
                surveyBounds,
            )
        }
    }
}

class DisplayOptions(
    val showSplays: Boolean = true,
    val showSketch: Boolean = true,
    val showStationLabels: Boolean = true,
    val showGrid: Boolean = true,
    val darkMode: Boolean = false,
)

/**
 * The four amber corner brackets the app puts round the station the next leg will start from.
 *
 * Ported from `GraphView.highlightActiveStation`, geometry and all: a box 1.1 times the station
 * diameter, with a gap of a third of that left open in the middle of each side, so what is drawn is
 * four corners rather than a square. It reads as a viewfinder, which is exactly right — it is
 * showing you where the survey is about to grow from — and it is the single most recognisable thing
 * on the screen after the red centreline. Leaving it out made the plan look subtly wrong in a way
 * that was hard to name.
 */
private fun DrawScope.drawActiveStationHighlight(centre: Offset, palette: Palette) {
    val diameter = SketchDefaults.STATION_CROSS_DIAMETER_DP.dp.toPx() * 1.1f
    val gap = diameter / 3f
    val half = diameter / 2f
    val arm = (diameter - gap) / 2f
    val stroke = SketchDefaults.STATION_STROKE_WIDTH_DP.dp.toPx() * 1.25f

    val left = centre.x - half
    val right = centre.x + half
    val top = centre.y - half
    val bottom = centre.y + half

    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(palette.activeStation, Offset(x, y), Offset(x + dx * arm, y), stroke)
        drawLine(palette.activeStation, Offset(x, y), Offset(x, y + dy * arm), stroke)
    }

    corner(left, top, 1f, 1f)
    corner(right, top, -1f, 1f)
    corner(left, bottom, 1f, -1f)
    corner(right, bottom, -1f, -1f)
}

/**
 * The metre grid the Android app draws under everything else.
 *
 * The spacing comes from [SketchViewport.minorGridBoxSizeMetres], which is the ported rule: one
 * metre when zoomed in past 15 pixels per metre, ten metres past 2, a hundred metres below that.
 * So the grid tells you the scale at a glance without reading the bar, which is the whole point of
 * it underground.
 *
 * Lines are drawn on whole multiples of the spacing in *survey* coordinates rather than stepping
 * across the screen, so they stay pinned to the cave as the view is panned instead of crawling.
 */
private fun DrawScope.drawGrid(viewport: SketchViewport, palette: Palette) {
    val spacing = viewport.minorGridBoxSizeMetres().toFloat()
    if (spacing <= 0f) return

    val topLeft = viewport.toSurvey(Offset.Zero)
    val bottomRight = viewport.toSurvey(Offset(size.width, size.height))

    // A guard rather than an optimisation: at a very low zoom the loop below would run for
    // millions of lines nobody could see.
    val columns = (bottomRight.x - topLeft.x) / spacing
    val rows = (bottomRight.y - topLeft.y) / spacing
    if (!columns.isFinite() || !rows.isFinite() || columns > 400f || rows > 400f) return

    var x = floor(topLeft.x / spacing) * spacing
    while (x <= bottomRight.x) {
        val screenX = viewport.toScreen(Coord2D(x, topLeft.y)).x
        drawLine(palette.grid, Offset(screenX, 0f), Offset(screenX, size.height), 1f)
        x += spacing
    }

    var y = floor(topLeft.y / spacing) * spacing
    while (y <= bottomRight.y) {
        val screenY = viewport.toScreen(Coord2D(topLeft.x, y)).y
        drawLine(palette.grid, Offset(0f, screenY), Offset(size.width, screenY), 1f)
        y += spacing
    }
}

/**
 * Below this zoom the station names are dropped.
 *
 * Deliberately generous. Legs are commonly 5-10 m, so at this scale adjacent labels are only about
 * 10 px apart and will overlap here and there — but a plan with no station names on it is much less
 * use to a caver than a plan with a few crowded ones, and the Android app makes the same trade.
 */
private const val LABEL_VISIBILITY_PIXELS_PER_METRE = 1.5f

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawSurvey(
    scene: SurveyScene,
    options: DisplayOptions,
    viewport: SketchViewport,
    textMeasurer: TextMeasurer,
    fontFamily: FontFamily,
    tool: SketchTool,
) {
    val palette = if (options.darkMode) DarkPalette else LightPalette

    drawRect(palette.background)

    fun project(coord: Coord2D): Offset = viewport.toScreen(coord)

    if (options.showGrid) {
        drawGrid(viewport, palette)
    }

    if (options.showSplays) {
        for ((start, end) in scene.splays) {
            drawLine(palette.splay, project(start), project(end), 1f, StrokeCap.Round)
        }
    }

    if (options.showSketch) {
        for (detail in scene.sketch.pathDetails) {
            if (detail.path.size < 2) continue
            val colour = detail.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            drawPolyline(detail.path.map(::project), Color(colour.intValue), 2f)
        }
    }

    // Centreline on top of the sketch, as in the original.
    for ((start, end) in scene.legs) {
        drawLine(palette.centreline, project(start), project(end), 2.5f, StrokeCap.Round)
    }

    for ((name, coord) in scene.stations) {
        val centre = project(coord)
        drawCircle(palette.station, radius = 3.5f, center = centre)
        if (name == scene.activeStationName) {
            drawActiveStationHighlight(centre, palette)
        }
        if (options.showStationLabels &&
            viewport.pixelsPerMetre > LABEL_VISIBILITY_PIXELS_PER_METRE
        ) {
            val layout =
                textMeasurer.measure(
                    name,
                    TextStyle(color = palette.stationLabel, fontSize = 9.sp, fontFamily = fontFamily),
                )
            drawText(layout, topLeft = Offset(centre.x + 5f, centre.y - 14f))
        }
    }

    if (options.showSketch) {
        for (label in scene.sketch.textDetails) {
            val colour = label.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            val layout =
                textMeasurer.measure(
                    label.text,
                    TextStyle(color = Color(colour.intValue), fontSize = 12.sp, fontFamily = fontFamily),
                )
            drawText(layout, topLeft = project(label.position))
        }

        // Symbol artwork lives in the Android app's SVG assets, which this port does not carry;
        // a symbol is drawn as a marked point so its placement is still visible.
        for (symbol in scene.sketch.symbolDetails) {
            drawCircle(palette.symbol, radius = 4f, center = project(symbol.position), style = Stroke(1.5f))
        }

        // Cross-sections: the passage profile at a station, drawn where it was placed on the plan.
        val sectionScale = scene.sketch.crossSectionScale
        for (detail in scene.sketch.crossSectionDetails) {
            val centre = project(detail.position)
            for (line in detail.crossSection.getProjection().legMap.values) {
                val end = line.end.scale(sectionScale)
                drawLine(
                    palette.crossSection,
                    centre,
                    Offset(
                        centre.x + end.x * viewport.pixelsPerMetre,
                        centre.y + end.y * viewport.pixelsPerMetre,
                    ),
                    1.2f,
                    StrokeCap.Round,
                )
            }
            drawCircle(palette.crossSection, radius = 2.5f, center = centre)
        }
    }

    if (tool == SketchTool.ERASE) {
        // The eraser's real reach, drawn at the size a tap would actually clear.
        drawCircle(
            palette.station,
            radius = SketchDefaults.DELETE_DETAILS_WITHIN_DP,
            center = Offset(size.width - 40f, 40f),
            style = Stroke(1.5f),
        )
    }

    drawScaleBar(viewport.pixelsPerMetre, palette, textMeasurer, fontFamily)
}

private fun DrawScope.drawPolyline(points: List<Offset>, colour: Color, width: Float) {
    if (points.size < 2) return
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    drawPath(path, colour, style = Stroke(width = width, cap = StrokeCap.Round))
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawScaleBar(
    pixelsPerMetre: Float,
    palette: Palette,
    textMeasurer: TextMeasurer,
    fontFamily: FontFamily,
) {
    if (!pixelsPerMetre.isFinite() || pixelsPerMetre <= 0f) return

    // Choose a round number of metres landing near 120px.
    val rawMetres = 120f / pixelsPerMetre
    if (!rawMetres.isFinite() || rawMetres <= 0f) return
    val magnitude = 10f.pow(floor(log10(rawMetres)))
    val metres =
        listOf(1f, 2f, 5f, 10f).map { it * magnitude }.minByOrNull { abs(it - rawMetres) } ?: return
    if (!metres.isFinite() || metres <= 0f) return

    val barPixels = metres * pixelsPerMetre
    if (!barPixels.isFinite() || barPixels > size.width) return
    val left = 24f
    val bottom = size.height - 24f

    drawLine(palette.scaleBar, Offset(left, bottom), Offset(left + barPixels, bottom), 2f)
    drawLine(palette.scaleBar, Offset(left, bottom - 5f), Offset(left, bottom + 5f), 2f)
    drawLine(palette.scaleBar, Offset(left + barPixels, bottom - 5f), Offset(left + barPixels, bottom + 5f), 2f)

    val label = if (metres >= 1f) "${metres.roundToInt()} m" else "${(metres * 100).roundToInt()} cm"
    val layout =
        textMeasurer.measure(label, TextStyle(color = palette.scaleBar, fontSize = 11.sp, fontFamily = fontFamily))
    drawText(layout, topLeft = Offset(left, bottom - 24f))
}

class Palette(
    val background: Color,
    val centreline: Color,
    val splay: Color,
    val station: Color,
    val stationLabel: Color,
    val symbol: Color,
    val crossSection: Color,
    val scaleBar: Color,
    val grid: Color,
    val activeStation: Color,
)

/**
 * The Android app's own graph colours, not a reinterpretation of them — see [SexyTopoColours].
 *
 * The red centreline is the surprising one, and the one that most makes a screenshot recognisable:
 * SexyTopo draws legs in pure red and splays in a light red, not in the ink-on-paper greys most
 * survey software uses.
 */
private val LightPalette =
    Palette(
        background = SexyTopoColours.canvasBackground,
        centreline = SexyTopoColours.leg,
        splay = SexyTopoColours.splay,
        station = SexyTopoColours.station,
        // `stationPaint`, not `legendPaint`: red numerals beside red dots. GraphView.java:1635.
        stationLabel = SexyTopoColours.station,
        symbol = SexyTopoColours.crossSectionIndicator,
        crossSection = SexyTopoColours.crossSectionConnection,
        scaleBar = SexyTopoColours.legend,
        grid = SexyTopoColours.grid,
        activeStation = SexyTopoColours.activeStation,
    )

private val DarkPalette =
    Palette(
        background = SexyTopoColours.canvasBackgroundNight,
        centreline = SexyTopoColours.legNight,
        splay = SexyTopoColours.splayNight,
        station = SexyTopoColours.stationNight,
        stationLabel = SexyTopoColours.stationNight,
        symbol = SexyTopoColours.crossSectionIndicatorNight,
        crossSection = SexyTopoColours.crossSectionConnection,
        scaleBar = SexyTopoColours.legendNight,
        grid = SexyTopoColours.gridNight,
        activeStation = SexyTopoColours.activeStationNight,
    )
