package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the shared Compose UI to PNG files with no display attached.
 *
 * This exists so the port can be demonstrated from a headless machine (and in CI): it draws the
 * exact composable the iOS app hosts, through the same Skia renderer iOS uses, and writes the
 * result to disk.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val outputDir = File("build/demo").apply { mkdirs() }
    val survey = ExampleSurvey.create()

    fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f), content = content)
        try {
            val image = scene.render()
            val data =
                image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Skia failed to encode $name")
            val file = File(outputDir, name)
            file.writeBytes(data.bytes)
            println("wrote ${file.path} (${data.bytes.size / 1024} KiB)")
        } finally {
            scene.close()
        }
    }

    render("plan.png", 1400, 1000) {
        App(survey = survey, initialProjection = Projection2D.PLAN)
    }
    render("extended-elevation.png", 1400, 1000) {
        App(survey = survey, initialProjection = Projection2D.EXTENDED_ELEVATION)
    }
    render("plan-dark.png", 1400, 1000) {
        App(survey = survey, initialProjection = Projection2D.PLAN, initialDarkMode = true)
    }
    render("phone-plan.png", 420, 900) {
        App(survey = survey, initialProjection = Projection2D.PLAN)
    }

    println("Rendered ${outputDir.listFiles()?.size ?: 0} images to ${outputDir.absolutePath}")
}
