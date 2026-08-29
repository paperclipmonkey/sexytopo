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
import androidx.compose.ui.unit.sp
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
    modifier: Modifier = Modifier,
    tool: SketchTool = SketchTool.MOVE,
    revision: Int = 0,
    onSketchEdit: () -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = LocalAppFontFamily.current

    // Reprojecting is pure and cheap; recompute when the survey, projection or sketch changes.
    val scene = remember(survey, projection, revision) { SurveyScene.from(survey, projection) }

    val viewport = remember(survey, projection) { SketchViewport() }
    val fit = remember(survey, projection) { FitOnce() }

    // The viewport and the editor are plain objects, not Compose state, so a gesture that changes
    // one has to say so. Two counters rather than one: a stroke in progress only needs a repaint,
    // while a committed edit needs the scene (and its bounds) rebuilt.
    var viewTick by remember { mutableIntStateOf(0) }
    var strokeTick by remember { mutableIntStateOf(0) }

    val gestures =
        when (tool) {
            SketchTool.MOVE ->
                Modifier.pointerInput(scene) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        // Zoom about the pinch centre first, then pan, so the point under the
                        // fingers stays under them. adjustZoomBy refuses to leave the shared
                        // viewport's zoom range rather than clamping, exactly as the Java does.
                        viewport.adjustZoomBy(zoomChange, centroid.toCoord2D())
                        viewport.panBy(panChange.toCoord2D())
                        viewTick++
                    }
                }

            SketchTool.ERASE ->
                // A tap, not a drag: the Android app erases on touch-down only, so the eraser is a
                // tapping tool rather than a rubbing one. Reproduced deliberately - rubbing would
                // feel better but would diverge from what a surveyor's muscle memory expects.
                Modifier.pointerInput(scene) {
                    detectTapGestures { offset ->
                        val erased =
                            editor.eraseAt(
                                point = viewport.toSurvey(offset),
                                toleranceInMetres =
                                    viewport.toSurveyDistance(SketchDefaults.DELETE_DETAILS_WITHIN_DP),
                                pixelsPerMetre = viewport.pixelsPerMetre,
                                showCrossSections = options.showSketch,
                            )
                        if (erased) onSketchEdit()
                    }
                }

            else ->
                Modifier.pointerInput(scene) {
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

    Box(modifier = modifier.then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            // Read both counters so a gesture repaints; the values themselves are not used.
            @Suppress("UNUSED_EXPRESSION")
            viewTick
            @Suppress("UNUSED_EXPRESSION")
            strokeTick

            // Fit here rather than from onSizeChanged: this is the first moment the real size is
            // known, and in the single-frame headless render there is no later moment at all.
            if (!fit.done && size.width > 0f && size.height > 0f) {
                viewport.fitTo(scene.bounds, size.width, size.height)
                fit.done = true
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
    val bounds: Bounds,
) {
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

            val bounds =
                Bounds.of(
                    buildList {
                        stations.forEach { add(it.second) }
                        legs.forEach { add(it.first); add(it.second) }
                        splays.forEach { add(it.first); add(it.second) }
                        sketch.pathDetails.forEach { addAll(it.path) }
                        sketch.textDetails.forEach { add(it.position) }
                        sketch.symbolDetails.forEach { add(it.position) }
                        sketch.crossSectionDetails.forEach { add(it.position) }
                    },
                )

            return SurveyScene(stations, legs, splays, sketch, bounds)
        }
    }
}

class DisplayOptions(
    val showSplays: Boolean = true,
    val showSketch: Boolean = true,
    val showStationLabels: Boolean = true,
    val darkMode: Boolean = false,
)

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
)

private val LightPalette =
    Palette(
        background = Color(0xFFF7F8F4),
        centreline = Color(0xFF1C2624),
        splay = Color(0xFFB6C0BA),
        station = Color(0xFFB23327),
        stationLabel = Color(0xFF5A6A66),
        symbol = Color(0xFF2C6B5F),
        crossSection = Color(0xFF92640A),
        scaleBar = Color(0xFF5A6A66),
    )

private val DarkPalette =
    Palette(
        background = Color(0xFF121715),
        centreline = Color(0xFFE4E9E4),
        splay = Color(0xFF44514C),
        station = Color(0xFFE06A5C),
        stationLabel = Color(0xFF9FADA7),
        symbol = Color(0xFF5FB3A1),
        crossSection = Color(0xFFD9A84E),
        scaleBar = Color(0xFF9FADA7),
    )
