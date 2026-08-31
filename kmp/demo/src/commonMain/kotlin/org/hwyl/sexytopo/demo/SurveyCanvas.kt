package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
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
import org.hwyl.sexytopo.shared.sketch.SketchStyle
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import org.hwyl.sexytopo.shared.sketch.centroidOf
import org.hwyl.sexytopo.shared.sketch.colourForSymbol
import org.hwyl.sexytopo.shared.sketch.dashesAlong
import org.hwyl.sexytopo.shared.sketch.findCrossSectionBodyAt
import org.hwyl.sexytopo.shared.sketch.hitsHotCorner
import org.hwyl.sexytopo.shared.sketch.hotCornerSide
import org.hwyl.sexytopo.shared.sketch.hotCornerTopLefts
import org.hwyl.sexytopo.shared.sketch.whollyOutside
import org.hwyl.sexytopo.shared.sketch.zoomBetween
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
    /**
     * A station held under the finger, by name — the Android app's long-press station menu.
     *
     * Whatever tool is active, as in the original, where the long-press detector is consulted
     * ahead of the tool. Left unset by the cross-section editor, which draws one station and has
     * nothing to say about it.
     */
    onLongPressStation: (String) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = LocalAppFontFamily.current

    // Density is a composition-local, so it is read here rather than inside the gesture scope,
    // which is not a Density and cannot resolve dp.toPx().
    val symbolSizeInPixels =
        with(LocalDensity.current) { options.style.symbolSizeDp.dp.toPx() }

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

    // True while a hot-corner or two-finger gesture is panning the view. Only used to tint the
    // corners, so the surveyor can see the touch was taken deliberately rather than lost.
    var modalMoving by remember { mutableStateOf(false) }

    // The cross-section drag in progress, if any. Read inside the draw block so the preview
    // follows the finger; null between gestures, which is also what says "draw everything
    // normally".
    var sectionDrag by remember { mutableStateOf<SectionDrag?>(null) }

    /** Grab whatever cross-section is under the finger, for a [mode] drag. */
    fun grab(mode: SectionDragMode, at: Coord2D): SectionDrag? {
        if (!options.crossSectionsAreTouchable) return null
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
                        // The app's own override: a water symbol is stamped blue whatever the
                        // brush is set to, unless the surveyor has said otherwise.
                        colour =
                            colourForSymbol(
                                symbol.therionName,
                                editor.activeColour,
                                options.blueWater,
                            ),
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
                            viewport.toSurveyDistance(options.style.textSizeSp.sp.toPx()),
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
                            if (options.crossSectionsAreTouchable) {
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
                                    deletePathFragments = options.deletePathFragments,
                                    showCrossSections = options.crossSectionsAreTouchable,
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
                            if (!options.crossSectionsAreTouchable) return@detectTapGestures
                            findCrossSectionBodyAt(scene.sketch, viewport.toSurvey(offset))
                                ?.let(onOpenCrossSection)
                        }
                    }
                    .pointerInput(scene, tool, options.snapToLines) {
                        // A passage wall is drawn as a series of strokes, and the joins between
                        // them are where a drawing stops looking like a survey — a wall with gaps
                        // in it is also one no tracing tool can fill. Snapping is ported from
                        // `GraphView.considerSnapToSketchLine`, ends only and both ends: the start
                        // jumps on touch-down, and the finish appends the snapped point rather
                        // than moving the last one, exactly as the original does.
                        val snapWithin =
                            viewport.toSurveyDistance(
                                SketchDefaults.SNAP_TO_LINE_SENSITIVITY_DP.dp.toPx(),
                            )

                        fun snapped(point: Coord2D): Coord2D =
                            if (options.snapToLines) {
                                editor.snapPointNear(point, snapWithin) ?: point
                            } else {
                                point
                            }

                        // onDragEnd is told nothing about where the finger was, so the last
                        // point is kept here to snap the end against.
                        var lastPoint = Coord2D.ORIGIN

                        detectDragGestures(
                            onDragStart = { offset ->
                                lastPoint = viewport.toSurvey(offset)
                                editor.startPath(snapped(lastPoint))
                                strokeTick++
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                lastPoint = viewport.toSurvey(change.position)
                                editor.extendPath(lastPoint)
                                strokeTick++
                            },
                            onDragEnd = {
                                // finishPath simplifies the stroke and pushes one undo step; a
                                // stroke of fewer than two points is still committed, as in the
                                // original, because a tap is how you draw a dot.
                                if (options.snapToLines) {
                                    editor.snapPointNear(lastPoint, snapWithin)
                                        ?.let { editor.extendPath(it) }
                                }
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

    // Hold a station to get at it. See [detectLongPress] for why this is not detectTapGestures,
    // and why it sits between the tool's own detectors and the hot-corner one.
    val longPress =
        Modifier.pointerInput(scene, tool) {
            // The station under the press, decided while the finger is still down and acted on
            // when it lifts. One gesture loop, so a plain local is safe.
            var held: String? = null
            detectLongPress(
                onHeld = { offset ->
                    val reach =
                        viewport.toSurveyDistance(SketchDefaults.SELECTION_SENSITIVITY_DP.dp.toPx())
                    held = scene.stationNearest(viewport.toSurvey(offset), reach)
                    if (held != null) {
                        // `GraphView.LongPressListener` abandons the active path before showing
                        // the menu: a stroke begun by the press that opened it is not a stroke
                        // anybody meant to draw. Done now rather than on release, because the
                        // stroke is on screen now.
                        editor.abandonPath()
                        strokeTick++
                    }
                    held != null
                },
                onReleased = {
                    held?.let(onLongPressStation)
                    held = null
                },
            )
        }

    // Pan and zoom without leaving the current tool: see [detectModalMove]. Not installed over the
    // pan tool, which already does all of it with one finger and would end up handling a pinch
    // twice.
    val modalMove =
        if (tool == SketchTool.MOVE) {
            Modifier
        } else {
            Modifier.pointerInput(scene, tool, options.hotCorners, options.twoFingerMove) {
                detectModalMove(
                    hotCorners = options.hotCorners,
                    twoFingerPan = options.twoFingerMove,
                    pinchZoom = options.pinchToZoom,
                    onStart = {
                        // A second finger arriving mid-stroke abandons it rather than committing
                        // half a line the surveyor never meant to draw.
                        editor.abandonPath()
                        strokeTick++
                        modalMoving = true
                    },
                    onTransform = { centroid, pan, zoom ->
                        canvas.transformBy(centroid.toCoord2D(), pan.toCoord2D(), zoom)
                    },
                    onEnd = { modalMoving = false },
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
    Box(modifier = modifier.clipToBounds().then(gestures).then(longPress).then(modalMove)) {
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

            drawSurvey(
                scene,
                options,
                viewport,
                textMeasurer,
                fontFamily,
                tool,
                sectionDrag,
                modalMoving,
                // North is meaningless anywhere else: the extended elevation is unrolled onto a
                // line and a cross-section is drawn across the passage. `GraphView.drawCompass`
                // returns immediately unless the projection is the plan, and so does this.
                isPlan = sceneOverride == null && projection == Projection2D.PLAN,
            )
        }
    }
}

/** Everything the canvas needs, precomputed in survey space. */
/**
 * One drawn segment of the centreline, with the three things the display options ask about it.
 *
 * They are settled here rather than at draw time because they are questions about the *survey* —
 * which station a leg hangs off, which reading was the last one taken, whether the leg lies in the
 * plane being drawn — and by the time a segment reaches the canvas it is a pair of screen points
 * with no leg behind it. The Java can afford to ask `survey.getMostRecentLeg() == leg` inside its
 * draw loop because it still holds the leg; this port projects once and draws many times.
 */
class SceneSegment(
    val start: Coord2D,
    val end: Coord2D,
    /** `GraphView.isAttachedToActive`: does it hang off the station the next leg starts from? */
    val attachedToActive: Boolean = false,
    /** `Survey.getMostRecentLeg`. True of a splay too, if that was the last reading taken. */
    val isLatest: Boolean = false,
    /** `Projection2D.isLegInPlane`. False means foreshortened, and drawn dashed. */
    val inPlane: Boolean = true,
) {
    operator fun component1(): Coord2D = start
    operator fun component2(): Coord2D = end
}

class SurveyScene private constructor(
    val stations: List<Pair<String, Coord2D>>,
    val legs: List<SceneSegment>,
    val splays: List<SceneSegment>,
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
            val active = survey.activeStation
            val latest = survey.mostRecentLeg
            val legs = mutableListOf<SceneSegment>()
            val splays = mutableListOf<SceneSegment>()
            for ((leg, line) in space.legMap) {
                val segment =
                    SceneSegment(
                        start = line.start,
                        end = line.end,
                        // Identity, as in the Java: onwardLegs is a list of the legs themselves,
                        // and Leg has no equals, so this asks whether the leg *is* one of the
                        // active station's rather than whether it reads the same as one.
                        attachedToActive = active.onwardLegs.any { it === leg },
                        // Splays included, as in the Java: the test comes before the one that
                        // picks the splay's own paint, so a wall shot that was the last thing
                        // recorded is marked too. That is the right answer to "what did I just
                        // take", which is what the mark is for.
                        isLatest = leg === latest,
                        inPlane = projection.isLegInPlane(leg),
                    )
                if (leg.hasDestination()) legs.add(segment) else splays.add(segment)
            }

            val sketch = survey.getSketch(projection)

            val surveyPoints = buildList {
                stations.forEach { add(it.second) }
                legs.forEach { add(it.start); add(it.end) }
                splays.forEach { add(it.start); add(it.end) }
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
            // Every splay in a cross-section radiates from the station this view is centred on,
            // so all of them are "attached to the active station" in the sense the fade asks
            // about — which is why turning the fade on cannot empty this screen.
            val splays =
                projection.legMap.values.map { line ->
                    SceneSegment(line.start, line.end, attachedToActive = true)
                }

            val points = buildList {
                add(Coord2D.ORIGIN)
                splays.forEach { add(it.start); add(it.end) }
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
internal fun crossSectionFitBounds(splays: List<SceneSegment>): Bounds {
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
    /** Whether a stroke jumps to the end of a nearby one. See [SketchEditor.snapPointNear]. */
    val snapToLines: Boolean = SketchDefaults.SNAP_TO_LINES_DEFAULT,
    /** Whether the four corners pan the view whatever tool is selected. See [detectModalMove]. */
    val hotCorners: Boolean = AppPreferences.DEFAULT_HOT_CORNERS,
    /** Whether a two-fingered drag pans it too. Pinch-to-zoom does not depend on this. */
    val twoFingerMove: Boolean = AppPreferences.DEFAULT_TWO_FINGER_MOVE,
    /**
     * Whether everything but the working end of the survey is drawn at a fifth alpha.
     * `SketchPreferences.Toggle.FADE_NON_ACTIVE`, off by default as it is in the app.
     */
    val fadeNonActive: Boolean = AppPreferences.DEFAULT_FADE_NON_ACTIVE,
    /**
     * Whether the leg just taken is drawn in magenta. `pref_highlight_latest_leg`, on by default.
     */
    val highlightLatestLeg: Boolean = AppPreferences.DEFAULT_HIGHLIGHT_LATEST_LEG,
    /**
     * Whether a stamped water symbol comes out blue whatever the brush is. Not a display option
     * either — like [snapToLines] it changes what is created, not what is shown — but the Android
     * app puts it on this same menu, so it arrives by the same route.
     */
    val blueWater: Boolean = AppPreferences.DEFAULT_BLUE_WATER,
    /**
     * Whether cross-sections are drawn on the plan — and, because of that, whether they can be
     * tapped. `SHOW_X_SECTIONS`.
     */
    val showCrossSections: Boolean = AppPreferences.DEFAULT_SHOW_CROSS_SECTIONS,
    /** Whether two fingers zoom. `PINCH_TO_ZOOM`; the two-fingered *pan* is [twoFingerMove]. */
    val pinchToZoom: Boolean = AppPreferences.DEFAULT_PINCH_TO_ZOOM,
    /** Whether the north arrow is drawn on the plan. `SHOW_COMPASS`. */
    val showCompass: Boolean = AppPreferences.DEFAULT_SHOW_COMPASS,
    /**
     * Whether the eraser rubs out part of a wall line or all of it. `pref_delete_path_fragments`.
     */
    val deletePathFragments: Boolean = SketchDefaults.DELETE_PATH_FRAGMENTS_DEFAULT,
    /**
     * How big everything is drawn. `preferences_sketching.xml`'s numeric group.
     *
     * On the display options rather than read from the preferences here, because this canvas is
     * also driven by the headless renderer and by tests that hold no preferences file — and
     * because every other thing that changes what the drawing looks like arrives by this route.
     */
    val style: SketchStyle = SketchStyle.DEFAULT,
) {
    /**
     * Whether a cross-section on the plan is there to be found by a finger.
     *
     * One property rather than the same pair of conditions at four hit-test sites, because the
     * Java's rule is a single sentence — an invisible cross-section cannot be tapped — and it is
     * invisible either because the whole sketch is hidden or because sections are.
     */
    val crossSectionsAreTouchable: Boolean get() = showSketch && showCrossSections
}

/** `GraphView.FADED_ALPHA`, which is `0xff / 5` of full. */
const val FADED_ALPHA = 0.2f

/** `GraphView.DASHED_LINE_INTERVAL_DP`. */
private const val DASH_INTERVAL_DP = 4f

/**
 * Every size on the drawing, in dp.
 *
 * They were plain numbers until this was written, and a plain number in a `DrawScope` is a
 * *physical pixel*: on a phone at three device pixels to the dp the whole cave came out a third of
 * the size it was drawn at, hairline centreline and pinhead stations, while the labels beside them
 * — measured in `sp`, which Compose does scale — stayed the size they should be. Nothing here
 * could catch it, because the browser the checks run in is at one device pixel to the dp and every
 * number is its own conversion. The Android app converts all of these through `dpToPixels`.
 *
 * The numbers are the ones this canvas already used, reinterpreted, so the drawing is unchanged at
 * density 1 and correct everywhere else. The Java's own leg width is 2 dp against the 2.5 here.
 */
private object CanvasSizes {
    const val LEG_STROKE_DP = 2.5f
    const val SPLAY_STROKE_DP = 1f
    const val SKETCH_STROKE_DP = 2f
    const val STATION_RADIUS_DP = 3.5f
    const val SYMBOL_FALLBACK_RADIUS_DP = 4f
    const val THIN_STROKE_DP = 1.5f
    const val CROSS_SECTION_STROKE_DP = 1.2f
    const val CROSS_SECTION_RADIUS_DP = 2.5f
    const val AIMING_LINE_DP = 1f
    /** Where a station's name sits relative to its dot. */
    const val LABEL_RIGHT_DP = 5f
    const val LABEL_UP_DP = 14f
}

/**
 * How far off the screen a station can be and still put something on it.
 *
 * Its name goes up and to the right of the dot, so this has to cover the longest name a surveyor
 * is likely to type rather than the dot's own radius.
 */
private const val STATION_CULL_MARGIN_DP = 120f

/** How far in from the top-right corner the eraser's reach is shown. */
private const val ERASER_INSET_DP = 40f

/** The faint metre grid. */
private const val GRID_STROKE_DP = 1f

/** The scale bar: the rule itself, its two end ticks, and where its label sits. */
private const val SCALE_BAR_TARGET_DP = 120f
private const val SCALE_BAR_LEFT_DP = 24f
private const val SCALE_BAR_STROKE_DP = 2f
private const val SCALE_BAR_TICK_DP = 5f
private const val SCALE_BAR_LABEL_UP_DP = 24f

/** `GeneralPreferences.getLegendFontSizeSp`, which the compass sizes itself off. */
private const val LEGEND_TEXT_SP = 10f

/** Clear air between the arrow's tail and the scale bar's label. */
private const val COMPASS_GAP_DP = 6f

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

    val gridStroke = GRID_STROKE_DP.dp.toPx()
    var x = floor(topLeft.x / spacing) * spacing
    while (x <= bottomRight.x) {
        val screenX = viewport.toScreen(Coord2D(x, topLeft.y)).x
        drawLine(palette.grid, Offset(screenX, 0f), Offset(screenX, size.height), gridStroke)
        x += spacing
    }

    var y = floor(topLeft.y / spacing) * spacing
    while (y <= bottomRight.y) {
        val screenY = viewport.toScreen(Coord2D(topLeft.x, y)).y
        drawLine(palette.grid, Offset(0f, screenY), Offset(size.width, screenY), gridStroke)
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
    modalMoving: Boolean = false,
    isPlan: Boolean = false,
) {
    val palette = if (options.darkMode) DarkPalette else LightPalette

    drawRect(palette.background)

    fun project(coord: Coord2D): Offset = viewport.toScreen(coord)

    if (options.showGrid) {
        drawGrid(viewport, palette)
    }

    // How the Java decides what a segment looks like, in one place rather than three: faded if the
    // fade is on and it does not hang off the working station, magenta if it is the reading just
    // taken, and dashed if it does not lie in the plane being drawn.
    // The screen, in the coordinates a projected segment arrives in. `GraphView.isLineOnCanvas`
    // tests against the canvas exactly, with no margin: a leg whose ends are both off the same
    // side has nothing between them to see.
    val screenTopLeft = Coord2D(0f, 0f)
    val screenBottomRight = Coord2D(size.width, size.height)

    fun drawSegment(segment: SceneSegment, base: Color, width: Float) {
        val from = project(segment.start)
        val to = project(segment.end)
        // Zoomed into one passage of a real survey almost every leg is off screen, and each one
        // otherwise costs a draw call and - if it is drawn dashed - a list of dashes built and
        // thrown away every frame, while a finger is dragging.
        if (whollyOutside(from.toCoord2D(), to.toCoord2D(), screenTopLeft, screenBottomRight)) {
            return
        }
        val colour =
            if (options.fadeNonActive && !segment.attachedToActive) {
                base.copy(alpha = FADED_ALPHA)
            } else {
                base
            }
        if (segment.inPlane) {
            drawLine(colour, from, to, width, StrokeCap.Round)
            return
        }
        val dashLength = DASH_INTERVAL_DP.dp.toPx()
        for ((dashFrom, dashTo) in dashesAlong(from.toCoord2D(), to.toCoord2D(), dashLength)) {
            drawLine(colour, dashFrom.toOffset(), dashTo.toOffset(), width, StrokeCap.Round)
        }
    }

    // The magenta is tested here as well as on the legs because the Java asks
    // `getMostRecentLeg() == leg` *before* it asks whether the reading is a splay, so a wall shot
    // that was the last thing taken is marked too. Drawing splays in their own loop is what made
    // it easy to miss: the branch that existed in one place in the original exists in two here.
    if (options.showSplays) {
        for (splay in scene.splays) {
            val base =
                if (options.highlightLatestLeg && splay.isLatest) {
                    palette.latestLeg
                } else {
                    palette.splay
                }
            drawSegment(splay, base, options.style.splayWidthDp.dp.toPx())
        }
    }

    if (options.showSketch) {
        for (detail in scene.sketch.pathDetails) {
            if (detail.path.size < 2) continue
            val colour = detail.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            drawPolyline(
                detail.path.map(::project),
                Color(colour.intValue),
                options.style.sketchLineWidthDp.dp.toPx(),
            )
        }
    }

    // Centreline on top of the sketch, as in the original.
    for (leg in scene.legs) {
        val base =
            if (options.highlightLatestLeg && leg.isLatest) palette.latestLeg else palette.centreline
        drawSegment(leg, base, options.style.legWidthDp.dp.toPx())
    }

    val stationMargin = STATION_CULL_MARGIN_DP.dp.toPx()
    for ((name, coord) in scene.stations) {
        val centre = project(coord)
        // Zoomed into one passage, almost every station of a real survey is off screen, and a
        // station is not only a dot: if labels are showing, each one measures a piece of text,
        // which is much more work than the circle. The Android app walks them all.
        if (whollyOutside(centre.toCoord2D(), screenTopLeft, screenBottomRight, stationMargin)) {
            continue
        }
        val isActive = name == scene.activeStationName
        // The Java sets the paint's alpha to solid when it reaches the active station and never
        // sets it back, so which stations come out faded depends on where the active one falls in
        // a HashMap's iteration order — see the README. Here the question is asked per station.
        val stationColour =
            if (options.fadeNonActive && !isActive) {
                palette.station.copy(alpha = FADED_ALPHA)
            } else {
                palette.station
            }
        drawCircle(
            stationColour,
            radius = options.style.stationRadiusDp.dp.toPx(),
            center = centre,
        )
        if (isActive) {
            drawActiveStationHighlight(centre, palette)
        }
        if (options.showStationLabels &&
            viewport.pixelsPerMetre > LABEL_VISIBILITY_PIXELS_PER_METRE
        ) {
            val layout =
                textMeasurer.measure(
                    name,
                    TextStyle(
                        color = palette.stationLabel,
                        fontSize = options.style.stationLabelSizeSp.sp,
                        fontFamily = fontFamily,
                    ),
                )
            drawText(
                layout,
                topLeft =
                    Offset(
                        centre.x + CanvasSizes.LABEL_RIGHT_DP.dp.toPx(),
                        centre.y - CanvasSizes.LABEL_UP_DP.dp.toPx(),
                    ),
            )
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
                drawCircle(
                    palette.symbol,
                    radius = CanvasSizes.SYMBOL_FALLBACK_RADIUS_DP.dp.toPx(),
                    center = centre,
                    style = Stroke(CanvasSizes.THIN_STROKE_DP.dp.toPx()),
                )
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
        for (detail in if (options.showCrossSections) scene.sketch.crossSectionDetails else emptyList()) {
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
                    CanvasSizes.CROSS_SECTION_STROKE_DP.dp.toPx(),
                    StrokeCap.Round,
                )
            }
            drawCircle(
                colour,
                radius = CanvasSizes.CROSS_SECTION_RADIUS_DP.dp.toPx(),
                center = centre,
            )
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
                    CanvasSizes.AIMING_LINE_DP.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
    }

    if (tool == SketchTool.ERASE) {
        // The eraser's real reach, drawn at the size a tap would actually clear.
        drawCircle(
            palette.station,
            radius = SketchDefaults.DELETE_DETAILS_WITHIN_DP.dp.toPx(),
            center = Offset(size.width - ERASER_INSET_DP.dp.toPx(), ERASER_INSET_DP.dp.toPx()),
            style = Stroke(CanvasSizes.THIN_STROKE_DP.dp.toPx()),
        )
    }

    // Last, so they sit on top of the drawing rather than under it: a corner you cannot see
    // because a passage wall is drawn across it is a corner that will eat a stroke.
    if (options.hotCorners) {
        drawHotCorners(modalMoving, palette)
    }

    drawScaleBar(viewport.pixelsPerMetre, palette, textMeasurer, fontFamily, options.style.legendSizeSp)
    // `drawCompass` is guarded on both the toggle and the projection, in that order. There is
    // no arrow on an elevation because there is no bearing to draw one for.
    if (options.showCompass && isPlan) {
        drawNorthArrow(palette, textMeasurer, fontFamily, options.style.legendSizeSp)
    }
}

/**
 * The north arrow, above the scale bar and to the left, as `GraphView.drawCompass` draws it.
 *
 * A plan with no north on it is a picture rather than a survey — the exported SVG has carried one
 * since the legend was ported, and the drawing on screen has not. The geometry is the Java's, sized
 * off the legend's own text size: an arrow two and a half text-heights long, a head six tenths of
 * one, and the letter N above the tip.
 *
 * **It does not swing with the phone yet.** The original rotates it by the device's heading, and on
 * a plan north is genuinely up — `Projection2D.PLAN` maps the northing to *minus* the screen y — so
 * an arrow that always points up is correct rather than approximate; what is missing is the
 * *magnetometer*, which needs an `expect`/`actual` on three platforms and, on iOS, a
 * usage-description key that crashes the app on launch if it is wrong. Left for when somebody can
 * run it on a phone, which is also the only place it could be checked.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawNorthArrow(
    palette: Palette,
    textMeasurer: TextMeasurer,
    fontFamily: FontFamily,
    legendSizeSp: Float = LEGEND_TEXT_SP,
    headingDegrees: Float = 0f,
) {
    // The arrow is built out of its own letter: its length is two and a half times the cap height
    // and its head six tenths of it, so making the legend bigger makes the whole thing bigger and
    // it stays in proportion. That is why one number drives all of it.
    val textSize = legendSizeSp.sp.toPx()
    val layout =
        textMeasurer.measure(
            "N",
            TextStyle(color = palette.scaleBar, fontSize = legendSizeSp.sp, fontFamily = fontFamily),
        )
    val textHeight = layout.size.height.toFloat()
    val arrowLength = textSize * 2.5f
    val head = textSize * 0.6f
    val centreX = textSize * 1.25f + arrowLength / 2f + textSize
    // Above the scale bar *and its label*. The Java measures from its bar alone, but its label
    // hangs below the bar and this port's sits above it, so copying the formula put the arrow's
    // tail straight through the words "10 m".
    val labelTop = size.height - 2f * SCALE_BAR_LABEL_UP_DP.dp.toPx() - textHeight
    val centreY = labelTop - COMPASS_GAP_DP.dp.toPx() - arrowLength / 2f
    val stroke = SCALE_BAR_STROKE_DP.dp.toPx()

    rotate(-headingDegrees, pivot = Offset(centreX, centreY)) {
        val tip = centreY - arrowLength / 2f
        val tail = centreY + arrowLength / 2f
        drawLine(palette.scaleBar, Offset(centreX - head, tip + head), Offset(centreX, tip), stroke)
        drawLine(palette.scaleBar, Offset(centreX, tip), Offset(centreX + head, tip + head), stroke)
        drawLine(palette.scaleBar, Offset(centreX, tip), Offset(centreX, tail), stroke)
        // The Java positions the letter by its baseline; Compose positions it by its top.
        drawText(
            layout,
            topLeft = Offset(centreX - textSize * 0.35f, tip - textSize * 0.2f - textHeight),
        )
    }
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
    legendSizeSp: Float = LEGEND_TEXT_SP,
) {
    if (!pixelsPerMetre.isFinite() || pixelsPerMetre <= 0f) return

    // Choose a round number of metres whose bar lands near the target length.
    val rawMetres = SCALE_BAR_TARGET_DP.dp.toPx() / pixelsPerMetre
    if (!rawMetres.isFinite() || rawMetres <= 0f) return
    val magnitude = 10f.pow(floor(log10(rawMetres)))
    val metres =
        listOf(1f, 2f, 5f, 10f).map { it * magnitude }.minByOrNull { abs(it - rawMetres) } ?: return
    if (!metres.isFinite() || metres <= 0f) return

    val barPixels = metres * pixelsPerMetre
    if (!barPixels.isFinite() || barPixels > size.width) return
    val left = SCALE_BAR_LEFT_DP.dp.toPx()
    val bottom = size.height - SCALE_BAR_LABEL_UP_DP.dp.toPx()
    val stroke = SCALE_BAR_STROKE_DP.dp.toPx()
    val tick = SCALE_BAR_TICK_DP.dp.toPx()

    drawLine(palette.scaleBar, Offset(left, bottom), Offset(left + barPixels, bottom), stroke)
    drawLine(palette.scaleBar, Offset(left, bottom - tick), Offset(left, bottom + tick), stroke)
    drawLine(
        palette.scaleBar,
        Offset(left + barPixels, bottom - tick),
        Offset(left + barPixels, bottom + tick),
        stroke,
    )

    val label = if (metres >= 1f) "${metres.roundToInt()} m" else "${(metres * 100).roundToInt()} cm"
    val layout =
        textMeasurer.measure(
            label,
            TextStyle(color = palette.scaleBar, fontSize = legendSizeSp.sp, fontFamily = fontFamily),
        )
    drawText(layout, topLeft = Offset(left, bottom - SCALE_BAR_LABEL_UP_DP.dp.toPx()))
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
    /** `R.color.hotCorner`, drawn at a fifth alpha. The active tint is [activeStation]'s amber. */
    val hotCorner: Color,
    /** `R.color.legLatest`, which the app resolves to `md_magenta`. */
    val latestLeg: Color,
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
        hotCorner = SexyTopoColours.hotCorner,
        latestLeg = SexyTopoColours.latestLeg,
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
        hotCorner = SexyTopoColours.hotCornerNight,
        latestLeg = SexyTopoColours.latestLeg,
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

/**
 * Pan and zoom the sketch without putting the pencil down.
 *
 * Ported from `GraphView.isModalMoveSelection` and the `ScaleGestureDetector` above it. Until this
 * existed, moving the drawing while drawing it meant tapping MOVE, dragging, and tapping DRAW again
 * — two toolbar hits per pan, on a phone in a wet oversuit, for the single most frequent thing a
 * surveyor does to a sketch. The Android app has three escapes and this port had none of them:
 *
 *  - a touch that *starts* in one of the four corners pans instead of drawing ([hotCorners]);
 *  - a second finger pans ([twoFingerPan], off by default, exactly as in the original);
 *  - two fingers zoom ([pinchZoom], on by default, and its own preference in the original too);
 *  - a second finger always zooms, under every tool, gated on neither preference.
 *
 * The third is the one that was most obviously missing: pinch-to-zoom worked only under the pan
 * tool here, whereas the Android `ScaleGestureDetector` is consulted before the tool switch and so
 * works under all of them.
 *
 * ## Why this is a hand-written pointer loop
 *
 * `detectTransformGestures` would do all of it except decide *when* to start. It fires for a single
 * finger as readily as for two, so a canvas that is also being drawn on cannot use it: every stroke
 * would pan the view under itself. This loop watches the pointers, decides on the way past, and
 * consumes only once it has taken over — which is what lets the tool's own detector, sitting
 * further out in the same modifier chain, carry on untouched the rest of the time.
 *
 * It has to be the *innermost* pointer input on the node. Compose delivers the main pass from the
 * inside out, so only the innermost handler can consume a change before the tool's detector sees
 * it; installed further out, a hot-corner touch would draw a stroke and pan at the same time.
 */
internal suspend fun PointerInputScope.detectModalMove(
    hotCorners: Boolean,
    twoFingerPan: Boolean,
    pinchZoom: Boolean,
    onStart: () -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        // requireUnconsumed = false: this handler runs first and consumes nothing yet, but a
        // gesture that began elsewhere should still be watched for a second finger.
        val down = awaitFirstDown(requireUnconsumed = false)

        var moving =
            hotCorners &&
                hitsHotCorner(
                    down.position.x,
                    down.position.y,
                    size.width.toFloat(),
                    size.height.toFloat(),
                )
        if (moving) {
            down.consume()
            onStart()
        }

        var previous = listOf(down.position.toCoord2D())

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            val current = pressed.map { it.position.toCoord2D() }

            // A second finger takes over mid-stroke, as in the original, where the scale detector
            // is consulted on every event rather than only on the first. `onStart` abandons the
            // stroke in progress; the tool's own detector sees its changes consumed from here on
            // and cancels itself, so the half-drawn line disappears rather than being committed.
            if (!moving && pressed.size >= 2) {
                moving = true
                onStart()
                // Re-baseline: the pan is measured from *now*, not from where one finger was, or
                // the view would leap by the distance between the two.
                previous = current
            }

            if (moving) {
                for (change in pressed) change.consume()

                // Nothing is measured across a change in the number of fingers. Both the centroid
                // and the spread jump when one lands or lifts — a finger leaving a pinch moves the
                // centroid to the one that remains — and applying that jump throws the drawing
                // across the screen at exactly the moment somebody is finishing a gesture.
                val sameFingers = previous.size == current.size
                val zoom =
                    if (sameFingers && pinchZoom) zoomBetween(previous, current) else 1f
                val pan =
                    if (!sameFingers) {
                        Coord2D.ORIGIN
                    } else if (pressed.size < 2 || twoFingerPan) {
                        // Two separate preferences, as in the original: [pinchZoom] gates the
                        // zoom and [twoFingerPan] the drag. A hot-corner pan is one finger, so
                        // neither has any say over it.
                        centroidOf(current) - centroidOf(previous)
                    } else {
                        Coord2D.ORIGIN
                    }
                if (zoom != 1f || pan != Coord2D.ORIGIN) {
                    onTransform(centroidOf(current).toOffset(), pan.toOffset(), zoom)
                }
            }

            previous = current
        }

        if (moving) onEnd()
    }
}

/**
 * The four squares that pan the view, drawn faintly so they read as furniture rather than as ink.
 *
 * `GraphView.drawHotCorners`, with the fourth corner added: see [hotCornerTopLefts] for why the
 * original draws three and tests four. The active tint is the app's own — grey normally, amber
 * while a corner is actually panning, so the surveyor can see that the corner took the touch and
 * the stroke was not simply lost.
 */
private fun DrawScope.drawHotCorners(active: Boolean, palette: Palette) {
    val side = hotCornerSide(size.width, size.height)
    if (side <= 0f) return
    val colour = (if (active) palette.activeStation else palette.hotCorner).copy(alpha = FADED_ALPHA)
    for (corner in hotCornerTopLefts(size.width, size.height)) {
        drawRect(colour, topLeft = Offset(corner.x, corner.y), size = Size(side, side))
    }
}

/** `GraphView.FADED_ALPHA`, which is 0xff / 5, as a fraction. */

/**
 * A press held still on the same spot, as `GraphView`'s `LongPressListener` does it.
 *
 * Hand-written rather than `detectTapGestures(onLongPress = …)`, for two reasons. That detector
 * consumes the touch-down before deciding anything, and it also refuses a down somebody else has
 * consumed — so two of them in one modifier chain cannot both work, and the draw tool already has
 * one for opening a cross-section. And it offers no way to say "only if the press is on something":
 * this has to hit-test a station at the moment the press qualifies and leave the gesture alone when
 * there is nothing under it, or a long press on blank paper would swallow the touch.
 *
 * [onHeld] runs the moment the press qualifies and returns whether it wants the gesture. When it
 * does, the rest of the touch is swallowed — the original's `menuShownInThisTouch` — so the lift
 * that follows does not also count as a tap and select a station or drop a cross-section.
 *
 * [onReleased] runs when the finger comes off, and is where a dialog belongs. Opening one from
 * [onHeld] puts it on screen under a finger that is still down, and the release then lands on the
 * scrim and dismisses it again: on a phone the menu appears for as long as the surveyor keeps
 * holding and vanishes the instant they let go, which reads as the app not having the feature.
 * That only shows up when the press is somewhere the dialog will not cover — near the bottom of the
 * screen, which is exactly where the stations at the working end of a survey are.
 */
internal suspend fun PointerInputScope.detectLongPress(
    onHeld: (Offset) -> Boolean,
    onReleased: (Offset) -> Unit,
) {
    awaitEachGesture {
        // Consumed here means an inner handler took the gesture: a hot-corner pan, in practice.
        val down = awaitFirstDown(requireUnconsumed = true)

        val held =
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    var stillHeld = true
                    while (stillHeld) {
                        val event = awaitPointerEvent()
                        val finger = event.changes.firstOrNull { it.id == down.id }
                        stillHeld =
                            finger != null &&
                                finger.pressed &&
                                !finger.isConsumed &&
                                // A second finger is a pinch, not a long press.
                                event.changes.count { it.pressed } == 1 &&
                                (finger.position - down.position).getDistance() <=
                                    viewConfiguration.touchSlop
                    }
                }
                // The loop ended on its own, so the finger moved or lifted first.
                false
            } catch (_: PointerEventTimeoutCancellationException) {
                true
            }

        if (held && onHeld(down.position)) {
            // Consume *then* test, and consume every change rather than the pressed ones. The
            // touch-up is the change that is no longer pressed, so a loop that filters for pressed
            // changes first lets exactly one event through: the release. That is the one the tool's
            // tap detector is waiting for — so the menu opened and, on the same touch, the tap
            // underneath it opened a cross-section, which replaced the screen the menu was on. It
            // looked like the menu never appearing.
            while (true) {
                val event = awaitPointerEvent()
                for (change in event.changes) change.consume()
                if (event.changes.none { it.pressed }) break
            }
            onReleased(down.position)
        }
    }
}
