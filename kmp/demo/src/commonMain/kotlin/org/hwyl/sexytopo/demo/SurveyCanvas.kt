package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
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
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.SketchDefaults
import org.hwyl.sexytopo.shared.sketch.SketchStyle
import org.hwyl.sexytopo.shared.sketch.SketchEditor
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.sketch.SketchViewport
import org.hwyl.sexytopo.shared.sketch.boundsOf
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
import org.hwyl.sexytopo.shared.survey.SurveyStats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The survey drawing surface, written once in Compose Multiplatform and rendered by Skia on every
 * platform — iOS included.
 *
 * Very little of what `GraphView` does on Android is actually Android-specific: the tool model,
 * viewport, hit-testing, undo stack, stroke simplification and the eraser's split-rather-than-
 * delete behaviour are all in the shared module. What is left here is drawing and touch plumbing.
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
     * A callback rather than a dialog raised from here: typing needs a keyboard and the canvas has
     * none. The host puts the dialog up and calls [SketchEditor.addText] when the surveyor is done.
     */
    onPlaceLabel: (Coord2D, Float) -> Unit = { _, _ -> },
    /** Which symbol the stamp tool places. */
    symbol: Symbol = Symbol.ENTRANCE,
    /**
     * Draw this scene instead of building one from [survey] and [projection].
     *
     * The cross-section editor's surface is the same canvas over a different world: one station at
     * the origin, its splays around it, and the section's own sub-sketch.
     */
    sceneOverride: SurveyScene? = null,
    /** Tapping a cross-section body, with the tools the Android app allows it from. */
    onOpenCrossSection: (CrossSectionDetail) -> Unit = {},
    /**
     * A station held under the finger, by name — the Android app's long-press station menu.
     *
     * Left unset by the cross-section editor, which draws one station and has nothing to say
     * about it.
     */
    onLongPressStation: (String) -> Unit = {},
    /**
     * The station whose cross-section is waiting to be placed: `stationNameBeingCrossSectioned`.
     *
     * Set while [SketchTool.POSITION_CROSS_SECTION] is armed, and the reason that tool needs no
     * hit test — the station was chosen from its own menu before the tool was.
     */
    crossSectioning: Station? = null,
    /** The one-shot has fired, so the tool that was in hand before it comes back. */
    onCrossSectionPositioned: () -> Unit = {},
    /**
     * Which way the top of the screen is pointing, instead of asking the device.
     *
     * For the callers that have no device to ask: the headless renderer, which has no
     * magnetometer, and the tests, which need the same picture twice running. Left null — which is
     * everything the surveyor ever runs — the canvas asks the platform through
     * [rememberDeviceHeading].
     */
    headingDegrees: Float? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = LocalAppFontFamily.current

    // Density is a composition-local, so it is read here rather than inside the gesture scope,
    // which is not a Density and cannot resolve dp.toPx().
    val symbolSizeInPixels =
        with(LocalDensity.current) { options.style.symbolSizeDp.dp.toPx() }

    // Not computed at all when the caller has supplied its own scene, and that is load-bearing
    // rather than an optimisation: the cross-section editor passes Projection2D.CROSS_SECTION, and
    // `Survey.getSketch` throws for it. Building the scene eagerly threw inside the composition,
    // which does not crash the page - the editor simply never appeared - so the tool looked as
    // though it did nothing at all.
    val projected =
        remember(survey, projection, revision, sceneOverride == null) {
            if (sceneOverride == null) SurveyScene.from(survey, projection) else null
        }
    val scene = sceneOverride ?: projected!!

    // North is meaningless anywhere else: the extended elevation is unrolled onto a line and a
    // cross-section is drawn across the passage. `GraphView.drawCompass` returns immediately
    // unless the projection is the plan, and so does this.
    val isPlan = sceneOverride == null && projection == Projection2D.PLAN
    // Asked for only while the arrow is on screen, so the magnetometer stops with it. Read in the
    // draw block below rather than here, so a heading arriving ten times a second redraws the
    // canvas without recomposing it.
    val deviceHeading =
        rememberDeviceHeading(
            enabled = options.showCompass && isPlan && headingDegrees == null,
        )

    // What the gesture loops below hit-test against. They are not restarted when the scene is
    // rebuilt (see the keys on `gestures`), so they read the newest one through this rather than
    // capturing whichever was current when a loop began - a station that arrived during a stroke
    // is still there to be long-pressed once it is over.
    val currentScene by rememberUpdatedState(scene)

    // For the same reason, the callbacks: a loop that outlives the composition that started it
    // must not go on calling that composition's lambdas.
    val currentOnSketchEdit by rememberUpdatedState(onSketchEdit)
    val currentOnSelectStation by rememberUpdatedState(onSelectStation)
    val currentOnPlaceLabel by rememberUpdatedState(onPlaceLabel)
    val currentOnOpenCrossSection by rememberUpdatedState(onOpenCrossSection)
    val currentOnLongPressStation by rememberUpdatedState(onLongPressStation)
    val currentCrossSectioning by rememberUpdatedState(crossSectioning)
    val currentOnCrossSectionPositioned by rememberUpdatedState(onCrossSectionPositioned)

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

    /** Start a [mode] drag of [detail], the finger being at [at]. */
    fun hold(mode: SectionDragMode, detail: CrossSectionDetail, at: Coord2D): SectionDrag =
        SectionDrag(
            mode = mode,
            detail = detail,
            from = at,
            pivot = currentScene.positionOf(detail.station.name),
        )

    /** Grab whatever cross-section is under the finger, for a [mode] drag. */
    fun grab(mode: SectionDragMode, at: Coord2D): SectionDrag? {
        if (!options.crossSectionsAreTouchable) return null
        val detail = findCrossSectionBodyAt(currentScene.sketch, at) ?: return null
        return hold(mode, detail, at)
    }

    // Where each cross-section's drag bar was drawn last frame, in screen coordinates: written by
    // the draw pass, read by [sectionHandle] below. A plain map rather than snapshot state: it is
    // written from inside the draw, and a write that invalidated the composition would ask for
    // another frame, which would write it again.
    val handleRects = remember { mutableMapOf<CrossSectionDetail, Rect>() }

    // `Modifier.pointerInput` runs a suspending gesture loop that restarts only when one of its
    // keys changes, and a restart cancels whatever gesture is under the finger. So the keys are
    // exactly the things that make the running loop wrong, and nothing else:
    //
    //  - `tool`, or picking a new tool would swap the *lambda* while the previously started loop
    //    kept running, and the canvas would go on panning;
    //  - `options`, or a running loop would hold the settings it captured when it started, and
    //    turning cross-sections off from a menu would change nothing a finger could feel. The whole
    //    object rather than the settings each detector happens to read, because a list of what a
    //    lambda reads is exactly the sort of thing that goes stale the next time somebody adds a
    //    line to it. `DisplayOptions` is a data class, so this key changes exactly when a setting
    //    changes;
    //  - `editor`, `canvas` and `survey`, which change together when the view switches to another
    //    sketch, and a loop must not go on drawing into the old one.
    //
    // Not `scene`. The scene is rebuilt on every revision - every reading from the instrument, and
    // every finished stroke - and keying on it cancelled the gesture in progress each time. Someone
    // drawing a wall while the instrument was firing lost the line under their pen the moment a
    // shot landed: the loop's cleanup abandoned it, and the version before that left it on the
    // sketch with no undo step behind it. What the loops need from the scene, they read live
    // through `currentScene`.
    val gestureKeys = arrayOf<Any?>(survey, editor, canvas, tool, options)

    val gestures =
        when (tool) {
            SketchTool.MOVE ->
                Modifier.pointerInput(*gestureKeys) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        // Zoom about the pinch centre first, then pan, so the point under the
                        // fingers stays under them.
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
                    currentOnSketchEdit()
                }

                Modifier
                    .pointerInput(*gestureKeys, symbol) {
                        detectTapGestures { offset -> stamp(offset, 0f) }
                    }
                    .pointerInput(*gestureKeys, symbol) {
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
                // One gesture loop for both: press on a section, drag, lift. Only what the drag
                // means differs, and SectionDrag holds that.
                val mode =
                    if (tool == SketchTool.MOVE_CROSS_SECTION) SectionDragMode.MOVE
                    else SectionDragMode.ROTATE

                Modifier.pointerInput(*gestureKeys) {
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
                            if (sectionDrag?.commit(editor) == true) currentOnSketchEdit()
                            sectionDrag = null
                        },
                        onDragCancel = { sectionDrag = null },
                    )
                }
            }

            SketchTool.POSITION_CROSS_SECTION ->
                Modifier.pointerInput(*gestureKeys) {
                    // `handlePositionCrossSection`. There is no hit test here and there must not
                    // be one: the station was named by the menu this tool was armed from, and the
                    // tap says only where on the paper the section is drawn. Somewhere the
                    // passage is not, usually - which is exactly why the app asks.
                    detectTapGestures { offset ->
                        val station = currentCrossSectioning ?: return@detectTapGestures
                        // The bearing comes from CrossSectioner's own heuristic: bisect the
                        // corner mid-passage, follow the single leg at a dead end, give up and
                        // use north where there is nothing to go on. It is a guess, and
                        // SketchTool.ROTATE_CROSS_SECTION is how a surveyor overrules it.
                        editor.addCrossSection(
                            CrossSectioner.section(survey, station),
                            viewport.toSurvey(offset),
                        )
                        currentOnSketchEdit()
                        currentOnCrossSectionPositioned()
                    }
                }

            SketchTool.TEXT ->
                Modifier.pointerInput(*gestureKeys) {
                    // Tap where the label goes. Size is converted from sp on screen into metres in
                    // the survey, exactly as the symbol tool does, so a label grows with the
                    // passage rather than staying the size it was placed at.
                    detectTapGestures { offset ->
                        currentOnPlaceLabel(
                            viewport.toSurvey(offset),
                            viewport.toSurveyDistance(options.style.textSizeSp.sp.toPx()),
                        )
                    }
                }

            SketchTool.SELECT ->
                Modifier.pointerInput(*gestureKeys) {
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
                                findCrossSectionBodyAt(currentScene.sketch, where)
                            } else {
                                // Invisible sections cannot be tapped.
                                null
                            }
                        if (section != null) {
                            currentOnOpenCrossSection(section)
                            return@detectTapGestures
                        }
                        val reach =
                            viewport.toSurveyDistance(
                                SketchDefaults.SELECTION_SENSITIVITY_DP.dp.toPx(),
                            )
                        val chosen = currentScene.stationNearest(where, reach)
                        if (chosen != null && currentOnSelectStation(chosen)) currentOnSketchEdit()
                    }
                }

            SketchTool.ERASE ->
                Modifier.pointerInput(*gestureKeys) {
                    // A rubber that rubs, which is a deliberate departure from the Android app:
                    // `GraphView.handleErase` only ever takes out what is under the *first* touch,
                    // and dragging across a wall does nothing at all there. A tool drawn as an
                    // eraser and named *Erase* is one every surveyor will try to rub with, so here
                    // it erases under the finger when it lands, and again everywhere it goes.
                    val toleranceInMetres =
                        viewport.toSurveyDistance(
                            // The constant is in dp; the viewport thinks in pixels. Passing it
                            // straight through made the eraser's reach depend on the display
                            // density, and drew a circle of the wrong size to say so.
                            SketchDefaults.DELETE_DETAILS_WITHIN_DP.dp.toPx(),
                        )

                    fun rubAt(point: Coord2D): Boolean =
                        editor.eraseAt(
                            point = point,
                            toleranceInMetres = toleranceInMetres,
                            pixelsPerMetre = viewport.pixelsPerMetre,
                            deletePathFragments = options.deletePathFragments,
                            showCrossSections = options.crossSectionsAreTouchable,
                        )

                    awaitEachGesture {
                        // One press of ctrl+z has to take back everything one drag of the rubber
                        // erased, not just the last stroke it crossed.
                        editor.inOneUndoStep {
                            val down = awaitFirstDown()
                            var last = viewport.toSurvey(down.position)
                            var erased = rubAt(last)
                            if (erased) strokeTick++

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!pointer.pressed) break
                                val here = viewport.toSurvey(pointer.position)
                                // Along the segment, not merely at its far end. A finger moving
                                // quickly is sampled every few frames, so at speed the gaps
                                // between samples are far wider than the eraser and a rub would
                                // come out dotted — taking out one stroke in three and leaving a
                                // wall that looks deliberately dashed.
                                val rubbedHere = rubAlong(last, here, toleranceInMetres, rub = ::rubAt)
                                // Redrawn as it happens rather than only once the finger lifts,
                                // the same as the pencil already does — a rub with nothing to
                                // show for it until you let go is one that looks like it did
                                // nothing at all.
                                if (rubbedHere) strokeTick++
                                erased = rubbedHere || erased
                                last = here
                                // Consumed so the sketch does not also scroll under the rub. The
                                // hot-corner pan detector sits outside this one and would
                                // otherwise take the same drag.
                                pointer.consume()
                            }

                            if (erased) currentOnSketchEdit()
                        }
                    }
                }

            else ->
                Modifier
                    // A tap on a cross-section opens it; a tap anywhere else leaves a dot.
                    // `detectDragGestures` waits for touch slop before firing anything, so a tap
                    // alone produces no stroke - both jobs therefore go in *one* detector rather
                    // than two: a second `pointerInput` here took the touch-down away from the drag
                    // detector below and stopped drawing working at all.
                    .pointerInput(*gestureKeys) {
                        detectTapGestures { offset ->
                            val at = viewport.toSurvey(offset)
                            val section =
                                if (options.crossSectionsAreTouchable) {
                                    findCrossSectionBodyAt(currentScene.sketch, at)
                                } else {
                                    null
                                }
                            if (section != null) {
                                currentOnOpenCrossSection(section)
                            } else {
                                // A path of one point. `finishPath` commits it as the original
                                // does, and the renderer draws a stroke with no length as a round
                                // cap — which is what a dot is.
                                editor.startPath(at)
                                editor.finishPath()
                                strokeTick++
                                currentOnSketchEdit()
                            }
                        }
                    }
                    .pointerInput(*gestureKeys) {
                        // Snapping is ported from `GraphView.considerSnapToSketchLine`, ends only:
                        // the start jumps on touch-down, and the finish appends the snapped point
                        // rather than moving the last one, exactly as the original does.
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

                        // Written out rather than `detectDragGestures`: that only calls
                        // onDragStart once the touch has moved past slop, with wherever it had
                        // reached by then — not the original touch-down — so the line began a
                        // few pixels late and a light, precise Pencil touch lost a visible chip
                        // off the start of every stroke. This starts the path at the true
                        // down position the moment slop is exceeded, then extends straight to
                        // wherever the pointer already is, so nothing between the two is lost.
                        //
                        // Slop is still checked, and still leaves a plain tap undrawn here: that
                        // is `detectTapGestures`'s job, above, and drawing a dot from both would
                        // double it.
                        //
                        // requireUnconsumed = false, as `detectDragGestures` itself takes it: a
                        // strict read here broke the long-press station menu, which watches the
                        // same down for `!isConsumed` while it waits out its own timeout, and two
                        // detectors both insisting on being first is not a fight either can win.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPoint = viewport.toSurvey(down.position)
                            var lastPoint = downPoint
                            var started = false

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pointer =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    // A hot corner or a cross-section's drag bar sits further in
                                    // on the same modifier chain and has already had its turn on
                                    // this event; a change it consumed is a gesture it has taken
                                    // over, and drawing from the same touch would pan or drag the
                                    // section *and* leave a line behind it.
                                    if (pointer.isConsumed) break
                                    if (!pointer.pressed) break

                                    if (!started) {
                                        val moved =
                                            (pointer.position - down.position).getDistance()
                                        if (moved < viewConfiguration.touchSlop) continue
                                        editor.startPath(snapped(downPoint))
                                        strokeTick++
                                        started = true
                                    }

                                    pointer.consume()
                                    lastPoint = viewport.toSurvey(pointer.position)
                                    editor.extendPath(lastPoint)
                                    strokeTick++
                                }

                                // `started` alone is not enough: another detector can have
                                // abandoned the stroke under this loop - a second finger reaching
                                // `detectModalMove`, or a long press taking the touch - and the
                                // loop then leaves on `isConsumed` with nothing active. Snapping
                                // in that state would *start* a stroke at the snap point, since
                                // `extendPath` begins one when none is in progress, and
                                // `finishPath` would commit it: a dot left on the end of a wall by
                                // a pan the surveyor made to get away from it.
                                if (started && editor.activePath != null) {
                                    // finishPath simplifies the stroke and pushes one undo step;
                                    // a stroke of fewer than two points is still committed, as in
                                    // the original, because a tap is how you draw a dot — though a
                                    // tap never reaches here at all, since it never exceeds slop.
                                    if (options.snapToLines) {
                                        editor.snapPointNear(lastPoint, snapWithin)
                                            ?.let { editor.extendPath(it) }
                                    }
                                    editor.finishPath()
                                    currentOnSketchEdit()
                                }
                            } finally {
                                // A gesture cancelled mid-stroke — a long press elsewhere taking
                                // the pointer, say, or the tool changing under it — otherwise
                                // leaves an unfinished stroke on the sketch; `detectDragGestures`'s
                                // own onDragCancel did the same cleanup. `finishPath` above already
                                // clears this on the ordinary path, so this only ever fires on the
                                // cancelled one. A reading arriving is not one of those any more:
                                // see the keys on `gestures`.
                                if (editor.activePath != null) {
                                    editor.abandonPath()
                                    strokeTick++
                                }
                            }
                        }
                    }
        }

    // Hold a station to get at it. See [detectLongPress] for why this is not detectTapGestures,
    // and why it sits between the tool's own detectors and the hot-corner one.
    val longPress =
        Modifier.pointerInput(*gestureKeys) {
            var held: String? = null
            detectLongPress(
                onHeld = { offset ->
                    val reach =
                        viewport.toSurveyDistance(SketchDefaults.SELECTION_SENSITIVITY_DP.dp.toPx())
                    held = currentScene.stationNearest(viewport.toSurvey(offset), reach)
                    if (held != null) {
                        // A stroke begun by the press that opened the menu is not a stroke anybody
                        // meant to draw.
                        editor.abandonPath()
                        strokeTick++
                    }
                    held != null
                },
                onReleased = {
                    held?.let(currentOnLongPressStation)
                    held = null
                },
            )
        }

    // Pick a cross-section up by its drag bar, whatever tool is in hand. `GraphView` switches tool
    // to MOVE_CROSS_SECTION for the touch when the bar is hit; switching `tool` mid-gesture isn't
    // open to us the same way, since every `pointerInput` here is keyed on it and the switch would
    // tear down the gesture it was meant to begin. A detector of its own instead: it takes the
    // touch only when the press lands on a bar, and otherwise consumes nothing.
    //
    // Placed after `longPress` and before `modalMove`: hot corners get first refusal, the bars
    // next, and the tool's own detectors last.
    val sectionHandle =
        if (options.legacyCrossSections) {
            // No bar is drawn in legacy mode, so there is nothing to grab.
            Modifier
        } else {
            Modifier.pointerInput(*gestureKeys) {
                val reach = CanvasSizes.CROSS_SECTION_HANDLE_TOUCH_HEIGHT_DP.dp.toPx()
                awaitEachGesture {
                    // requireUnconsumed: a hot corner has already claimed this touch, and a
                    // section parked in the corner must not steal the pan.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val detail =
                        findCrossSectionHandleAt(handleRects, down.position, reach)
                            ?: return@awaitEachGesture
                    // Only now, once a bar is actually under the finger. Consuming on every press
                    // would starve every other tool on the canvas.
                    down.consume()
                    sectionDrag = hold(SectionDragMode.MOVE, detail, viewport.toSurvey(down.position))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.isConsumed) {
                            // Something ahead of us took the touch - a second finger reaching
                            // `detectModalMove`, in practice. Drop the section where it was rather
                            // than committing a move the surveyor has stopped making.
                            sectionDrag = null
                            return@awaitEachGesture
                        }
                        if (!pointer.pressed) break
                        sectionDrag = sectionDrag?.movedTo(viewport.toSurvey(pointer.position))
                        pointer.consume()
                    }

                    if (sectionDrag?.commit(editor) == true) currentOnSketchEdit()
                    sectionDrag = null
                }
            }
        }

    // A wheel, or a laptop trackpad, which is the whole input story on the browser build at a
    // desk. Reported from there: a pinch on a Mac trackpad arrives as a wheel event with ctrl
    // held, so without handling it here the browser takes it as page zoom and the cave never
    // hears about it. Nothing in the Android app to port here; a phone has no wheel. The
    // convention taken is the one every desktop drawing tool uses: plain scroll pans, ctrl (or
    // cmd) and scroll zooms about the pointer.
    val wheel =
        Modifier.pointerInput(*gestureKeys) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    // The last change rather than the first: a scroll carries one, but taking the
                    // last is what the rest of Compose's own scroll handling does when a stray
                    // hover change is also in the event.
                    val change = event.changes.lastOrNull() ?: continue
                    val scroll = change.scrollDelta
                    if (scroll.x == 0f && scroll.y == 0f) continue
                    // In the pixels the platform actually reports; see [scrollUnitInPixels],
                    // which is two orders of magnitude apart between a browser and Swing.
                    val scrolled =
                        Coord2D(scroll.x * scrollUnitInPixels, scroll.y * scrollUnitInPixels)
                    val modifiers = event.keyboardModifiers
                    if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                        if (!options.pinchToZoom) continue
                        // Exponential, so a notch out and a notch back land where they started -
                        // a linear step does not, and the drawing walks away from you.
                        canvas.transformBy(
                            change.position.toCoord2D(),
                            Coord2D(0f, 0f),
                            exp(-scrolled.y * ZOOM_PER_SCROLLED_PIXEL),
                        )
                    } else {
                        // Negated: a scroll *down* means the paper goes up, which is a drag
                        // upwards, and `transformBy` takes the movement of the hand.
                        canvas.transformBy(change.position.toCoord2D(), scrolled.scale(-1f), 1f)
                    }
                    change.consume()
                }
            }
        }

    // Pan and zoom without leaving the current tool: see [detectModalMove]. Not installed over the
    // pan tool, which already does all of it with one finger and would end up handling a pinch
    // twice.
    val modalMove =
        if (tool == SketchTool.MOVE) {
            Modifier
        } else {
            Modifier.pointerInput(*gestureKeys) {
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

    // Ctrl+Z and Ctrl+Shift+Z (Cmd on a Mac, and Ctrl+Y as well for Windows), going to the same
    // [SketchEditor.undo] the toolbar's own arrows use - the three separate stacks the Android app
    // keeps, one per view, which is why this sits in the canvas rather than at the top of the app.
    val keyboardFocus = remember { FocusRequester() }
    // The canvas takes the keyboard when it appears, and takes it back whenever the tool changes:
    // a Compose button takes the focus when pressed, so picking the pencil off the toolbar moved
    // the keyboard to the pencil button and Ctrl+Z did nothing from then on. Keying on `tool`
    // brings it back to the paper the moment a tool is chosen; [keyboardFocusOnTouch] covers the
    // buttons that do not change it.
    LaunchedEffect(tool) {
        // requestFocus throws if the node is not attached yet, which is a race worth losing
        // quietly: the alternative is the whole drawing failing to appear because a key handler
        // could not be set up.
        runCatching { keyboardFocus.requestFocus() }
    }

    // Touching the drawing gives it the keyboard back, whatever took it - a colour swatch, a menu
    // that has closed, the browser's own address bar. Consumes nothing and is dispatched after
    // every other detector, so no tool can tell it is there.
    //
    // It also tells the controller when a touch is down, from the first finger landing until the
    // last one lifts, so nothing moves the view while a stroke is under way - the automatic re-fit
    // below, and `centreOn` following a station that a reading mid-stroke has just created. The
    // `finally` is what keeps a torn-down loop from leaving the view held still for good.
    val touchTracking =
        Modifier.pointerInput(canvas) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                runCatching { keyboardFocus.requestFocus() }
                canvas.touchBegan()
                try {
                    while (awaitPointerEvent().changes.any { it.pressed }) {
                        // Still down.
                    }
                } finally {
                    canvas.touchEnded()
                }
            }
        }

    fun onKey(event: KeyEvent): Boolean {
        // Key *down*, or every shortcut fires twice.
        if (event.type != KeyEventType.KeyDown) return false
        if (!event.isCtrlPressed && !event.isMetaPressed) return false
        val redo =
            (event.key == Key.Z && event.isShiftPressed) || event.key == Key.Y
        val undo = event.key == Key.Z && !event.isShiftPressed
        if (!undo && !redo) return false
        // Taken whether or not there was anything left on the stack: an undo at the bottom of the
        // pile is still the app's key, and letting it through to the browser at that one moment
        // would be a surprise nobody could explain.
        if (if (undo) editor.undo() else editor.redo()) currentOnSketchEdit()
        strokeTick++
        return true
    }

    // Clipping is stated rather than inherited. `drawGrid` starts its first line at
    // `floor(topLeft.y / spacing) * spacing`, which is at or above the top of the view - so
    // something up the tree is already clipping it today. But that is a layout change away from
    // not being true, and the failure it would produce is the cave painting over the app bar. One
    // modifier is a cheap way not to depend on an ancestor for that.
    Box(
        modifier =
            modifier
                .clipToBounds()
                .then(touchTracking)
                // Order is load-bearing. A key event is dispatched to the focused node and then
                // *up* its ancestors, and `focusable()` is the node that holds the focus - so a
                // handler placed after it is a descendant of the focus target and never hears a
                // thing. Written that way round first, and the symptom was a canvas that took the
                // keyboard focus and ignored every key.
                .focusRequester(keyboardFocus)
                .onKeyEvent(::onKey)
                .focusable()
                .then(gestures)
                .then(longPress)
                .then(sectionHandle)
                .then(modalMove)
                .then(wheel)
    ) {
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
            // Re-fit as the survey grows, until the surveyor pans or zooms - live surveying starts
            // from a single station and adds a leg every few readings, and a fit that happened once
            // would leave the cave walking off the edge of the screen. Once they have moved the
            // view themselves it is theirs, and re-fitting under them would be rude.
            //
            // The trigger is the *centreline's* extent, not the whole scene's: a drawn stroke
            // enlarges the scene too, and re-framing the view because somebody drew near the edge
            // would move the paper out from under the pen.
            //
            // And not while a touch is down, for the same reason from the other side: a leg that
            // lands mid-stroke grows the centreline, and re-fitting to it then would move the
            // paper under a pen that has not moved. The fit happens on the frame after the finger
            // lifts instead; `touchEnded` asks for that frame.
            if (
                !canvas.isTouched &&
                    fit.shouldFitTo(scene.surveyBounds) &&
                    size.width > 0f &&
                    size.height > 0f
            ) {
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
                isPlan = isPlan,
                projection = projection,
                handleRects = handleRects,
                // A missing heading means no compass on this device, so the arrow is drawn as a
                // label: north is up, because that is where the plan puts it.
                headingDegrees = headingDegrees ?: deviceHeading.value ?: 0f,
            )
        }

    }
}

