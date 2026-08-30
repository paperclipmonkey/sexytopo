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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchDefaults
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import org.hwyl.sexytopo.shared.sketch.findCrossSectionBodyAt
import org.hwyl.sexytopo.shared.survey.CrossSectioner
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
    /** Which symbol the stamp tool places. */
    symbol: Symbol = Symbol.ENTRANCE,
    /**
     * Draw this scene instead of building one from [survey] and [projection].
     *
     * The cross-section editor's surface is the same canvas over a different world: one station at
     * the origin, its splays around it, and the section's own sub-sketch. Everything else — the
     * viewport, the tools, the eraser, the undo stack — is the same code, which is the point.
     */
    sceneOverride: SurveyScene? = null,
    /** Tapping a cross-section body, with the tools the Android app allows it from. */
    onOpenCrossSection: (CrossSectionDetail) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = LocalAppFontFamily.current

    // Density is a composition-local, so it is read here rather than inside the gesture scope,
    // which is not a Density and cannot resolve dp.toPx().
    val symbolSizeInPixels =
        with(LocalDensity.current) { SketchDefaults.SYMBOL_STARTING_SIZE_DP.dp.toPx() }

    // Reprojecting is pure and cheap; recompute when the survey, projection or sketch changes.
    //
    // Not computed at all when the caller has supplied its own scene, and that is load-bearing
    // rather than an optimisation: the cross-section editor passes Projection2D.CROSS_SECTION, and
    // `Survey.getSketch` throws for it — a cross-section is drawn from a station's splays, not from
    // a sketch the survey holds. Building the scene eagerly threw inside the composition, which
    // does not crash the page: the editor simply never appeared and the last frame stayed on
    // screen, so the tool looked as though it did nothing at all.
    val projected =
        remember(survey, projection, revision, sceneOverride == null) {
            if (sceneOverride == null) SurveyScene.from(survey, projection) else null
        }
    val scene = sceneOverride ?: projected!!

    val viewport = canvas.viewport
    val fit = canvas.fit

    // The editor is a plain object, not Compose state, so a stroke in progress has to say when it
    // needs repainting. The viewport says so through the controller's own revision.
    var strokeTick by remember { mutableIntStateOf(0) }

    // The cross-section drag in progress, if any. Read inside the draw block so the preview
    // follows the finger; null between gestures, which is also what says "draw everything
    // normally".
    var sectionDrag by remember { mutableStateOf<SectionDrag?>(null) }

    /** Grab whatever cross-section is under the finger, for a [mode] drag. */
    fun grab(mode: SectionDragMode, at: Coord2D): SectionDrag? {
        val detail = findCrossSectionBodyAt(scene.sketch, at) ?: return null
        return SectionDrag(
            mode = mode,
            detail = detail,
            from = at,
            pivot = scene.positionOf(detail.station.name),
        )
    }

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

            SketchTool.SYMBOL -> {
                // Two detectors, not one. A drag sets the bearing for a directional symbol - a
                // water flow points downstream, a gradient downhill - which is why SymbolDetail
                // carries an angle at all. But detectDragGestures never fires for a tap: it waits
                // for the touch slop to be exceeded, so on its own it silently stamped nothing at
                // all unless the finger moved. The tap detector handles the upright case and
                // cancels itself once a drag starts, so exactly one of them fires.
                fun stamp(at: Offset, angle: Float) {
                    editor.addSymbol(
                        position = viewport.toSurvey(at),
                        symbolName = symbol.therionName,
                        // A fixed size on screen, converted to metres through the current zoom, so
                        // a symbol keeps its size in the cave rather than on the display.
                        size = viewport.toSurveyDistance(symbolSizeInPixels),
                        angle = angle,
                    )
                    onSketchEdit()
                }

                Modifier
                    .pointerInput(scene, tool, symbol) {
                        detectTapGestures { offset -> stamp(offset, 0f) }
                    }
                    .pointerInput(scene, tool, symbol) {
                        var start = Offset.Zero
                        var angle = 0f
                        detectDragGestures(
                            onDragStart = { offset ->
                                start = offset
                                angle = 0f
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                if (symbol.isDirectional) {
                                    angle = bearingOf(change.position - start)
                                }
                            },
                            onDragEnd = { stamp(start, angle) },
                        )
                    }
            }

            SketchTool.MOVE_CROSS_SECTION, SketchTool.ROTATE_CROSS_SECTION -> {
                // One gesture loop for both, because they are the same gesture: press on a
                // section, drag, lift. Only what the drag means differs, and SectionDrag holds
                // that.
                //
                // A drag rather than a tap, deliberately - a section that could be picked up by a
                // tap would be picked up by every stray touch on a drawing covered in them.
                val mode =
                    if (tool == SketchTool.MOVE_CROSS_SECTION) SectionDragMode.MOVE
                    else SectionDragMode.ROTATE

                Modifier.pointerInput(scene, tool) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            sectionDrag = grab(mode, viewport.toSurvey(offset))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            sectionDrag =
                                sectionDrag?.movedTo(viewport.toSurvey(change.position))
                        },
                        onDragEnd = {
                            if (sectionDrag?.commit(editor) == true) onSketchEdit()
                            sectionDrag = null
                        },
                        onDragCancel = { sectionDrag = null },
                    )
                }
            }

            SketchTool.POSITION_CROSS_SECTION ->
                Modifier.pointerInput(scene, tool) {
                    // Tap a station. The Android app splits this in two — a context-menu item that
                    // names the station, then a tap that positions the drawing — because it has a
                    // long-press menu to hang the first half on. One tap does both here: the
                    // nearest station within the app's own selection reach is the subject, and the
                    // point tapped is where the section is drawn, so a surveyor can put it in the
                    // white space beside the passage rather than on top of it.
                    detectTapGestures { offset ->
                        val reach =
                            viewport.toSurveyDistance(
                                SketchDefaults.SELECTION_SENSITIVITY_DP.dp.toPx(),
                            )
                        val where = viewport.toSurvey(offset)
                        val name = scene.stationNearest(where, reach)
                        val station = name?.let { survey.getStationByName(it) }
                        if (station != null) {
                            // The bearing comes from CrossSectioner's own heuristic: bisect the
                            // corner mid-passage, follow the single leg at a dead end, give up and
                            // use north where there is nothing to go on. It is a guess, and
                            // SketchTool.ROTATE_CROSS_SECTION is how a surveyor overrules it.
                            editor.addCrossSection(CrossSectioner.section(survey, station), where)
                            onSketchEdit()
                        }
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
                        val where = viewport.toSurvey(offset)
                        // A cross-section first, as in `GraphView.handleCrossSectionBodyTap`,
                        // which runs before the tool's own handler. A section is parked in clear
                        // space beside the passage, so it is rarely near enough to a station for
                        // this to steal a selection.
                        val section =
                            if (options.showSketch) {
                                findCrossSectionBodyAt(scene.sketch, where)
                            } else {
                                // Invisible sections cannot be tapped: the original's own first
                                // guard, and the obvious one - nothing should open from a tap on
                                // apparently empty paper.
                                null
                            }
                        if (section != null) {
                            onOpenCrossSection(section)
                            return@detectTapGestures
                        }
                        val reach =
                            viewport.toSurveyDistance(
                                SketchDefaults.SELECTION_SENSITIVITY_DP.dp.toPx(),
                            )
                        val chosen = scene.stationNearest(where, reach)
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
                Modifier
                    // A tap on a cross-section opens it, as in the Android app, where the check
                    // runs ahead of every tool but pan and erase. It costs nothing here: the draw
                    // tool is a drag detector, which never fires for a tap, so the two do not
                    // compete for the same gesture.
                    .pointerInput(scene, tool) {
                        detectTapGestures { offset ->
                            if (!options.showSketch) return@detectTapGestures
                            findCrossSectionBodyAt(scene.sketch, viewport.toSurvey(offset))
                                ?.let(onOpenCrossSection)
                        }
                    }
                    .pointerInput(scene, tool) {
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
                                // finishPath simplifies the stroke and pushes one undo step; a
                                // stroke of fewer than two points is still committed, as in the
                                // original, because a tap is how you draw a dot.
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

            drawSurvey(scene, options, viewport, textMeasurer, fontFamily, tool, sectionDrag)
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
    /** Where a station sits in this projection, or null if it is not in it at all. */
    fun positionOf(name: String): Coord2D? =
        stations.firstOrNull { it.first == name }?.second

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

        /**
         * The world inside a cross-section: one station at the origin, its splays around it, and
         * the sub-sketch being drawn into.
         *
         * Ported from `CrossSectionActivity.getProjection` and `CrossSectionView`. The coordinates
         * are the section's own — station-relative, across-passage on x and height on y — not the
         * plan's, which is why the section's [CrossSectionDetail.position] does not appear here at
         * all. Every splay is a ray from the station, so the "legs" list is empty and the passage
         * outline the surveyor is about to draw is the only thing that will join them up.
         *
         * @param working the sketch being edited, which is a copy of the section's own — see
         *   [Sketch.copy].
         */
        fun forCrossSection(detail: CrossSectionDetail, working: Sketch): SurveyScene {
            val projection = detail.crossSection.getProjection()
            val station = detail.station
            val splays =
                projection.legMap.values.map { line -> line.start to line.end }

            val points = buildList {
                add(Coord2D.ORIGIN)
                splays.forEach { add(it.first); add(it.second) }
            }

            return SurveyScene(
                stations = listOf(station.name to Coord2D.ORIGIN),
                legs = emptyList(),
                splays = splays,
                sketch = working,
                // The station is highlighted here for the same reason it is on the plan: it is
                // the fixed point everything in this view is measured from.
                activeStationName = station.name,
                bounds = crossSectionFitBounds(splays),
                surveyBounds = Bounds.of(points),
            )
        }
    }
}

/**
 * The box the cross-section editor opens onto.
 *
 * Ported from `CrossSectionView.autoFitZoom`, which sets the zoom so the longest splay occupies
 * `AUTO_FIT_SCREEN_FRACTION` — 0.4 — of the smaller screen dimension. That fraction is doing real
 * work: the wall outline is drawn *outside* the splay ends, so a view fitted tightly to the splays
 * would open with nowhere to draw it.
 *
 * Expressed as a box rather than as a zoom because that is what this canvas's fit takes, and it
 * comes to the same thing: fitting a box `longestSplay / 0.4` across into the smaller dimension
 * gives exactly the Java's pixels per metre.
 *
 * A station with no splays at all — booked with no wall shots — gets a fixed few metres instead.
 * The Java falls back to a fixed 60 pixels per metre there, which means a different number of
 * metres on every phone; a fixed extent means the same passage-sized area on all of them.
 */
internal fun crossSectionFitBounds(splays: List<Pair<Coord2D, Coord2D>>): Bounds {
    var longest = 0f
    for ((start, end) in splays) {
        longest = maxOf(longest, getDistance(start, end))
    }
    val halfExtent =
        if (longest <= 0f) {
            EMPTY_CROSS_SECTION_HALF_EXTENT
        } else {
            longest / (2f * CROSS_SECTION_SCREEN_FRACTION)
        }
    return Bounds(-halfExtent, -halfExtent, halfExtent, halfExtent)
}

/** `CrossSectionView.AUTO_FIT_SCREEN_FRACTION`. */
private const val CROSS_SECTION_SCREEN_FRACTION = 0.4f

/** Half the width, in metres, of the view opened onto a station with no splays to measure. */
private const val EMPTY_CROSS_SECTION_HALF_EXTENT = 2.5f

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
    sectionDrag: SectionDrag? = null,
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
        // The UIS artwork itself, drawn from the path data the symbols carry. It used to be a
        // small circle standing in for "a symbol is here", because the app's artwork is SVG and
        // nothing here could read it; parseSvgPath can.
        for (symbol in scene.sketch.symbolDetails) {
            val artwork = Symbol.byTherionName(symbol.symbolName)?.let { symbolPaths[it] }
            val colour = symbol.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            val centre = project(symbol.position)

            if (artwork == null) {
                // A symbol from a newer version of the app. Better a mark than nothing: the
                // surveyor put something there and the file still round-trips it.
                drawCircle(palette.symbol, radius = 4f, center = centre, style = Stroke(1.5f))
                continue
            }

            // Scale from the 40-unit grid to the stamp's size in metres, then to pixels.
            val scale = symbol.size * viewport.pixelsPerMetre / Symbol.VIEWPORT
            withTransform({
                translate(centre.x, centre.y)
                rotate(symbol.angle, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawPath(
                    artwork,
                    Color(colour.intValue),
                    style = Stroke(width = SYMBOL_STROKE_UNITS),
                )
            }
        }

        // Cross-sections: the passage profile at a station, drawn where it was placed on the plan.
        //
        // A section being dragged is drawn where the finger has it rather than where it still is
        // in the sketch, and in the indicator colour so it is obvious which one is moving. The
        // preview comes from SectionDrag, which is also what commits the edit - so what is drawn
        // during the drag cannot disagree with what the drag leaves behind.
        val sectionScale = scene.sketch.crossSectionScale
        for (detail in scene.sketch.crossSectionDetails) {
            val dragged = sectionDrag != null && sectionDrag.detail === detail
            val shown = if (dragged) sectionDrag.preview() else detail
            val colour = if (dragged) palette.symbol else palette.crossSection
            val centre = project(shown.position)
            for (line in shown.crossSection.getProjection().legMap.values) {
                val end = line.end.scale(sectionScale)
                drawLine(
                    colour,
                    centre,
                    Offset(
                        centre.x + end.x * viewport.pixelsPerMetre,
                        centre.y + end.y * viewport.pixelsPerMetre,
                    ),
                    1.2f,
                    StrokeCap.Round,
                )
            }
            drawCircle(colour, radius = 2.5f, center = centre)
        }

        // While re-aiming, the line the section is being aimed along: station to finger. Without
        // it the gesture is a section spinning for no visible reason - this is the thing being
        // pointed at the passage, and the pivot it swings about is not otherwise marked.
        if (sectionDrag != null && sectionDrag.mode == SectionDragMode.ROTATE) {
            sectionDrag.pivot?.let { pivot ->
                drawLine(
                    palette.symbol,
                    project(pivot),
                    project(sectionDrag.finger),
                    1f,
                    StrokeCap.Round,
                )
            }
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

/**
 * Stroke width for symbol artwork, in the symbol's own grid units.
 *
 * The drawables specify `strokeWidth="1"` on a 40-unit viewport, and the transform scales it with
 * everything else — so a symbol keeps its proportions at any zoom, which is what makes it read as
 * a drawn mark rather than as an icon pasted on.
 */
private const val SYMBOL_STROKE_UNITS = 1f

/**
 * The compass bearing a drag points in, in degrees clockwise from up.
 *
 * Screen y grows downwards, so a drag towards the top of the screen is north. A drag of no length
 * leaves the symbol upright rather than snapping it to an arbitrary direction.
 */
internal fun bearingOf(delta: Offset): Float = bearingOf(delta.x, delta.y)

/**
 * The same, from a raw vector — shared with the cross-section rotate gesture, which measures in
 * survey metres rather than in pixels but wants the identical answer.
 */
internal fun bearingOf(dx: Float, dy: Float): Float {
    if (dx == 0f && dy == 0f) return 0f
    val degrees = kotlin.math.atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / kotlin.math.PI
    return ((degrees + 360.0) % 360.0).toFloat()
}
