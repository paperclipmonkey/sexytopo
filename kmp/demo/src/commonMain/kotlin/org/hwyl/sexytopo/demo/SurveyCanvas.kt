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

    /** Start a [mode] drag of [detail], the finger being at [at]. */
    fun hold(mode: SectionDragMode, detail: CrossSectionDetail, at: Coord2D): SectionDrag =
        SectionDrag(
            mode = mode,
            detail = detail,
            from = at,
            pivot = scene.positionOf(detail.station.name),
        )

    /** Grab whatever cross-section is under the finger, for a [mode] drag. */
    fun grab(mode: SectionDragMode, at: Coord2D): SectionDrag? {
        if (!options.crossSectionsAreTouchable) return null
        val detail = findCrossSectionBodyAt(scene.sketch, at) ?: return null
        return hold(mode, detail, at)
    }

    // Where each cross-section's drag bar was drawn last frame, in screen coordinates: written by
    // the draw pass, read by [sectionHandle] below. `GraphView.crossSectionHandleRects`.
    //
    // A plain map rather than snapshot state, and that is not laziness: it is written from inside
    // the draw, and a write that invalidated the composition would ask for another frame, which
    // would write it again.
    val handleRects = remember { mutableMapOf<CrossSectionDetail, Rect>() }

    // Keyed on `tool` as well as `scene`, and that is the whole reason the toolbar works.
    //
    // `Modifier.pointerInput` runs a suspending gesture loop that restarts only when one of its
    // keys changes. Every branch below sits at the same position in the modifier chain, so Compose
    // sees one node: keyed on `scene` alone, picking a new tool swapped the *lambda* and left the
    // previously started loop running, and the canvas went on panning. It looked as though tool
    // selection worked, because switching between the table and a sketch rebuilds `scene` and the
    // loop restarted with whatever tool was current by then - so the fix was always one view
    // switch away, which is exactly how the bug was reported.
    //
    // And on `options`, for the same reason and with the same symptom, which is a bug this file
    // carried until a check that had been passing for the wrong reason was pointed at a tool that
    // could actually fail it. A running loop holds the `options` it captured when it started, so
    // turning a setting off from a menu changed nothing a finger could feel: with cross-sections
    // hidden, a tap still opened a section's editor from what looks like blank paper - the very
    // thing `handleCrossSectionBodyTap`'s "special case: can't tap on invisible X-sections" exists
    // to prevent - and it stayed wrong until the tool was switched or the view left and re-entered.
    //
    // The whole object rather than the settings each detector happens to read. Two of the loops
    // below already listed theirs (`snapToLines`, `hotCorners`, `twoFingerMove`) and were right
    // about those and silently wrong about the rest; a list of what a lambda reads is exactly the
    // sort of thing that goes stale the next time somebody adds a line to it. `DisplayOptions` is
    // a data class, so this key changes when a setting changes and not when a frame is drawn.
    val gestures =
        when (tool) {
            SketchTool.MOVE ->
                Modifier.pointerInput(scene, tool, options) {
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
                    .pointerInput(scene, tool, options, symbol) {
                        detectTapGestures { offset -> stamp(offset, 0f) }
                    }
                    .pointerInput(scene, tool, options, symbol) {
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

                Modifier.pointerInput(scene, tool, options) {
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
                Modifier.pointerInput(scene, tool, options) {
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
                Modifier.pointerInput(scene, tool, options) {
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
                Modifier.pointerInput(scene, tool, options) {
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
                Modifier.pointerInput(scene, tool, options) {
                    // A rubber that rubs, which is a deliberate departure from the Android app.
                    //
                    // `GraphView.handleErase` does its work under `case ACTION_DOWN` and its
                    // `ACTION_MOVE` case is a bare `break` — so over there the eraser only ever
                    // takes out what is under the *first* touch, and dragging across a wall does
                    // nothing at all. That is not a porting gap, it is upstream's behaviour, and
                    // this port copied it faithfully and even wrote it into `eraseAt`'s
                    // documentation. It is still wrong: a tool drawn as an eraser, held like an
                    // eraser and named *Erase* is one every surveyor will try to rub with, and a
                    // stroke it silently declines to remove is one they will assume it could not
                    // reach.
                    //
                    // So: erase under the finger when it lands, and again everywhere it goes.
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
                        val down = awaitFirstDown()
                        var last = viewport.toSurvey(down.position)
                        var erased = rubAt(last)

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) break
                            val here = viewport.toSurvey(pointer.position)
                            // Along the segment, not merely at its far end. A finger moving
                            // quickly is sampled every few frames, so at speed the gaps between
                            // samples are far wider than the eraser and a rub would come out
                            // dotted — taking out one stroke in three and leaving a wall that
                            // looks deliberately dashed.
                            erased = rubAlong(last, here, toleranceInMetres, rub = ::rubAt) || erased
                            last = here
                            // Consumed so the sketch does not also scroll under the rub. The
                            // hot-corner pan detector sits outside this one and would otherwise
                            // take the same drag.
                            pointer.consume()
                        }

                        if (erased) onSketchEdit()
                    }
                }

            else ->
                Modifier
                    // What a *tap* with the pencil means. Two things, in the Android app's own
                    // order: a tap on a cross-section opens it — `handleCrossSectionBodyTap` runs
                    // ahead of every tool but pan and erase — and a tap anywhere else leaves a
                    // dot, which is `handleDraw`'s ACTION_UP branch, opening `if
                    // (touchPointOnView.equals(actionDownPointOnView)) { // handle dots`.
                    //
                    // The dot was missing here, and the comment that used to sit on this detector
                    // said why without noticing: "the draw tool is a drag detector, which never
                    // fires for a tap, so the two do not compete". They did not compete because
                    // one of them was not there. `detectDragGestures` waits for the touch slop
                    // before firing anything at all, so a tap produced no stroke — while
                    // `finishPath`'s own comment went on claiming that a stroke of fewer than two
                    // points is committed "because a tap is how you draw a dot".
                    //
                    // Both jobs go in *one* detector rather than two. A third `pointerInput` in
                    // this chain took the touch-down away from the drag detector below and stopped
                    // drawing working at all — including inside the cross-section editor, three
                    // checks earlier in the browser suite. Tap-then-drag is the arrangement the
                    // SYMBOL branch above already proves.
                    .pointerInput(scene, tool, options) {
                        detectTapGestures { offset ->
                            val at = viewport.toSurvey(offset)
                            val section =
                                if (options.crossSectionsAreTouchable) {
                                    findCrossSectionBodyAt(scene.sketch, at)
                                } else {
                                    null
                                }
                            if (section != null) {
                                onOpenCrossSection(section)
                            } else {
                                // A path of one point. `finishPath` commits it as the original
                                // does, and the renderer draws a stroke with no length as a round
                                // cap — which is what a dot is.
                                editor.startPath(at)
                                editor.finishPath()
                                strokeTick++
                                onSketchEdit()
                            }
                        }
                    }
                    .pointerInput(scene, tool, options) {
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
        Modifier.pointerInput(scene, tool, options) {
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

    // Pick a cross-section up by its drag bar, whatever tool is in hand.
    //
    // `GraphView` does this in `onTouchEvent` before it dispatches to the current tool at all:
    // `isCrossSectionMoveSelection` hit-tests the bar on every ACTION_DOWN and, if it hits,
    // switches to SketchTool.MOVE_CROSS_SECTION for the rest of the touch, putting the previous
    // tool back on the way up. That is what the bar is *for*. This port drew the bar - grip marks
    // and all, three ticks saying "drag me" - and never hit-tested it, so the only affordance a
    // section has did nothing at all, and moving one meant knowing to open the drawing menu and
    // pick "Move a cross-section" first. An affordance that lies is worse than none.
    //
    // Switching `tool` mid-gesture is not open to us the way it is to the Java: every
    // `pointerInput` in this file is keyed on `tool`, so the switch would tear down and restart
    // the very gesture it was meant to begin. A detector of its own instead, which comes to the
    // same behaviour by another route - it takes the touch only when the press lands on a bar,
    // and otherwise consumes nothing, so no other tool notices it is there.
    //
    // Placed after `longPress` and before `modalMove`, which is the Java's order of tests: the
    // Main pass runs innermost-first, so the hot corners get first refusal (as
    // `isModalMoveSelection` does, being asked first), the bars next, and the tool's own
    // detectors last.
    val sectionHandle =
        if (options.legacyCrossSections) {
            // No bar is drawn in legacy mode, so there is nothing to grab; `handleRects` would be
            // empty anyway, and `isCrossSectionMoveSelection` bails on the same condition in the
            // same place.
            Modifier
        } else {
            Modifier.pointerInput(scene, tool, options) {
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

                    if (sectionDrag?.commit(editor) == true) onSketchEdit()
                    sectionDrag = null
                }
            }
        }

    // A wheel, or a laptop trackpad, which is the whole input story on the browser build at a
    // desk. Reported from there: *"on web desktop click and drag to pan works great but macbook
    // pinch to zoom zooms the whole page rather than the survey"* - a pinch on a Mac trackpad
    // arrives as a wheel event with ctrl held, so the browser takes it as page zoom and the cave
    // never hears about it.
    //
    // Nothing in the Android app to port here; a phone has no wheel. The convention taken is the
    // one every desktop drawing tool uses, which is also what a caver reaching for this at home
    // after a trip will have in their fingers from Figma or Inkscape: plain scroll pans, ctrl (or
    // cmd) and scroll zooms about the pointer. Two-finger scrolling therefore slides the paper
    // around, which is what a trackpad's own gesture means everywhere else.
    //
    // The pinch honours `pinchToZoom`, because it is the same gesture the preference is about:
    // somebody who turned the pinch off did so to stop the drawing jumping while they work, and a
    // trackpad is no different.
    val wheel =
        Modifier.pointerInput(scene, tool, options) {
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
            Modifier.pointerInput(scene, tool, options) {
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

    // Ctrl+Z and Ctrl+Shift+Z, which is the other half of what a browser build gets asked for at a
    // desk: *"add support for ctrl+z to undo and redo? That'd be cool."* Cmd on a Mac, and Ctrl+Y
    // as well because half of Windows reaches for that one.
    //
    // The keys go to the same [SketchEditor.undo] the toolbar's own arrows use, so the plan, the
    // extended elevation and a cross-section each undo their own drawing - the three separate
    // stacks the Android app keeps, and the reason this sits in the canvas rather than at the top
    // of the app, where it would have to work out which of them is showing.
    val keyboardFocus = remember { FocusRequester() }
    // The canvas takes the keyboard when it appears, and takes it back whenever the tool changes.
    //
    // Both halves are needed and the second was found the hard way. Each of the sketch, the table,
    // the manual and the cross-section editor replaces the others in the tree rather than covering
    // them, so leaving a section brings the plan's canvas back into composition and this runs
    // again - that is the first half. The second is that a Compose button takes the focus when it
    // is pressed, so picking the pencil off the toolbar moved the keyboard to the pencil button
    // and Ctrl+Z did nothing from then on. Which is not a corner case: choosing a tool and then
    // drawing with it is the only way anybody uses this. Keying on `tool` brings the keyboard back
    // to the paper the moment a tool is chosen, and [keyboardFocusOnTouch] covers the buttons that
    // do not change it.
    LaunchedEffect(tool) {
        // requestFocus throws if the node is not attached yet, which is a race worth losing
        // quietly: the alternative is the whole drawing failing to appear because a key handler
        // could not be set up.
        runCatching { keyboardFocus.requestFocus() }
    }

    // Touching the drawing gives it the keyboard back, whatever took it - a colour swatch, a menu
    // that has closed, the browser's own address bar. Consumes nothing and is dispatched after
    // every other detector, so no tool can tell it is there.
    val keyboardFocusOnTouch =
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                runCatching { keyboardFocus.requestFocus() }
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
        if (if (undo) editor.undo() else editor.redo()) onSketchEdit()
        strokeTick++
        return true
    }

    // Clipping is stated rather than inherited. `drawGrid` starts its first line at
    // `floor(topLeft.y / spacing) * spacing`, which is by definition at or above the top of the
    // view - the same arithmetic GraphView uses, where an Android View's own clip makes it free. It
    // is free here too today: render the demo with this modifier removed and that line still does
    // not appear, so something up the tree is already clipping. But "something up the tree" is a
    // layout change away from not being true, and the failure it would produce is the cave painting
    // over the app bar. One modifier is a cheap way not to depend on an ancestor for that.
    Box(
        modifier =
            modifier
                .clipToBounds()
                .then(keyboardFocusOnTouch)
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
                projection = projection,
                handleRects = handleRects,
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
    /**
     * The stations carrying a comment, and the origin's name with the survey's.
     *
     * `GraphView.drawStations` marks both on the plan - an icon beside the name for a comment, and
     * `name (surveyName)` for the origin - and this port marked neither, so a note written at a
     * station could only be found by opening the table.
     */
    val commentedStations: Set<String>,
    val originName: String,
    val surveyName: String,
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
                space.stationMap.keys.filter { it.hasComment() }.map { it.name }.toSet(),
                survey.origin.name,
                survey.name,
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
                // The section editor draws one station and no chrome round it: no comment icon
                // (the note belongs to the station on the plan, not to the profile of the passage
                // at it) and no survey name in brackets, because there is no origin in here.
                commentedStations = emptySet(),
                originName = "",
                surveyName = "",
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

/*
 * A data class, and that is load-bearing rather than tidiness: [DemoState.displayOptions] is a
 * `get()` that builds a fresh one every read, so identity changes on every recomposition, while
 * the settings inside it change only when somebody opens a menu. Value equality is what lets the
 * gesture loops below key on the whole object - see the note on `gestures` - instead of restarting
 * every detector on the canvas sixty times a second.
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
    /**
     * Whether a cross-section is drawn the old way: the splay star and a dashed line to it, with
     * no frame, no drag bar and - as the Android app's own summary for the setting says - no
     * tap-to-edit. `pref_legacy_cross_sections`, default off.
     */
    val legacyCrossSections: Boolean = AppPreferences.DEFAULT_LEGACY_CROSS_SECTIONS,
) {
    /**
     * Whether a cross-section on the plan is there to be found by a finger.
     *
     * One property rather than the same pair of conditions at four hit-test sites, because the
     * Java's rule is a single sentence — an invisible cross-section cannot be tapped — and it is
     * invisible either because the whole sketch is hidden or because sections are. Legacy sections
     * are the third way of being untouchable: `settings_legacy_cross_sections_summary` says the
     * mode "disables tap-to-edit", and it has to - the frame and its handle are the only things
     * that mark a section as an object, so without them a tap near one is a tap on the drawing.
     */
    val crossSectionsAreTouchable: Boolean
        get() = showSketch && showCrossSections && !legacyCrossSections
}

/** `GraphView.FADED_ALPHA`, which is `0xff / 5` of full. */
const val FADED_ALPHA = 0.2f

/**
 * How much a pixel of ctrl-scroll zooms the drawing, as the exponent of e.
 *
 * Reported from a MacBook: "zooming in browser isn't very fast... a lot of scrolling required".
 * A wheel notch in a browser is 100 pixels, and at the original 0.0015 that was a factor of only
 * about 1.16 - close to the 1.1 the toolbar's own buttons use, which reads as generous for a
 * single tap and as nothing at all for a gesture somebody expects to cover a whole survey in a
 * few strokes. At 0.006 the same notch is a factor of about 1.8, and a firm two-finger pinch or a
 * few clicks of a mouse wheel now gets from one end of a cave to the other. Exponential rather
 * than multiplied, so that zooming out and back in returns to the scale you started at instead of
 * drifting a little each time.
 *
 * This only speeds up Chrome and Firefox, which report a trackpad pinch as a real `wheel` event
 * with a physical `deltaY` this multiplies. Safari reports a pinch as its own `gesturechange`
 * event with an exact scale ratio instead, and [keepPinchesInsideTheApp] deliberately *divides*
 * by this same constant to turn that ratio into a wheel delta before this multiplies it back out
 * - the two cancel, on purpose, so a Safari pinch always reproduces the ratio the fingers made
 * exactly, whatever this number is. Changing it changes how fast a *wheel* feels and nothing about
 * how fast a *pinch* on Safari feels.
 *
 * Not inside `CanvasSizes` with the rest, because it is not only this file's: the browser host has
 * to turn Safari's own pinch events into the wheel events this reads, and it needs this number to
 * do it. One constant, passed across, rather than the same figure written down in Kotlin and again
 * in a string of JavaScript where nothing would ever notice the two drifting apart.
 */
internal const val ZOOM_PER_SCROLLED_PIXEL = 0.006f

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
    const val AIMING_LINE_DP = 1f
    /** Where a station's name sits relative to its dot. */
    const val LABEL_RIGHT_DP = 5f
    const val LABEL_UP_DP = 14f

    // The frame round a cross-section on the plan. Every one of these is a `GraphView` constant of
    // the same name, so the frame is the size and shape the Android app draws.
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
     * The bar is drawn 8dp tall, which the Android app also hit-tests at 8dp - about 1.3mm, well
     * under a third of the 48dp Android's own guidance asks of a touch target, and a target you
     * would not reliably hit indoors with a dry hand, never mind in a wet cave in gloves. This
     * port grows the *hit* rectangle to 24dp before testing it, and only upwards: everything
     * below the bar is the section's own frame, where a press means "open this section for
     * drawing", so growing downwards would buy one gesture by breaking another.
     *
     * A deliberate departure, not a port of anything.
     */
    const val CROSS_SECTION_HANDLE_TOUCH_HEIGHT_DP = 24f

    const val CROSS_SECTION_HANDLE_GRIP_WIDTH_DP = 2f
    const val CROSS_SECTION_HANDLE_GRIP_SPACING_DP = 5f
    const val CROSS_SECTION_HANDLE_GRIP_LENGTH_FRACTION = 0.45f
}

/**
 * How far off the screen a station can be and still put something on it.
 *
 * Its name goes up and to the right of the dot, so this has to cover the longest name a surveyor
 * is likely to type rather than the dot's own radius - and, since the origin's label carries the
 * survey's name in brackets as well, a fair bit of that too. The Android app culls nothing at all,
 * so the worst this can do is drop the tail of a very long label that was already mostly off the
 * side of the screen.
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
 *
 * The diameter is the *setting*, `pref_station_diameter`, as the Java's `stationCrossDiameterPx`
 * is. This used to read the 10dp default constant instead, so a surveyor who enlarged their
 * stations for cold hands got brackets that stayed where they were and ended up drawn inside the
 * cross they are meant to frame.
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
 * `GraphView.drawStations` draws a bitmap; this draws the same idea with three strokes, because
 * the port has no icon assets and a note is a shape everybody already knows: a page with writing
 * on it. Sized off the station diameter, as the Java sizes its icon.
 *
 * Not decoration. A comment is the only place a surveyor can write "sump, not passed" or "loose,
 * do not climb", and until now this port put it in the table and nowhere else - so on the drawing,
 * which is what you look at underground, it did not exist.
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
 * `GraphView.drawCrossSectionBorder` and `drawCrossSectionHandle`, which this port did not have at
 * all: a section was a star of splay lines and a dot, floating on the drawing with nothing to say
 * where it ended or that it could be moved. The frame is what makes it an object. The rectangle is
 * the section's own bounding box - splays, sub-sketch and a forced minimum, all from
 * [boundsOf] - scaled by the sketch's cross-section scale about the section's centre, then padded
 * by a twentieth of its shorter side clamped into 4..16dp, with room above for the handle.
 *
 * Returns the border rectangle so the connector can be clipped to it.
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
 * The grip marks are the whole point. A plain green strip is decoration; three ticks is the
 * universal "this is a thing you drag", and it is the only affordance the section has - moving one
 * by grabbing its middle would fight the sketching tool for the same touch.
 *
 * Returns the bar's rectangle, in screen coordinates, so the caller can record where it ended up.
 * That is how `GraphView` does it too - `crossSectionHandleRects.put(originalDetail, handleRect)`
 * on the line after the draw - and the reason is worth stating: a hit test that recomputed this
 * rectangle from the sketch would be a second copy of the padding, the scale and the projection,
 * free to drift from the one that drew the bar. Then the grip marks would be in one place and the
 * thing you can actually grab in another, which is the worst kind of bug to have underground
 * because it looks like the app ignoring you.
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
 * A pure function beside the gesture rather than inside it, for the reason the rest of this file
 * gives: a rule that lives in a composable is a rule nothing can test.
 *
 * [from] is *not* rubbed — the caller has already done that, either as the touch-down or as the
 * previous move's endpoint — so a stationary finger costs one call and no repeats. [to] always is,
 * so the rub reaches exactly as far as the finger did.
 *
 * The step is the eraser's own radius, which is what makes the rub continuous rather than dotted: a
 * finger crossing the screen in a fifth of a second is sampled perhaps a dozen times, so at speed
 * the gaps between samples are many times wider than the eraser, and rubbing along a wall would
 * take out one stroke in three and leave something that looks deliberately dashed.
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
 * The cross-section whose drag bar is under [pointOnScreen], or null. `GraphView`'s
 * `findCrossSectionHandleAt`.
 *
 * [handleRects] is filled by the draw pass, so this asks about the bars actually on the screen
 * rather than recomputing where they ought to be. Everything that stops a bar being drawn - the
 * "show cross-sections" toggle, legacy mode, a section outside the sketch being shown - therefore
 * stops it being grabbable, for free and without a second set of conditions to keep in step.
 *
 * [minimumHeightPx] is the departure described on
 * [CanvasSizes.CROSS_SECTION_HANDLE_TOUCH_HEIGHT_DP]: a rectangle shorter than this is grown
 * upwards, away from the section's own body, until it is that tall. First match wins, as in the
 * original, which iterates a LinkedHashMap and returns on the first rectangle that contains the
 * point.
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
     * Where each cross-section's drag bar was drawn, in screen coordinates, filled in as they are
     * drawn and read by the gesture that lets one be picked up. `GraphView.crossSectionHandleRects`
     * exactly: the draw pass is the only thing that knows where the bar ended up, so it is the
     * thing that says. Cleared here, on every frame, whatever the display options say - a section
     * hidden by "show cross-sections", drawn in legacy mode, or scrolled out of the survey
     * entirely must not leave a live handle behind at last frame's coordinates.
     */
    handleRects: MutableMap<CrossSectionDetail, Rect>? = null,
) {
    val palette = if (options.darkMode) DarkPalette else LightPalette

    handleRects?.clear()

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

    // `GraphView.drawDashedLine`, used for a foreshortened leg and for the line joining a
    // cross-section to its station.
    fun drawDashes(from: Offset, to: Offset, colour: Color) {
        val dashLength = DASH_INTERVAL_DP.dp.toPx()
        val width = CanvasSizes.CROSS_SECTION_CONNECTOR_WIDTH_DP.dp.toPx()
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
        // The Java sets the paint's alpha to solid when it reaches the active station and never
        // sets it back, so which stations come out faded depends on where the active one falls in
        // a HashMap's iteration order — see the README. Here the question is asked per station.
        val stationColour =
            if (options.fadeNonActive && !isActive) {
                palette.station.copy(alpha = FADED_ALPHA)
            } else {
                palette.station
            }
        // A cross, as `GraphView.drawStationCross` draws it: two lines through the point, each
        // the full station diameter long, at `STATION_STROKE_WIDTH_DP`.
        //
        // This port drew a filled dot instead, which was written down as a divergence and never
        // given a reason. There is a good one for the cross and none for the dot: a station is a
        // *position*, and a cross says where it is while a blob covers it — at the default ten dp
        // a filled dot hides the ends of every leg meeting there, which on a plan is exactly the
        // junction a surveyor is trying to read. It is also what every cave survey ever published
        // uses, and what the app this copies looks like.
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
        // in that order and spaced off the station's own diameter - so a bigger station setting
        // spaces the annotations out with it, as `GraphView.drawStations` does.
        //
        // The one difference is vertical. The Java puts the name and its icons on one row through
        // the station; this port has always drawn the name a little above and to the right, which
        // keeps it off the centreline at a junction, so the mark sits below its label rather than
        // beside it. Left as it is: moving the label back onto the station's row to gain the
        // alignment would cost the thing the offset was for.
        val markSize = options.style.stationDiameterDp.dp.toPx()
        var nextX = centre.x + CanvasSizes.LABEL_RIGHT_DP.dp.toPx()
        val labelTop = centre.y - CanvasSizes.LABEL_UP_DP.dp.toPx()
        val labelsVisible =
            options.showStationLabels && viewport.pixelsPerMetre > LABEL_VISIBILITY_PIXELS_PER_METRE

        if (labelsVisible) {
            // The origin says which survey it is the origin of, as the Java does. On one survey
            // that is a curiosity; the moment two are open, or one is drawn under another, it is
            // the only thing on the page that says which cave you are looking at.
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

            // The splay star, drawn as `GraphView.drawCrossSection` draws it: through `drawLegs`,
            // which means in the ordinary splay colour, at the ordinary splay width, hidden with
            // the rest of the splays, and dashed when the shot does not lie in the plane being
            // drawn. Every one of those was different here - silver, 1.2dp, always shown, never
            // dashed - and the reason was that a section had nothing else to mark it out. It has a
            // frame now, which is what the Android app relies on, so the star can go back to
            // saying what it is: splays, the same as every other splay on the page.
            //
            // A section being dragged keeps the indicator colour, which is this port's own and not
            // in the Java. Alpha is what the original changes while a section moves, and alpha
            // reads as nothing at all on a phone in a cave.
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

            // And the centre, which the Java marks with the same station cross it marks a station
            // with - because that is what it is, the station the section was taken at, drawn where
            // the section was put rather than where the station is.
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
            //
            // `GraphView.drawCrossSectionSubSketch`, which is one line of Java —
            // `getSketch().scale(xsScale).translate(centreOnSurvey)` — and was missing here
            // entirely: this port drew the splay star and the marker dot and never read
            // `CrossSectionDetail.sketch` at all. That makes the feature's whole point invisible.
            // A surveyor drops a section, taps it, draws the shape of the passage, comes back to
            // the plan and sees the same star of splays as before. It saved, it exports, it
            // reopens in the editor — but the only reasonable conclusion from the plan is that it
            // did not, and the second attempt is to draw it again.
            //
            // Only the paths: this port's section editor offers move, draw and erase and no way
            // to place a symbol or a label, so `symbolDetails` and `textDetails` are always empty
            // in a section's sketch. Worth knowing if that ever changes, because `scale` grows a
            // symbol *in place* rather than moving it — deliberate for the plan's own sizing, and
            // wrong for this transform, in the Java as much as here.
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
            // taken at. `GraphView` draws all three and this port drew none of them, which left a
            // section as a star of lines floating on the plan: nothing said how big it was,
            // nothing said it could be moved, and - the one that actually costs you underground -
            // nothing said which station it belonged to. Drop two sections in the same chamber and
            // the drawing stops being readable.
            //
            // The Java skips the frame entirely under `pref_legacy_cross_sections`, drawing the
            // connector straight to the centre instead; [DisplayOptions.legacyCrossSections]
            // carries that setting through, so the old look is still available to anyone who
            // prefers it.
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
                // sections. The Java is careful about the same thing - `originalDetail`, not the
                // one it just drew.
                handleRects?.put(detail, drawCrossSectionHandle(border, palette))
            }
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