/**
 * One drawn segment of the centreline, with the three things the display options ask about it.
 *
 * Settled here rather than at draw time: those are questions about the *survey*, and by the time a
 * segment reaches the canvas it is a pair of screen points with no leg behind it.
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
    /** The stations carrying a comment, marked as `GraphView.drawStations` marks them on the plan. */
    val commentedStations: Set<String>,
    val originName: String,
    val surveyName: String,
    /**
     * `GraphView.setCachedStats`: the survey's length and vertical range, for the legend line.
     *
     * Cached with the scene rather than computed in the draw loop for the reason the Android app
     * caches them — both walk the whole survey, and the loop runs on every frame of a drag.
     */
    val surveyLength: Float = 0f,
    val surveyHeight: Float = 0f,
    /** Everything drawn, centreline and ink alike — what the opening zoom is fitted to. */
    val bounds: Bounds,
    /**
     * The centreline alone, kept apart from [bounds]: re-framing the view answers "has the
     * *survey* grown", not "has anything on screen moved", which includes an in-progress stroke.
     */
    val surveyBounds: Bounds,
) {
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
                        // Identity, not equality: Leg has no equals, so this asks whether the leg
                        // *is* one of the active station's.
                        attachedToActive = active.onwardLegs.any { it === leg },
                        // Splays included: a wall shot that was the last thing recorded is marked
                        // too.
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
                space.stationMap.keys.filter { it.hasComment() }.map { it.name }.toSet(),
                survey.origin.name,
                survey.name,
                SurveyStats.totalLength(survey),
                SurveyStats.heightRange(survey),
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
        fun forCrossSection(
            detail: CrossSectionDetail,
            working: Sketch,
            /**
             * The survey the section belongs to, for the legend line `CrossSectionView` draws.
             * Null draws no legend, which is what a caller with nothing to name wants.
             */
            survey: Survey? = null,
        ): SurveyScene {
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
                activeStationName = station.name,
                // No comment icon, and no "(survey name)" after the origin's own label: those
                // belong to the plan, not to the profile of the passage at this station. The
                // legend line below is a different thing, and `CrossSectionView` does draw it.
                commentedStations = emptySet(),
                originName = "",
                surveyName = survey?.name ?: "",
                surveyLength = survey?.let(SurveyStats::totalLength) ?: 0f,
                surveyHeight = survey?.let(SurveyStats::heightRange) ?: 0f,
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
 * `AUTO_FIT_SCREEN_FRACTION` — 0.4 — of the smaller screen dimension: the wall outline is drawn
 * *outside* the splay ends, so a view fitted tightly to the splays would open with nowhere to
 * draw it. Expressed as a box rather than a zoom because that is what this canvas's fit takes.
 *
 * A station with no splays gets a fixed few metres instead, rather than the Java's fixed pixels
 * per metre, so the view is the same passage-sized area on every phone.
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

/*
 * A data class, and that is load-bearing rather than tidiness: [DemoState.displayOptions] is a
 * `get()` that builds a fresh one every read, so identity changes on every recomposition. Value
 * equality is what lets the gesture loops key on the whole object instead of restarting every
 * detector on the canvas sixty times a second.
 */
data class DisplayOptions(
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
    /** `SketchPreferences.Toggle.FADE_NON_ACTIVE`, off by default as it is in the app. */
    val fadeNonActive: Boolean = AppPreferences.DEFAULT_FADE_NON_ACTIVE,
    /** `pref_highlight_latest_leg`, on by default. */
    val highlightLatestLeg: Boolean = AppPreferences.DEFAULT_HIGHLIGHT_LATEST_LEG,
    /**
     * Whether a stamped water symbol comes out blue whatever the brush is. Not a display option
     * either — like [snapToLines] it changes what is created, not what is shown.
     */
    val blueWater: Boolean = AppPreferences.DEFAULT_BLUE_WATER,
    /** Whether cross-sections are drawn on the plan, and so whether they can be tapped. */
    val showCrossSections: Boolean = AppPreferences.DEFAULT_SHOW_CROSS_SECTIONS,
    /** Whether two fingers zoom. The two-fingered *pan* is [twoFingerMove]. */
    val pinchToZoom: Boolean = AppPreferences.DEFAULT_PINCH_TO_ZOOM,
    /** Whether the north arrow is drawn on the plan. */
    val showCompass: Boolean = AppPreferences.DEFAULT_SHOW_COMPASS,
    /** `pref_delete_path_fragments`. */
    val deletePathFragments: Boolean = SketchDefaults.DELETE_PATH_FRAGMENTS_DEFAULT,
    /**
     * How big everything is drawn.
     *
     * On the display options rather than read from the preferences here, because this canvas is
     * also driven by the headless renderer and by tests that hold no preferences file.
     */
    val style: SketchStyle = SketchStyle.DEFAULT,
    /**
     * Whether a cross-section is drawn the old way: the splay star and a dashed line to it, with
     * no frame, no drag bar and no tap-to-edit. `pref_legacy_cross_sections`, default off.
     */
    val legacyCrossSections: Boolean = AppPreferences.DEFAULT_LEGACY_CROSS_SECTIONS,
) {
    /**
     * Whether a cross-section on the plan is there to be found by a finger.
     *
     * One property rather than the same pair of conditions at four hit-test sites: it is invisible
     * because the whole sketch is hidden, because sections are, or because legacy mode draws no
     * frame or handle to mark it out as an object at all.
     */
    val crossSectionsAreTouchable: Boolean
        get() = showSketch && showCrossSections && !legacyCrossSections
}

/** `GraphView.FADED_ALPHA`, which is `0xff / 5` of full. */
const val FADED_ALPHA = 0.2f

/**
 * How much a pixel of ctrl-scroll zooms the drawing, as the exponent of e.
 *
 * Reported from a MacBook: zooming in browser wasn't very fast, needing a lot of scrolling. A
 * wheel notch in a browser is 100 pixels, and at the original 0.0015 that was a factor of only
 * about 1.16; at 0.003 the same notch is a factor of about 1.35, so a firm two-finger pinch or a
 * few clicks of a mouse wheel now gets from one end of a cave to the other.
 *
 * Not 0.006, which this was for one commit: exponentiated across a whole pinch that reaches a
 * factor of four or more in a single motion, that threw most of what was on screen out of frame.
 * `desktop.mjs`'s own checks caught it: a scripted "zoom out to make room to pan" step landed at
 * 3% of the original scale instead of the ~40% it was written expecting. Halving the increase
 * rather than reverting it keeps the improvement while staying inside a realistic range.
 *
 * Exponential rather than multiplied, so that zooming out and back in returns to the scale you
 * started at instead of drifting a little each time.
 *
 * This only speeds up Chrome and Firefox, which report a trackpad pinch as a real `wheel` event
 * with a physical `deltaY` this multiplies. Safari reports a pinch as its own `gesturechange`
 * event with an exact scale ratio instead, and [keepPinchesInsideTheApp] deliberately *divides*
 * by this same constant to turn that ratio into a wheel delta before this multiplies it back out
 * - the two cancel, on purpose, so a Safari pinch always reproduces the ratio the fingers made,
 * whatever this number is.
 *
 * Not inside `CanvasSizes` with the rest: the browser host also needs this number, to turn
 * Safari's own pinch events into the wheel events this reads.
 */
internal const val ZOOM_PER_SCROLLED_PIXEL = 0.003f

/** `GraphView.DASHED_LINE_INTERVAL_DP`. */
private const val DASH_INTERVAL_DP = 4f

/**
 * Every size on the drawing, in dp.
 *
 * A plain number in a `DrawScope` is a *physical pixel*: on a phone at three device pixels to the
 * dp the whole cave would come out a third of the size it was drawn at, hairline centreline and
 * pinhead stations, while the labels beside them — measured in `sp`, which Compose does scale —
 * stayed the size they should be. The Android app converts all of these through `dpToPixels`.
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
    const val AIMING_LINE_DP = 1f
    /** Where a station's name sits relative to its dot. */
    const val LABEL_RIGHT_DP = 5f
    const val LABEL_UP_DP = 14f

    // The frame round a cross-section on the plan, sized to match `GraphView`'s own constants.
    const val CROSS_SECTION_CONNECTOR_WIDTH_DP = 2f
    const val CROSS_SECTION_INDICATOR_WIDTH_DP = 2f
    const val CROSS_SECTION_BORDER_WIDTH_DP = 2f
    const val CROSS_SECTION_BORDER_PADDING_MIN_DP = 4f
    const val CROSS_SECTION_BORDER_PADDING_MAX_DP = 16f
    const val CROSS_SECTION_BORDER_PADDING_FRACTION = 0.05f
    const val CROSS_SECTION_BORDER_CORNER_RADIUS_DP = 6f
    const val CROSS_SECTION_HANDLE_WIDTH_DP = 8f

    /**
     * How tall the drag bar is to a finger, as opposed to an eye.
     *
     * The bar is drawn 8dp tall, well under the 48dp Android's own guidance asks of a touch
     * target, so this port grows the *hit* rectangle to 24dp before testing it, and only upwards:
     * everything below the bar is the section's own frame, where a press means "open this section
     * for drawing", so growing downwards would buy one gesture by breaking another. A deliberate
     * departure, not a port of anything.
     */
    const val CROSS_SECTION_HANDLE_TOUCH_HEIGHT_DP = 24f

    const val CROSS_SECTION_HANDLE_GRIP_WIDTH_DP = 2f
    const val CROSS_SECTION_HANDLE_GRIP_SPACING_DP = 5f
    const val CROSS_SECTION_HANDLE_GRIP_LENGTH_FRACTION = 0.45f
}

/**
 * How far off the screen a station can be and still put something on it: enough to cover the
 * longest name a surveyor is likely to type, not just the dot's own radius.
 */
private const val STATION_CULL_MARGIN_DP = 120f

/** How far in from the top-right corner the eraser's reach is shown. */
private const val ERASER_INSET_DP = 40f

/** The faint metre grid. */
private const val GRID_STROKE_DP = 1f

/** `gridPaint.setStrokeWidth(... ? 3 : 1)`: what every tenth line is drawn at. */
private const val MAJOR_GRID_STROKE_DP = 3f

/** `GraphView.BOX_SIZE`: "every grid box is 10 units square". */
private const val GRID_BOX_LINES = 10

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
 * four corners rather than a square.
 *
 * The diameter is the *setting*, `pref_station_diameter`. This used to read the 10dp default
 * constant instead, so a surveyor who enlarged their stations for cold hands got brackets that
 * stayed put and ended up drawn inside the cross they were meant to frame.
 */
private fun DrawScope.drawActiveStationHighlight(
    centre: Offset,
    palette: Palette,
    stationDiameterDp: Float,
) {
    val diameter = stationDiameterDp.dp.toPx() * 1.1f
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
 * The mark on a station that says a cross-section was taken there, and which way it faces.
 *
 * `GraphView.drawCrossSectionIndicator`, which this port did not have. It matters more here than
 * it looks, because a section is drawn wherever it was dragged to - possibly right across the
 * chamber - and this is the only thing left at the station itself to say one exists. Without it a
 * plan gives you no way to tell which stations have been sectioned without tapping each in turn.
 *
 * It is a line one metre long lying *in* the section's plane, with a small arrowhead at one end
 * pointing along the bearing, so it reads as "cut here, looking that way". The Java gets the plane
 * for free with a trick worth spelling out: a compass bearing of `a` degrees is the screen
 * direction `(sin a, -cos a)`, and the section's plane is that turned a right angle, which is
 * `(cos a, sin a)` - so taking the cosine and sine of the bearing directly gives the plane rather
 * than the bearing. The arrow then uses `a - 90`, which turns it back.
 *
 * Drawn at half strength, as the Java's `alpha / 2` does: it is an annotation on the station, not
 * a shot, and at full strength on a busy junction it reads as another splay.
 */
private fun DrawScope.drawCrossSectionIndicator(
    stationOnScreen: Offset,
    angleDegrees: Float,
    pixelsPerMetre: Float,
    palette: Palette,
) {
    val radians = angleDegrees * PI.toFloat() / 180f
    val alongPlane = Offset(cos(radians), sin(radians))
    val half = pixelsPerMetre / 2f
    val start = stationOnScreen - alongPlane * half
    val end = stationOnScreen + alongPlane * half
    val ink = palette.symbol.copy(alpha = FADED_INDICATOR_ALPHA)

    drawLine(ink, start, end, CanvasSizes.CROSS_SECTION_INDICATOR_WIDTH_DP.dp.toPx())

    // The arrowhead: a thin triangle off the near end of the plane line, pointing along the
    // bearing. Its length is 0.4 of the line and its base 0.05 of it, both from the Java.
    val alongBearing = Offset(cos(radians - PI.toFloat() / 2f), sin(radians - PI.toFloat() / 2f))
    val tip = start + alongBearing * (pixelsPerMetre * ARROW_LENGTH_FRACTION)
    val innerCorner = start + alongPlane * (pixelsPerMetre * ARROW_BASE_FRACTION)
    drawPath(
        Path().apply {
            moveTo(innerCorner.x, innerCorner.y)
            lineTo(start.x, start.y)
            lineTo(tip.x, tip.y)
            close()
        },
        ink,
    )
}

/** `GraphView`'s `alpha / 2` for the cross-section indicator. */
private const val FADED_INDICATOR_ALPHA = 0.5f

/** The arrowhead's length and base, as fractions of the indicator line. */
private const val ARROW_LENGTH_FRACTION = 0.4f
private const val ARROW_BASE_FRACTION = 0.05f

/**
 * The mark beside a station's name that says somebody wrote a note there.
 *
 * `GraphView.drawStations` draws a bitmap; this draws the same idea with three strokes, since the
 * port has no icon assets. Sized off the station diameter, as the Java sizes its icon.
 */
private fun DrawScope.drawCommentMark(topLeft: Offset, size: Float, palette: Palette) {
    val stroke = CanvasSizes.THIN_STROKE_DP.dp.toPx()
    drawRect(
        palette.stationLabel,
        topLeft = topLeft,
        size = Size(size * COMMENT_MARK_WIDTH_FRACTION, size),
        style = Stroke(stroke),
    )
    val inset = size * COMMENT_MARK_INSET_FRACTION
    val right = topLeft.x + size * COMMENT_MARK_WIDTH_FRACTION - inset
    for (line in 1..2) {
        val y = topLeft.y + size * line / 3f
        drawLine(palette.stationLabel, Offset(topLeft.x + inset, y), Offset(right, y), stroke)
    }
}

private const val COMMENT_MARK_WIDTH_FRACTION = 0.8f
private const val COMMENT_MARK_INSET_FRACTION = 0.2f

/**
 * The frame drawn round a cross-section sitting on the plan, and the bar you drag it by.
 *
 * `GraphView.drawCrossSectionBorder`. The rectangle is the section's own bounding box — splays,
 * sub-sketch and a forced minimum, all from [boundsOf] — padded by a twentieth of its shorter side
 * clamped into 4..16dp, with room above for the handle. Returns the border rectangle so the
 * connector can be clipped to it.
 */
private fun DrawScope.drawCrossSectionBorder(
    topLeftOnScreen: Offset,
    bottomRightOnScreen: Offset,
    palette: Palette,
): Rect {
    val contentWidth = bottomRightOnScreen.x - topLeftOnScreen.x
    val contentHeight = bottomRightOnScreen.y - topLeftOnScreen.y
    val scaledPadding =
        min(contentWidth, contentHeight) * CanvasSizes.CROSS_SECTION_BORDER_PADDING_FRACTION
    val padding =
        max(
            CanvasSizes.CROSS_SECTION_BORDER_PADDING_MIN_DP.dp.toPx(),
            min(CanvasSizes.CROSS_SECTION_BORDER_PADDING_MAX_DP.dp.toPx(), scaledPadding),
        )
    val topPadding = padding + CanvasSizes.CROSS_SECTION_HANDLE_WIDTH_DP.dp.toPx()
    val rect =
        Rect(
            left = topLeftOnScreen.x - padding,
            top = topLeftOnScreen.y - topPadding,
            right = bottomRightOnScreen.x + padding,
            bottom = bottomRightOnScreen.y + padding,
        )
    val corner = CanvasSizes.CROSS_SECTION_BORDER_CORNER_RADIUS_DP.dp.toPx()
    drawRoundRect(
        palette.crossSectionFrame,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(CanvasSizes.CROSS_SECTION_BORDER_WIDTH_DP.dp.toPx()),
    )
    return rect
}

/**
 * The drag bar along the top of a section's frame: a filled strip with rounded top corners, three
 * grip marks down the middle. `GraphView.drawCrossSectionHandle`.
 *
 * Returns the bar's rectangle, in screen coordinates, so the caller can record where it ended up:
 * a hit test that recomputed this rectangle from the sketch instead would be a second copy of the
 * padding, the scale and the projection, free to drift from the one that drew the bar.
 */
private fun DrawScope.drawCrossSectionHandle(borderRect: Rect, palette: Palette): Rect {
    val handleHeight = CanvasSizes.CROSS_SECTION_HANDLE_WIDTH_DP.dp.toPx()
    val corner = CanvasSizes.CROSS_SECTION_BORDER_CORNER_RADIUS_DP.dp.toPx()
    val handleRect =
        Rect(borderRect.left, borderRect.top, borderRect.right, borderRect.top + handleHeight)

    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    handleRect,
                    topLeft = CornerRadius(corner, corner),
                    topRight = CornerRadius(corner, corner),
                    bottomRight = CornerRadius.Zero,
                    bottomLeft = CornerRadius.Zero,
                )
            )
        },
        palette.crossSectionFrame,
    )

    val centreX = handleRect.center.x
    val centreY = handleRect.center.y
    val gripHalf = handleHeight * CanvasSizes.CROSS_SECTION_HANDLE_GRIP_LENGTH_FRACTION / 2f
    val spacing = CanvasSizes.CROSS_SECTION_HANDLE_GRIP_SPACING_DP.dp.toPx()
    for (gripX in listOf(centreX - spacing, centreX, centreX + spacing)) {
        drawLine(
            palette.onCrossSectionFrame,
            Offset(gripX, centreY - gripHalf),
            Offset(gripX, centreY + gripHalf),
            CanvasSizes.CROSS_SECTION_HANDLE_GRIP_WIDTH_DP.dp.toPx(),
            StrokeCap.Round,
        )
    }

    return handleRect
}

