package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.compose.resources.configureWebResources
import kotlinx.browser.document
import kotlinx.browser.window
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.sketch.SketchEditor

/**
 * Browser host for the shared UI — the same [App] the iOS and desktop hosts show.
 *
 * The URL fragment selects a reduced mode, which exists to bisect startup failures without
 * rebuilding: `#min` renders only text, `#canvas` only a raw draw call, `#scene` the survey canvas
 * without the app chrome. No fragment runs the real app.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val mode = window.location.hash
    println("sexytopo demo starting, mode='$mode'")
    keepPinchesInsideTheApp(1.0 / ZOOM_PER_SCROLLED_PIXEL)
    // Compose Resources needs to be told how to turn a resource path into a URL on the web,
    // otherwise the bundled font is never fetched.
    configureWebResources { resourcePathMapping { path -> "./$path" } }
    try {
        // The accessibility tree, which Compose builds as real DOM nodes laid over the canvas:
        // testTag becomes an element's id, contentDescription its aria-label, and a Compose Role
        // its aria role. It is what lets a screen reader read this app at all, and what lets the
        // browser tests ask for a menu row by name instead of working out which pixel it is drawn
        // at. On by default; said here because this app depends on it.
        ComposeViewport(document.body!!, configure = { isA11YEnabled = true }) {
            when {
                mode.contains("min") -> Text("compose wasm is alive")
                mode.contains("canvas") ->
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize()) { drawCircle(Color.Red, 80f) }
                    }
                mode.contains("scene") -> {
                    val survey = remember { ExampleSurvey.create() }
                    SurveyCanvas(
                        survey = survey,
                        projection = Projection2D.PLAN,
                        options = DisplayOptions(),
                        editor =
                            remember(survey) {
                                SketchEditor(survey.getSketch(Projection2D.PLAN))
                            },
                        canvas = remember(survey) { CanvasController() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> App()
            }
        }
    } catch (t: Throwable) {
        println("STARTUP THREW: ${t::class.simpleName}: ${t.message}")
        throw t
    }
}
