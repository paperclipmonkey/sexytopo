package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.compose.resources.configureWebResources
import kotlinx.browser.document
import kotlinx.browser.window
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Projection2D

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
    // Compose Resources needs to be told how to turn a resource path into a URL on the web,
    // otherwise the bundled font is never fetched.
    configureWebResources { resourcePathMapping { path -> "./$path" } }
    try {
        ComposeViewport(document.body!!) {
            when {
                mode.contains("min") -> Text("compose wasm is alive")
                mode.contains("canvas") ->
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize()) { drawCircle(Color.Red, 80f) }
                    }
                mode.contains("scene") ->
                    SurveyCanvas(
                        survey = ExampleSurvey.create(),
                        projection = Projection2D.PLAN,
                        options = DisplayOptions(),
                        modifier = Modifier.fillMaxSize(),
                    )
                else -> App()
            }
        }
    } catch (t: Throwable) {
        println("STARTUP THREW: ${t::class.simpleName}: ${t.message}")
        throw t
    }
}