/**
 * Rubs along the segment from [from] to [to], a step at a time, and says whether anything went.
 *
 * [from] is *not* rubbed — the caller has already done that, either as the touch-down or as the
 * previous move's endpoint — so a stationary finger costs one call and no repeats. [to] always is,
 * so the rub reaches exactly as far as the finger did.
 *
 * The step is the eraser's own radius, which is what makes the rub continuous rather than dotted: a
 * fast-moving finger is sampled far less often than the eraser is wide, and rubbing only at the
 * sampled points would take out one stroke in three and leave something that looks deliberately
 * dashed.
 *
 * [maxSteps] bounds the work per event. It only bites on a flick across a zoomed-out cave, where
 * the alternative is thousands of nearest-detail searches inside one frame; when it does, the rub
 * is coarser than the eraser rather than absent, which is the right way round.
 */
internal fun rubAlong(
    from: Coord2D,
    to: Coord2D,
    stepInMetres: Float,
    maxSteps: Int = 64,
    rub: (Coord2D) -> Boolean,
): Boolean {
    val travel = to - from
    val distance = travel.mag()
    var erased = false
    if (stepInMetres > 0f && distance > stepInMetres) {
        val wanted = (distance / stepInMetres).toInt()
        val steps = if (wanted > maxSteps) maxSteps else wanted
        for (step in 1..steps) {
            if (rub(from + travel.scale(step.toFloat() / (steps + 1)))) erased = true
        }
    }
    if (rub(to)) erased = true
    return erased
}

