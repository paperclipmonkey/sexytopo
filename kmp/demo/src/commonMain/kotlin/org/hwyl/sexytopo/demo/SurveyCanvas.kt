package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The survey drawing surface, written once in Compose Multiplatform and rendered by Skia on every
 * platform — iOS included.
 *
 * This is the piece the feasibility study called the hard part: the Android app's equivalent is
 * `control/graph/GraphView`, a 2,199-line custom Android View. Its *drawing* is Android-specific,
 * but the geometry it draws is not, which is why the maths came across untouched and only this
 * layer had to be rewritten.
 *
 * What is here: centreline, splays, stations and labels, sketch paths, scale bar, pan and zoom.
 * What is not: the drawing tools, symbol artwork, cross-sections and undo. See the module README.
 */
@Composable
fun SurveyCanvas(
    survey: Survey,
    projection: Projection2D,
    options: DisplayOptions,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    // Reprojecting is cheap and pure; recompute only when the survey or projection changes.
    val scene = remember(survey, projection) { SurveyScene.from(survey, projection) }

    var zoom by remember(scene) { mutableFloatStateOf(1f) }
    var pan by remember(scene) { mutableStateOf(Offset.Zero) }

    Box(
        modifier =
            modifier.pointerInput(scene) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(0.2f, 40f)
                    pan += panChange
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawSurvey(scene, options, zoom, pan, textMeasurer)
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
    val stationCount: Int,
    val legCount: Int,
    val splayCount: Int,
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

            val bounds = Bounds.of(
                buildList {
                    stations.forEach { add(it.second) }
                    legs.forEach { add(it.first); add(it.second) }
                    splays.forEach { add(it.first); add(it.second) }
                    sketch.pathDetails.forEach { addAll(it.path) }
                    sketch.textDetails.forEach { add(it.position) }
                    sketch.symbolDetails.forEach { add(it.position) }
                },
            )

            return SurveyScene(
                stations = stations,
                legs = legs,
                splays = splays,
                sketch = sketch,
                bounds = bounds,
                stationCount = stations.size,
                legCount = legs.size,
                splayCount = splays.size,
            )
        }
    }
}

class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = max(maxX - minX, 0.001f)
    val height: Float get() = max(maxY - minY, 0.001f)
    val centreX: Float get() = (minX + maxX) / 2
    val centreY: Float get() = (minY + maxY) / 2

    companion object {
        fun of(points: List<Coord2D>): Bounds {
            if (points.isEmpty()) return Bounds(-1f, -1f, 1f, 1f)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in points) {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            return Bounds(minX, minY, maxX, maxY)
        }
    }
}

class DisplayOptions(
    val showSplays: Boolean = true,
    val showSketch: Boolean = true,
    val showStationLabels: Boolean = true,
    val darkMode: Boolean = false,
)

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawSurvey(
    scene: SurveyScene,
    options: DisplayOptions,
    zoom: Float,
    pan: Offset,
    textMeasurer: TextMeasurer,
) {
    val palette = if (options.darkMode) DarkPalette else LightPalette

    drawRect(palette.background)

    // Fit the survey to the viewport, then apply the user's pan and zoom on top.
    val padding = 48f
    val fit =
        min(
            (size.width - padding * 2) / scene.bounds.width,
            (size.height - padding * 2) / scene.bounds.height,
        )
    val pixelsPerMetre = fit * zoom

    fun project(coord: Coord2D): Offset =
        Offset(
            (coord.x - scene.bounds.centreX) * pixelsPerMetre + size.width / 2 + pan.x,
            (coord.y - scene.bounds.centreY) * pixelsPerMetre + size.height / 2 + pan.y,
        )

    if (options.showSplays) {
        for ((start, end) in scene.splays) {
            drawLine(
                color = palette.splay,
                start = project(start),
                end = project(end),
                strokeWidth = 1f,
                cap = StrokeCap.Round,
            )
        }
    }

    if (options.showSketch) {
        for (detail in scene.sketch.pathDetails) {
            if (detail.path.size < 2) continue
            val colour = detail.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            val path = Path()
            val first = project(detail.path.first())
            path.moveTo(first.x, first.y)
            for (i in 1 until detail.path.size) {
                val point = project(detail.path[i])
                path.lineTo(point.x, point.y)
            }
            drawPath(path, Color(colour.intValue), style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
    }

    // Centreline last of the lines, so it reads on top of the sketch.
    for ((start, end) in scene.legs) {
        drawLine(
            color = palette.centreline,
            start = project(start),
            end = project(end),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round,
        )
    }

    for ((name, coord) in scene.stations) {
        val centre = project(coord)
        drawCircle(palette.station, radius = 3.5f, center = centre)
        if (options.showStationLabels && zoom > 0.75f) {
            val layout =
                textMeasurer.measure(name, TextStyle(color = palette.stationLabel, fontSize = 9.sp))
            drawText(layout, topLeft = Offset(centre.x + 5f, centre.y - 14f))
        }
    }

    if (options.showSketch) {
        for (label in scene.sketch.textDetails) {
            val colour = label.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            val position = project(label.position)
            val layout =
                textMeasurer.measure(
                    label.text,
                    TextStyle(color = Color(colour.intValue), fontSize = 12.sp),
                )
            drawText(layout, topLeft = position)
        }

        // Symbol artwork lives in the Android app's SVG assets, which this proof of concept does
        // not carry; a symbol is drawn as a marked point so its placement is still visible.
        for (symbol in scene.sketch.symbolDetails) {
            val position = project(symbol.position)
            drawCircle(palette.symbol, radius = 4f, center = position, style = Stroke(width = 1.5f))
        }
    }

    drawScaleBar(pixelsPerMetre, palette, textMeasurer)
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawScaleBar(
    pixelsPerMetre: Float,
    palette: Palette,
    textMeasurer: TextMeasurer,
) {
    // Choose a round number of metres that lands near 120px.
    val targetPixels = 120f
    val rawMetres = targetPixels / pixelsPerMetre
    val magnitude = 10f.pow(kotlin.math.floor(kotlin.math.log10(rawMetres.toDouble())).toFloat())
    val metres =
        listOf(1f, 2f, 5f, 10f)
            .map { it * magnitude }
            .minByOrNull { abs(it - rawMetres) } ?: rawMetres

    val barPixels = metres * pixelsPerMetre
    val left = 24f
    val bottom = size.height - 24f

    drawLine(palette.scaleBar, Offset(left, bottom), Offset(left + barPixels, bottom), 2f)
    drawLine(palette.scaleBar, Offset(left, bottom - 5f), Offset(left, bottom + 5f), 2f)
    drawLine(
        palette.scaleBar,
        Offset(left + barPixels, bottom - 5f),
        Offset(left + barPixels, bottom + 5f),
        2f,
    )

    val label = if (metres >= 1f) "${metres.roundToInt()} m" else "$metres m"
    val layout = textMeasurer.measure(label, TextStyle(color = palette.scaleBar, fontSize = 11.sp))
    drawText(layout, topLeft = Offset(left, bottom - 24f))
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
