package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
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
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(2f), content = content)
        try {
            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia failed to encode $name")
            File(outputDir, name).writeBytes(data.bytes)
            println("wrote build/demo/$name (${data.bytes.size / 1024} KiB)")
        } finally {
            scene.close()
        }
    }

    render("plan.png", 1400, 1000) { App(survey = survey, initialProjection = Projection2D.PLAN) }
    render("extended-elevation.png", 1400, 1000) {
        App(survey = survey, initialProjection = Projection2D.EXTENDED_ELEVATION)
    }
    render("plan-dark.png", 1400, 1000) {
        App(survey = survey, initialProjection = Projection2D.PLAN, initialDarkMode = true)
    }
    render("phone-plan.png", 430, 932) { App(survey = survey) }
    render("drawing-tool.png", 1400, 1000) {
        App(survey = survey, initialTool = CanvasTool.DRAW)
    }

    // A survey built the way the app really builds one: readings decoded from DistoX wire-format
    // packets, three agreeing readings promoted to a station by the ported survey engine.
    render("table.png", 1400, 1000) {
        App(survey = survey, initialScreen = Screen.TABLE)
    }

    render("export.png", 1400, 1000) {
        App(survey = survey, initialScreen = Screen.EXPORT)
    }

    render("live-survey.png", 1400, 1000) {
        App(survey = buildSurveyFromInstrument(), initialProjection = Projection2D.PLAN)
    }

    println("Rendered ${outputDir.listFiles()?.count { it.extension == "png" } ?: 0} images")
}

/** Drives the real pipeline: simulated instrument → packet decode → survey engine. */
private fun buildSurveyFromInstrument(): Survey {
    val survey = Survey("Instrument Survey")
    val instrument = SimulatedInstrument(script = SurveySession.fieldScript())
    instrument.observe(
        object : InstrumentTransportListener {
            override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
                if (DistoXProtocol.isDataPacket(bytes)) {
                    SurveyUpdater.update(survey, DistoXProtocol.parseMeasurement(bytes))
                }
            }
        },
    )
    instrument.connect()
    while (instrument.hasMoreShots) instrument.emitNextShot()
    return survey
}
