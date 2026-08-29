package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** What a drag does on the canvas. */
enum class CanvasTool(val displayName: String) {
    PAN("Move"),
    DRAW("Draw"),
    ERASE("Erase"),
}

/**
 * The survey drawing surface, written once in Compose Multiplatform and rendered by Skia on every
 * platform — iOS included.
 *
 * The Android app's equivalent is `control/graph/GraphView`, a 2,199-line custom Android View. Its
 * drawing and gesture handling are Android-specific, but the geometry is not, which is why the
 * maths came across untouched and only this layer had to be rewritten.
 *
 * Strokes are captured in survey metres via [Viewport.toSurvey], never in pixels, so a sketch keeps
 * its meaning across zoom levels and round-trips through the shared JSON format unchanged.
 */
@Composable
fun SurveyCanvas(
    survey: Survey,
    projection: Projection2D,
    options: DisplayOptions,
    modifier: Modifier = Modifier,
    tool: CanvasTool = CanvasTool.PAN,
    brushColour: Colour = Colour.BLACK,
    revision: Int = 0,
    onSketchEdit: () -> Unit = {},
    history: SketchHistory? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = LocalAppFontFamily.current

    // Reprojecting is pure and cheap; recompute when the survey, projection or sketch changes.
    val scene = remember(survey, projection, revision) { SurveyScene.from(survey, projection) }

    var zoom by remember(survey, projection) { mutableFloatStateOf(1f) }
    var pan by remember(survey, projection) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // The stroke being drawn right now, before it is committed to the sketch.
    var liveStroke by remember { mutableStateOf<List<Coord2D>>(emptyList()) }

    val viewport = Viewport(scene.bounds, canvasSize, zoom, pan)

    val gestures =
        when (tool) {
            CanvasTool.PAN ->
                Modifier.pointerInput(scene) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.2f, 40f)
                        pan += panChange
                    }
                }

            CanvasTool.DRAW ->
                Modifier.pointerInput(scene, brushColour, canvasSize, zoom, pan) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            liveStroke = listOf(viewport.toSurvey(offset))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            liveStroke = liveStroke + viewport.toSurvey(change.position)
                        },
                        onDragEnd = {
                            val stroke = liveStroke
                            liveStroke = emptyList()
                            if (stroke.size >= 2) {
                                val sketch = survey.getSketch(projection)
                                history?.record(sketch)
                                // Simplify on release, as the Android app does, so a stroke is
                                // stored as a handful of points rather than every sampled position.
                                val simplified = simplifyPath(stroke, viewport.toSurveyDistance(1.5f))
                                sketch.pathDetails.add(PathDetail(simplified, brushColour))
                                onSketchEdit()
                            }
                        },
                        onDragCancel = { liveStroke = emptyList() },
                    )
                }

            CanvasTool.ERASE ->
                Modifier.pointerInput(scene, canvasSize, zoom, pan) {
                    var erasedAnything = false
                    detectDragGestures(
                        onDragStart = { offset ->
                            val sketch = survey.getSketch(projection)
                            history?.record(sketch)
                            erasedAnything =
                                eraseAt(sketch, viewport.toSurvey(offset), viewport.toSurveyDistance(ERASER_RADIUS_PX))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val sketch = survey.getSketch(projection)
                            if (eraseAt(
                                    sketch,
                                    viewport.toSurvey(change.position),
                                    viewport.toSurveyDistance(ERASER_RADIUS_PX),
                                )
                            ) {
                                erasedAnything = true
                                onSketchEdit()
                            }
                        },
                        onDragEnd = {
                            if (erasedAnything) onSketchEdit() else history?.undo(survey.getSketch(projection))
                            erasedAnything = false
                        },
                    )
                }
        }

    Box(modifier = modifier.onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }.then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            // Build the viewport from the DrawScope's own size rather than the onSizeChanged
            // state: on the very first frame — and in a single-frame headless render — that
            // callback has not run yet, and the survey would be drawn at 1 pixel per metre.
            drawSurvey(
                scene,
                options,
                Viewport(scene.bounds, size, zoom, pan),
                textMeasurer,
                fontFamily,
                liveStroke,
                brushColour,
                tool,
            )
        }
    }
}

/** Everything the canvas needs, precomputed in survey space. */
class SurveyScene private constructor(
    val stations: List<Pair<String, Coord2D>>,
    val legs: List<Pair<Coord2D, Coord2D>>,
    val splays: List<Pair<Coord2D, Coord2D>>,
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

private const val ERASER_RADIUS_PX = 14f

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawSurvey(
    scene: SurveyScene,
    options: DisplayOptions,
    viewport: Viewport,
    textMeasurer: TextMeasurer,
    fontFamily: FontFamily,
    liveStroke: List<Coord2D>,
    brushColour: Colour,
    tool: CanvasTool,
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
        if (options.showStationLabels && viewport.zoom > 0.75f) {
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
    }

    // The stroke under the stylus right now, drawn unsimplified for immediate feedback.
    if (liveStroke.size >= 2) {
        drawPolyline(liveStroke.map(::project), Color(brushColour.intValue), 2f)
    }

    if (tool == CanvasTool.ERASE) {
        drawCircle(
            palette.station,
            radius = ERASER_RADIUS_PX,
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

/**
 * Douglas-Peucker: drops points that lie within [epsilon] of the line between their neighbours.
 *
 * The Android app simplifies every stroke on release for the same reason — a finger or stylus emits
 * far more positions than the shape needs, and the sketch is persisted as JSON.
 */
fun simplifyPath(points: List<Coord2D>, epsilon: Float): List<Coord2D> {
    if (points.size < 3 || epsilon <= 0f) return points

    var maxDistance = 0f
    var index = 0
    for (i in 1 until points.size - 1) {
        val distance =
            org.hwyl.sexytopo.shared.math.getDistanceFromLine(points[i], points.first(), points.last())
        if (distance > maxDistance) {
            maxDistance = distance
            index = i
        }
    }

    return if (maxDistance > epsilon) {
        val left = simplifyPath(points.subList(0, index + 1), epsilon)
        val right = simplifyPath(points.subList(index, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(points.first(), points.last())
    }
}

class Palette(
    val background: Color,
    val centreline: Color,
    val splay: Color,
    val station: Color,
    val stationLabel: Color,
    val symbol: Color,
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
        scaleBar = Color(0xFF9FADA7),
    )
