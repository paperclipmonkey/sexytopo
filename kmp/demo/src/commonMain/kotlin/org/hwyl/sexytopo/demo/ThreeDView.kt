package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.math.Camera3D
import org.hwyl.sexytopo.shared.math.Space3DTransformer
import org.hwyl.sexytopo.shared.math.Wireframe
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.math.sqrt

/**
 * The cave, from the outside, turned with a finger.
 *
 * Ported from `ThreeDViewActivity`, `SurveyView3D` and the camera half of `SurveyRenderer`. The
 * other half of that renderer — GLSL shaders, vertex buffers, `glDrawArrays` — is deliberately not
 * ported. A centreline is a few hundred lines, which any 2D canvas draws without noticing, and
 * doing the projection in [Wireframe.projectSegment] rather than in a vertex shader is what lets
 * this run on iOS, Android, the desktop and the web from one file instead of only where GLES is.
 *
 * Two things the depth buffer used to do have to be done by hand, and both are here rather than in
 * the shared module because both are drawing decisions rather than maths:
 *
 * - **Near-plane clipping**, which is in [Wireframe.projectSegment] because getting it wrong is a
 *   maths bug — a passage that vanishes, or folds back onto the screen mirrored, as you move into
 *   the cave.
 * - **Draw order**, which is just painting: splays behind legs behind stations. GL sorted by depth
 *   per fragment; for unfilled lines the difference is invisible, and sorting hundreds of segments
 *   every frame would not be.
 *
 * ## Gestures
 *
 * One finger rotates; two pan, and pinch to zoom. This is the one deliberate divergence from
 * `SurveyView3D`, which pans with one finger and rotates with two — reachable only by moving both
 * fingers together without changing their spacing, which is also how you start a pinch. On a phone
 * the first thing anybody does with a 3D view is drag it to spin it, and the Android app answers
 * that by sliding the cave sideways.
 */
@Composable
fun ThreeDView(
    survey: Survey,
    revision: Int,
    darkMode: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rebuilt only when the survey changes, as the Java's `geometryDirty` flag arranges: moving the
    // camera changes the transform, not the geometry.
    val wireframe =
        remember(survey, revision) { Wireframe.of(Space3DTransformer().transformTo3D(survey)) }

    // Keyed on the wireframe rather than on the survey, so a leg recorded while you are looking
    // does not throw away the view you had set up - which is what the Java does, because it resets
    // the distance inside `buildGeometry`.
    //
    // The starting distance is a guess until the canvas has been measured: how far back a cave has
    // to be to fit depends on the shape of the screen, and nothing knows that during the first
    // composition. The guess is the Java's, and it is replaced below as soon as there is a size.
    var fitDistance by remember(wireframe) {
        mutableStateOf(Camera3D.fittingExtent(wireframe.extent))
    }
    var camera by remember(wireframe) { mutableStateOf(Camera3D(distance = fitDistance)) }
    var fittedToTheScreen by remember(wireframe) { mutableStateOf(false) }
    var showSplays by remember { mutableStateOf(true) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (darkMode) {
                        SexyTopoColours.panelBackgroundNight
                    } else {
                        SexyTopoColours.panelBackground
                    },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Close", color = SexyTopoColours.onPanel) }
            Spacer(Modifier.weight(1f))
            Text(
                survey.name,
                style = MaterialTheme.typography.titleSmall,
                color = SexyTopoColours.onPanel,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { camera = Camera3D(distance = fitDistance) },
            ) { Text("Reset", color = SexyTopoColours.onPanel) }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val aspect =
                if (constraints.maxHeight > 0) {
                    constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat()
                } else {
                    1f
                }

            // Refits the *distance* whenever the shape of the screen changes - a phone turned on
            // its side - but only moves the camera the first time, so measuring does not throw away
            // a view somebody has just set up. Reset uses the refreshed distance either way.
            LaunchedEffect(wireframe, aspect) {
                fitDistance = Camera3D.fittingRadius(wireframe.radius, aspect)
                if (!fittedToTheScreen) {
                    camera = Camera3D(distance = fitDistance)
                    fittedToTheScreen = true
                }
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (darkMode) {
                            SexyTopoColours.canvasBackgroundNight
                        } else {
                            SexyTopoColours.canvasBackground
                        },
                    )
                    .pointerInput(wireframe) {
                        // Written out rather than assembled from `detectDragGestures` and
                        // `detectTransformGestures`: those are two gesture loops competing for the
                        // same pointers, and which one wins depends on touch slop. One loop that
                        // counts fingers is the shape `SurveyView3D.onTouchEvent` has, and it can
                        // simply decide.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var previousCentroid: Offset? = null
                            var previousSpacing = 0f
                            var previousFingers = 0
                            while (true) {
                                val event = awaitPointerEvent()
                                val down = event.changes.filter { it.pressed }
                                if (down.isEmpty()) break

                                val centroid =
                                    down.fold(Offset.Zero) { total, change -> total + change.position } /
                                        down.size.toFloat()
                                val spacing =
                                    if (down.size >= 2) {
                                        (down[0].position - down[1].position).getDistance()
                                    } else {
                                        0f
                                    }

                                // A second finger arriving moves the centroid halfway across the
                                // screen without anything having moved, and a finger leaving does
                                // the same in reverse. Taking that as a drag throws the cave
                                // sideways at the start of every pinch. `SurveyView3D` has the same
                                // guard, spelled as resetting previousX/Y on ACTION_POINTER_DOWN.
                                if (down.size != previousFingers) previousCentroid = null

                                previousCentroid?.let { previous ->
                                    val moved = centroid - previous
                                    camera =
                                        if (down.size == 1) {
                                            camera.rotatedBy(moved.x, moved.y)
                                        } else {
                                            // The Java's guard: two fingers this close together are
                                            // more likely one finger being reported twice than a
                                            // pinch, and the ratio would be wild.
                                            val zoomed =
                                                if (previousSpacing > MINIMUM_PINCH &&
                                                    spacing > MINIMUM_PINCH
                                                ) {
                                                    camera.zoomedBy(previousSpacing / spacing)
                                                } else {
                                                    camera
                                                }
                                            zoomed.pannedBy(moved.x, moved.y)
                                        }
                                }

                                previousCentroid = centroid
                                previousSpacing = spacing
                                previousFingers = down.size
                                down.forEach { it.consume() }
                            }
                        }
                    },
            ) {
                drawWireframe(wireframe, camera, showSplays, darkMode)
            }

            if (wireframe.hasNothingToDraw) {
                Text(
                    "Nothing surveyed yet.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color =
                        if (darkMode) SexyTopoColours.bodyTextNight else SexyTopoColours.bodyText,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (darkMode) {
                        SexyTopoColours.panelBackgroundNight
                    } else {
                        SexyTopoColours.panelBackground
                    },
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showSplays = !showSplays }) {
                Text(
                    if (showSplays) "Hide splays" else "Show splays",
                    color = SexyTopoColours.onPanel,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "One finger turns, two pan and pinch",
                style = MaterialTheme.typography.labelSmall,
                color = SexyTopoColours.onPanel,
            )
        }
    }
}