/**
 * Where a line from [from] to [to] first meets [rect], or null if [from] is already inside it.
 *
 * `GraphView.clipSegmentToRectBoundary`. The connector runs from the station to the section's
 * centre; without this it would be drawn straight across the section's own frame and through
 * whatever the surveyor drew inside it. Stopping at the border says the same thing and covers
 * nothing.
 */
internal fun clipSegmentToRectBoundary(from: Offset, to: Offset, rect: Rect): Offset? {
    if (rect.contains(from)) return null
    val dx = to.x - from.x
    val dy = to.y - from.y
    var enter = 0f
    if (dx != 0f) {
        enter = max(enter, min((rect.left - from.x) / dx, (rect.right - from.x) / dx))
    }
    if (dy != 0f) {
        enter = max(enter, min((rect.top - from.y) / dy, (rect.bottom - from.y) / dy))
    }
    if (enter <= 0f || enter >= 1f) return to
    return Offset(from.x + enter * dx, from.y + enter * dy)
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

    val minor = GRID_STROKE_DP.dp.toPx()
    val major = MAJOR_GRID_STROKE_DP.dp.toPx()

    // `drawGrid` widens every tenth line: `gridPaint.setStrokeWidth(n % BOX_SIZE == 0 ? 3 : 1)`,
    // where n is the line's ordinal in survey coordinates, not its position on screen. That is
    // what makes the paper read as ten-metre boxes divided into metres rather than as an
    // undifferentiated mesh — and it is what tells a surveyor the scale at a glance.
    var line = floor(topLeft.x / spacing)
    while (line * spacing <= bottomRight.x) {
        val screenX = viewport.toScreen(Coord2D(line * spacing, topLeft.y)).x
        val stroke = if (line.toInt() % GRID_BOX_LINES == 0) major else minor
        drawLine(palette.grid, Offset(screenX, 0f), Offset(screenX, size.height), stroke)
        line += 1f
    }

    line = floor(topLeft.y / spacing)
    while (line * spacing <= bottomRight.y) {
        val screenY = viewport.toScreen(Coord2D(topLeft.x, line * spacing)).y
        val stroke = if (line.toInt() % GRID_BOX_LINES == 0) major else minor
        drawLine(palette.grid, Offset(0f, screenY), Offset(size.width, screenY), stroke)
        line += 1f
    }
}