/** Two fingers closer together than this are not a pinch. From `SurveyView3D.onTouchEvent`. */
private const val MINIMUM_PINCH = 10f

/**
 * The renderer's own colours, which are not the plan view's: a darker red leg, a translucent grey
 * splay and a blue station, straight out of `SurveyRenderer`.
 *
 * They are lightened for the dark theme, as the 2D palette's are, because the Java's are chosen
 * against the app's light background and its 3D view does not have a night mode.
 */
private object ThreeDColours {
    val leg = Color(0xFFCC3333)
    val legNight = Color(0xFFFF5555)
    val splay = Color(0x99999999)
    val splayNight = Color(0x99BBBBBB)
    val station = Color(0xFF3366CC)
    val stationNight = Color(0xFF6699FF)
}

private const val LEG_WIDTH = 2f
private const val SPLAY_WIDTH = 1f
private const val STATION_RADIUS = 3f

/**
 * Splays, then legs, then stations — a painter's algorithm standing in for the depth buffer the
 * Java's GL context had. Between the three kinds it is what you want anyway; within each kind, for
 * lines with no fill, there is nothing to hide.
 */
private fun DrawScope.drawWireframe(
    wireframe: Wireframe,
    camera: Camera3D,
    showSplays: Boolean,
    darkMode: Boolean,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val transform = camera.transformFor(wireframe.centre, size.width / size.height)

    fun line(ends: Pair<Coord3D, Coord3D>, colour: Color, width: Float) {
        val drawn =
            wireframe.projectSegment(transform, ends.first, ends.second, size.width, size.height)
                ?: return
        // A segment clipped at the near plane can land a very long way off screen; Skia will draw
        // it, but there is no point asking it to.
        if (!isPlausible(drawn.first) || !isPlausible(drawn.second)) return
        drawLine(colour, Offset(drawn.first.x, drawn.first.y), Offset(drawn.second.x, drawn.second.y), width)
    }

    if (showSplays) {
        val colour = if (darkMode) ThreeDColours.splayNight else ThreeDColours.splay
        for (splay in wireframe.splays) line(splay, colour, SPLAY_WIDTH)
    }

    val legColour = if (darkMode) ThreeDColours.legNight else ThreeDColours.leg
    for (leg in wireframe.legs) line(leg, legColour, LEG_WIDTH)

    val stationColour = if (darkMode) ThreeDColours.stationNight else ThreeDColours.station
    for (station in wireframe.stations) {
        val at = wireframe.project(transform, station, size.width, size.height) ?: continue
        if (!isPlausible(at)) continue
        drawCircle(stationColour, STATION_RADIUS, Offset(at.x, at.y))
    }
}

/** Far enough off screen that drawing it cannot matter, or not a number at all. */
private fun DrawScope.isPlausible(point: Coord2D): Boolean {
    if (!point.x.isFinite() || !point.y.isFinite()) return false
    val limit = OFF_SCREEN_LIMIT * sqrt(size.width * size.width + size.height * size.height)
    return point.x > -limit && point.x < size.width + limit &&
        point.y > -limit && point.y < size.height + limit
}

private const val OFF_SCREEN_LIMIT = 20f