/**
 * The cross-section whose drag bar is under [pointOnScreen], or null. `GraphView`'s
 * `findCrossSectionHandleAt`.
 *
 * [handleRects] is filled by the draw pass, so this asks about the bars actually on the screen
 * rather than recomputing where they ought to be: everything that stops a bar being drawn also
 * stops it being grabbable, for free.
 *
 * [minimumHeightPx] is the departure described on
 * [CanvasSizes.CROSS_SECTION_HANDLE_TOUCH_HEIGHT_DP]: a rectangle shorter than this is grown
 * upwards, away from the section's own body, until it is that tall.
 */
internal fun findCrossSectionHandleAt(
    handleRects: Map<CrossSectionDetail, Rect>,
    pointOnScreen: Offset,
    minimumHeightPx: Float,
): CrossSectionDetail? {
    for ((detail, rect) in handleRects) {
        val reachable =
            if (rect.height >= minimumHeightPx) rect
            else Rect(rect.left, rect.bottom - minimumHeightPx, rect.right, rect.bottom)
        if (reachable.contains(pointOnScreen)) return detail
    }
    return null
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
    /**
     * Which way the cave is being looked at. Only a cross-section's splays need it, to ask
     * `isLegInPlane` the same question the Java's `drawLegs` asks of every other segment.
     */
    projection: Projection2D = Projection2D.PLAN,
    /**
     * Where each cross-section's drag bar was drawn, in screen coordinates, read by the gesture
     * that lets one be picked up. Cleared here on every frame, whatever the display options say: a
     * section hidden or scrolled out of view must not leave a live handle behind at last frame's
     * coordinates.
     */
    handleRects: MutableMap<CrossSectionDetail, Rect>? = null,
    /** Which way the top of the screen is pointing, or zero where nothing can say. */
    headingDegrees: Float = 0f,
) {
    val palette = if (options.darkMode) DarkPalette else LightPalette

    handleRects?.clear()

    drawRect(palette.background)

    fun project(coord: Coord2D): Offset = viewport.toScreen(coord)

    if (options.showGrid) {
        drawGrid(viewport, palette)
    }

    // The screen, in the coordinates a projected segment arrives in. `GraphView.isLineOnCanvas`
    // tests against the canvas exactly, with no margin.
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

    // `GraphView.drawDashedLine`, used for a foreshortened leg and for the line joining a
    // cross-section to its station.
    fun drawDashes(from: Offset, to: Offset, colour: Color) {
        val dashLength = DASH_INTERVAL_DP.dp.toPx()
        val width = CanvasSizes.CROSS_SECTION_CONNECTOR_WIDTH_DP.dp.toPx()
        for ((dashFrom, dashTo) in dashesAlong(from.toCoord2D(), to.toCoord2D(), dashLength)) {
            drawLine(colour, dashFrom.toOffset(), dashTo.toOffset(), width, StrokeCap.Round)
        }
    }

    // The magenta is tested here as well as on the legs, since a wall shot can be the last thing
    // taken too - drawing splays in their own loop is what makes it easy to miss.
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
    // Which stations carry a cross-section, and at what bearing - with the rotate gesture's live
    // angle in place of the stored one, so the mark on the station swings with the section it
    // belongs to rather than snapping to it when the finger lifts.
    //
    // Drawn whatever the sketch and cross-section toggles say, which is what `GraphView` does:
    // `drawStations` reads the sketch directly and the two toggles gate `drawSketch` and
    // `drawCrossSections`. It looks like an oversight and works out well - with sections hidden to
    // clear the page you can still see which stations have one - so it is reproduced rather than
    // tidied.
    val sectionAngles =
        if (scene.sketch.crossSectionDetails.isEmpty()) {
            emptyMap()
        } else {
            buildMap {
                for (detail in scene.sketch.crossSectionDetails) {
                    val shown =
                        if (sectionDrag != null && sectionDrag.detail === detail) {
                            sectionDrag.preview()
                        } else {
                            detail
                        }
                    put(shown.station.name, shown.crossSection.angle)
                }
            }
        }

    for ((name, coord) in scene.stations) {
        val centre = project(coord)
        // Zoomed into one passage, almost every station of a real survey is off screen, and a
        // station is not only a dot: if labels are showing, each one measures a piece of text,
        // which is much more work than the circle. The Android app walks them all.
        if (whollyOutside(centre.toCoord2D(), screenTopLeft, screenBottomRight, stationMargin)) {
            continue
        }
        val isActive = name == scene.activeStationName
        // Asked per station, rather than the Java's approach of flipping a paint's alpha to solid
        // once it reaches the active station and never setting it back.
        val stationColour =
            if (options.fadeNonActive && !isActive) {
                palette.station.copy(alpha = FADED_ALPHA)
            } else {
                palette.station
            }
        // A cross, as `GraphView.drawStationCross` draws it: two lines through the point, each
        // the full station diameter long, at `STATION_STROKE_WIDTH_DP` - rather than a filled dot,
        // which at the default ten dp would hide the ends of every leg meeting there, exactly the
        // junction a surveyor is trying to read.
        val arm = options.style.stationRadiusDp.dp.toPx()
        val stationStroke = SketchDefaults.STATION_STROKE_WIDTH_DP.dp.toPx()
        drawLine(
            stationColour,
            Offset(centre.x, centre.y - arm),
            Offset(centre.x, centre.y + arm),
            stationStroke,
        )
        drawLine(
            stationColour,
            Offset(centre.x - arm, centre.y),
            Offset(centre.x + arm, centre.y),
            stationStroke,
        )
        if (isActive) {
            drawActiveStationHighlight(centre, palette, options.style.stationDiameterDp)
        }
        // What sits to the right of the station: its name, then a mark for each thing it carries,
        // spaced off the station's own diameter. Unlike the Java, the name sits a little above the
        // station rather than on the same row through it, keeping it off the centreline at a
        // junction.
        val markSize = options.style.stationDiameterDp.dp.toPx()
        var nextX = centre.x + CanvasSizes.LABEL_RIGHT_DP.dp.toPx()
        val labelTop = centre.y - CanvasSizes.LABEL_UP_DP.dp.toPx()
        val labelsVisible =
            options.showStationLabels && viewport.pixelsPerMetre > LABEL_VISIBILITY_PIXELS_PER_METRE

        if (labelsVisible) {
            // The origin says which survey it is the origin of, as the Java does.
            val label =
                if (name == scene.originName && scene.surveyName.isNotEmpty()) {
                    "$name (${scene.surveyName})"
                } else {
                    name
                }
            val layout =
                textMeasurer.measure(
                    label,
                    TextStyle(
                        color = palette.stationLabel,
                        fontSize = options.style.stationLabelSizeSp.sp,
                        fontFamily = fontFamily,
                    ),
                )
            drawText(layout, topLeft = Offset(nextX, labelTop))
            nextX += layout.size.width + markSize / 2f
        }

        if (name in scene.commentedStations) {
            drawCommentMark(Offset(nextX, centre.y - markSize / 2f), markSize, palette)
            nextX += markSize + markSize / 2f
        }

        sectionAngles[name]?.let { angle ->
            drawCrossSectionIndicator(centre, angle, viewport.pixelsPerMetre, palette)
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

        // The UIS artwork itself, drawn from the path data the symbols carry. It used to be a
        // small circle standing in for "a symbol is here", because the app's artwork is SVG and
        // nothing here could read it; parseSvgPath can.
        for (symbol in scene.sketch.symbolDetails) {
            val artwork = Symbol.byTherionName(symbol.symbolName)?.let { symbolPaths[it] }
            val colour = symbol.getDrawColour(options.darkMode)
            if (!colour.isDrawable) continue
            val centre = project(symbol.position)

            if (artwork == null) {
                // A symbol from a newer version of the app: better a mark than nothing.
                drawCircle(
                    palette.symbol,
                    radius = CanvasSizes.SYMBOL_FALLBACK_RADIUS_DP.dp.toPx(),
                    center = centre,
                    style = Stroke(CanvasSizes.THIN_STROKE_DP.dp.toPx()),
                )
                continue
            }

            // Move to where the stamp goes, turn it, then scale the 40-unit grid down to the
            // stamp's size in metres and on to pixels. No fourth step shifting the artwork by half
            // a box: `Symbol.toPath` builds it centred on the origin already, which is what lets
            // the rotate above turn it on the spot. `SymbolPalette` draws these same paths the
            // same way.
            //
            // There used to be such a step, and it centred the artwork a second time. It was right
            // when it was written — `toPath` then returned a box running from (0, 0) to (40, 40) —
            // and was left behind when `toPath` was changed to centre its own output, which is the
            // ordinary way a fix outlives its cause. The cost was every stamp in every survey
            // drawn half its own size up and to the left of the point it was stamped at, turning
            // about a corner rather than on the spot. Everything else agreed on the centre all
            // along: `GraphView` (`offset = size / 2f`, and a `RotateDrawable` pivoted at
            // 0.5, 0.5), the SVG and Therion exports, and `SymbolDetail.getDistanceFrom` — so the
            // eraser was reaching for symbols where this was not drawing them.
            // `SymbolStampCentringTest` measures the ink and holds it here.
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
        val sections =
            if (options.showCrossSections) scene.sketch.crossSectionDetails else emptyList()
        // Built once per frame and only when there is a section to join to a station: this is a
        // map the size of the survey, allocated inside the drawing of every frame, and a big cave
        // being panned is the worst moment to be doing that for nothing.
        val stationPositions = if (sections.isEmpty()) emptyMap() else scene.stations.toMap()
        val sectionScale = scene.sketch.crossSectionScale
        for (detail in sections) {
            val dragged = sectionDrag != null && sectionDrag.detail === detail
            val shown = if (dragged) sectionDrag.preview() else detail
            val centre = project(shown.position)

            // The splay star, in the ordinary splay colour and width like any other splay. A
            // section being dragged keeps the indicator colour instead, which is this port's own
            // and not in the Java: alpha, which the original changes while a section moves, reads
            // as nothing at all on a phone in a cave.
            val starColour = if (dragged) palette.symbol else palette.splay
            if (options.showSplays) {
                for ((leg, line) in shown.crossSection.getProjection().legMap) {
                    val end = line.end.scale(sectionScale)
                    val to =
                        Offset(
                            centre.x + end.x * viewport.pixelsPerMetre,
                            centre.y + end.y * viewport.pixelsPerMetre,
                        )
                    val width = options.style.splayWidthDp.dp.toPx()
                    if (projection.isLegInPlane(leg)) {
                        drawLine(starColour, centre, to, width, StrokeCap.Round)
                    } else {
                        val dashLength = DASH_INTERVAL_DP.dp.toPx()
                        for ((dashFrom, dashTo) in
                            dashesAlong(centre.toCoord2D(), to.toCoord2D(), dashLength)) {
                            drawLine(
                                starColour,
                                dashFrom.toOffset(),
                                dashTo.toOffset(),
                                width,
                                StrokeCap.Round,
                            )
                        }
                    }
                }
            }

            // And the centre: the station the section was taken at, drawn where the section was
            // put rather than where the station is.
            val sectionArm = options.style.stationRadiusDp.dp.toPx()
            val sectionStroke = SketchDefaults.STATION_STROKE_WIDTH_DP.dp.toPx()
            val markColour = if (dragged) palette.symbol else palette.station
            drawLine(
                markColour,
                Offset(centre.x, centre.y - sectionArm),
                Offset(centre.x, centre.y + sectionArm),
                sectionStroke,
            )
            drawLine(
                markColour,
                Offset(centre.x - sectionArm, centre.y),
                Offset(centre.x + sectionArm, centre.y),
                sectionStroke,
            )

            // The passage outline drawn *inside* the section, on the plan where the section sits.
            // Only the paths: this port's section editor offers move, draw and erase and no way to
            // place a symbol or a label, so `symbolDetails` and `textDetails` are always empty
            // here. Worth knowing if that ever changes, because `scale` grows a symbol *in place*
            // rather than moving it, which is wrong for this transform.
            for (stroke in shown.sketch.pathDetails) {
                if (stroke.path.size < 2) continue
                val ink = stroke.getDrawColour(options.darkMode)
                if (!ink.isDrawable) continue
                drawPolyline(
                    stroke.path.map { project(it.scale(sectionScale) + shown.position) },
                    Color(ink.intValue),
                    options.style.sketchLineWidthDp.dp.toPx(),
                )
            }

            // The frame, the drag bar, and the dashed line back to the station the section was
            // taken at - all three of which this port used to draw none of.
            //
            // The Java skips the frame entirely under `pref_legacy_cross_sections`, drawing the
            // connector straight to the centre instead; [DisplayOptions.legacyCrossSections]
            // carries that setting through.
            val stationOnScreen = stationPositions[shown.station.name]?.let(::project)
            if (options.legacyCrossSections) {
                if (stationOnScreen != null) {
                    drawDashes(stationOnScreen, centre, palette.crossSection)
                }
            } else {
                val bounds = boundsOf(shown)
                val border =
                    drawCrossSectionBorder(
                        project(
                            shown.position + (bounds.topLeft - shown.position).scale(sectionScale)
                        ),
                        project(
                            shown.position +
                                (bounds.bottomRight - shown.position).scale(sectionScale)
                        ),
                        palette,
                    )
                if (stationOnScreen != null) {
                    clipSegmentToRectBoundary(stationOnScreen, centre, border)?.let { end ->
                        drawDashes(stationOnScreen, end, palette.crossSection)
                    }
                }
                // Keyed on the detail in the sketch, not on `shown`: mid-drag `shown` is the
                // preview, a different object every frame, and the map would fill up with dead
                // sections.
                handleRects?.put(detail, drawCrossSectionHandle(border, palette))
            }
        }

        // While re-aiming, the line the section is being aimed along: station to finger.
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

    // `CrossSectionView.onDraw` is `drawGrid`, `drawSurvey`, `drawLegend` — and nothing else. No
    // hot corners, and no eraser ring: there is one station and its wall shots in here, and the
    // corners of a section drawn at four metres across are inside the passage.
    val isCrossSection = projection == Projection2D.CROSS_SECTION

    if (tool == SketchTool.ERASE && !isCrossSection) {
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
    if (options.hotCorners && !isCrossSection) {
        drawHotCorners(modalMoving, palette)
    }

    drawLegend(
        scene = scene,
        pixelsPerMetre = viewport.pixelsPerMetre,
        palette = palette,
        textMeasurer = textMeasurer,
        fontFamily = fontFamily,
        legendSizeSp = options.style.legendSizeSp,
    )
    // `drawCompass` is guarded on both the toggle and the projection, in that order. There is
    // no arrow on an elevation because there is no bearing to draw one for.
    if (options.showCompass && isPlan) {
        drawNorthArrow(
            palette,
            textMeasurer,
            fontFamily,
            options.style.legendSizeSp,
            headingDegrees,
        )
    }
}

/**
 * The north arrow, above the scale bar and to the left, as `GraphView.drawCompass` draws it.
 *
 * [headingDegrees] is which way the top of the screen is pointing, and the arrow turns back
 * against it: face north and it points up the screen, turn a quarter circle to your right and
 * north is now to your left, so the arrow swings the same quarter circle the other way. Zero is
 * both "facing north" and "this device has no compass", and they want the same picture — north
 * up, which is where `Projection2D.PLAN` puts it on the paper anyway.
 *
 * See [rememberDeviceHeading] for where the heading comes from on each platform.
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
    // `scaleBarY = getHeight() - textSize * 4f`, and the arrow's centre one text height above it.
    val scaleBarY = size.height - textSize * 4f
    val centreY = scaleBarY - arrowLength / 2f - textHeight
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

/**
 * `GraphView.drawLegend`: the survey's own name and size, then the scale bar under it.
 *
 * The label line is `<name> L<length> V<vertical range>`, in whole metres, which is the one thing
 * on the screen that says which cave this is and how big it has got. This port drew the bar and
 * left the line out — nothing looks wrong when a label is simply absent, which is why it survived
 * so long, and it is also the reason the exported SVG carried a legend the screen did not.
 */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawLegend(
    scene: SurveyScene,
    pixelsPerMetre: Float,
    palette: Palette,
    textMeasurer: TextMeasurer,
    fontFamily: FontFamily,
    legendSizeSp: Float = LEGEND_TEXT_SP,
) {
    val textSize = legendSizeSp.sp.toPx()
    val style =
        TextStyle(color = palette.scaleBar, fontSize = legendSizeSp.sp, fontFamily = fontFamily)

    // `offsetX`/`offsetY`, both `legendSize * 1.25`, so a bigger legend moves further in.
    val inset = textSize * 1.25f
    // `CrossSectionView.onDraw` calls `drawLegend` as the plan's does, so the section's editor
    // carries the same line — it is the only thing on that screen naming the cave. Guarded on the
    // name only so a scene built without one draws nothing rather than "  L0 V0".
    if (scene.surveyName.isNotEmpty()) {
        val label =
            scene.surveyName +
                " L" + formatFixed(scene.surveyLength, 0) +
                " V" + formatFixed(scene.surveyHeight, 0)
        val layout = textMeasurer.measure(label, style)
        // The Java positions text by its baseline and Compose by its top.
        drawText(layout, topLeft = Offset(inset, size.height - inset - layout.size.height))
    }

    drawScaleBar(pixelsPerMetre, palette, textMeasurer, fontFamily, legendSizeSp)
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

    val textSize = legendSizeSp.sp.toPx()
    val inset = textSize * 1.25f
    val left = inset
    // `scaleOffsetY = offsetY * 2`: the bar sits one legend-height above the label line.
    val bottom = size.height - inset * 2f
    val stroke = SCALE_BAR_STROKE_DP.dp.toPx()
    val tick = SCALE_BAR_TICK_DP.dp.toPx()

    // One path in the Java, and its ticks go *up* only — this port drew them both ways, which
    // reads as a dimension line rather than a scale.
    drawLine(palette.scaleBar, Offset(left, bottom), Offset(left + barPixels, bottom), stroke)
    drawLine(palette.scaleBar, Offset(left, bottom - tick), Offset(left, bottom), stroke)
    drawLine(
        palette.scaleBar,
        Offset(left + barPixels, bottom - tick),
        Offset(left + barPixels, bottom),
        stroke,
    )

    val label = if (metres >= 1f) "${metres.roundToInt()}m" else "${(metres * 100).roundToInt()}cm"
    val layout =
        textMeasurer.measure(
            label,
            TextStyle(color = palette.scaleBar, fontSize = legendSizeSp.sp, fontFamily = fontFamily),
        )
    // To the right of the bar, on its own line, as `drawLegend` places it — not above it.
    drawText(
        layout,
        topLeft =
            Offset(left + barPixels + 0.3f * textSize, bottom - layout.size.height * 0.8f),
    )
}

class Palette(
    val background: Color,
    val centreline: Color,
    val splay: Color,
    val station: Color,
    val stationLabel: Color,
    val symbol: Color,
    val crossSection: Color,
    /** `colorPrimary`: the frame and drag bar drawn round a cross-section on the plan. */
    val crossSectionFrame: Color,
    /** `colorOnPrimary`: the grip marks on the drag bar. */
    val onCrossSectionFrame: Color,
    val scaleBar: Color,
    val grid: Color,
    val activeStation: Color,
    /** `R.color.hotCorner`, drawn at a fifth alpha. The active tint is [activeStation]'s amber. */
    val hotCorner: Color,
    /** `R.color.legLatest`, which the app resolves to `md_magenta`. */
    val latestLeg: Color,
)

/** The Android app's own graph colours, not a reinterpretation of them — see [SexyTopoColours]. */
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
        crossSectionFrame = SexyTopoColours.crossSectionFrame,
        onCrossSectionFrame = SexyTopoColours.onCrossSectionFrame,
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
        crossSectionFrame = SexyTopoColours.crossSectionFrame,
        onCrossSectionFrame = SexyTopoColours.onCrossSectionFrame,
        scaleBar = SexyTopoColours.legendNight,
        grid = SexyTopoColours.gridNight,
        activeStation = SexyTopoColours.activeStationNight,
        hotCorner = SexyTopoColours.hotCornerNight,
        latestLeg = SexyTopoColours.latestLeg,
    )

/**
 * Stroke width for symbol artwork, in the symbol's own grid units: the drawables specify
 * `strokeWidth="1"` on a 40-unit viewport, and the transform scales it with everything else.
 */
private const val SYMBOL_STROKE_UNITS = 1f

/**
 * The compass bearing a drag points in, in degrees clockwise from up. Screen y grows downwards, so
 * a drag towards the top of the screen is north.
 */
internal fun bearingOf(delta: Offset): Float = bearingOf(delta.x, delta.y)

/** The same, from a raw vector — shared with the cross-section rotate gesture. */
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

            // A second finger takes over mid-stroke: `onStart` abandons the stroke in progress,
            // and the tool's own detector sees its changes consumed from here on and cancels
            // itself, so the half-drawn line disappears rather than being committed.
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
 * original draws three and tests four.
 */
private fun DrawScope.drawHotCorners(active: Boolean, palette: Palette) {
    val side = hotCornerSide(size.width, size.height)
    if (side <= 0f) return
    val colour = (if (active) palette.activeStation else palette.hotCorner).copy(alpha = FADED_ALPHA)
    for (corner in hotCornerTopLefts(size.width, size.height)) {
        drawRect(colour, topLeft = Offset(corner.x, corner.y), size = Size(side, side))
    }
}

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
