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
import org.hwyl.sexytopo.shared.sketch.SketchTool
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Renders the shared Compose UI to PNG files with no display attached.
 *
 * This exists so the port can be demonstrated from a headless machine (and in CI): it draws the
 * exact composable the iOS app hosts, through the same Skia renderer iOS uses, and writes the
 * result to disk.
 */
/**
 * Everything on one thread, and the one Compose already uses.
 *
 * `ImageComposeScene.render` measures, lays out and draws on whichever thread calls it, while
 * Compose Desktop's main dispatcher is the AWT event thread — so a `LaunchedEffect` body and the
 * snapshot observer's flush run over there while this one is inside a draw. Compose checks for
 * exactly that and throws *Detected multithreaded access to SnapshotStateObserver*, which it did
 * about one run in six, and more often the more semantics the app carries: the stack goes through
 * `calculateSemanticsConfiguration`, and every `testTag` is one more thing to invalidate.
 */
fun main() {
    SwingUtilities.invokeAndWait { renderEverything() }
    // The event thread is running now, and nothing has a window to close, so say so.
    exitProcess(0)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderEverything() {
    val outputDir = File("build/demo").apply { mkdirs() }
    val survey = ExampleSurvey.create()

    /**
     * @param width and [height] in **density-independent pixels**, not device pixels. Compose
     *   lays out in dp, so a render sized in raw pixels quietly gets the layout of a screen half
     *   the size — which is how an earlier phone render came out with a toolbar and no room for
     *   the cave. The images come out at [scale] times these numbers.
     */
    fun render(
        name: String,
        width: Int,
        height: Int,
        scale: Float = 2f,
        /**
         * How many frames to throw away before keeping one.
         *
         * A view whose layout depends on a `LaunchedEffect` — the 3D camera, which cannot fit the
         * cave to the screen until the canvas has been measured — is still at its opening guess on
         * the first frame. One discarded frame is enough for the effect to have run.
         */
        warmUpFrames: Int = 0,
        content: @Composable () -> Unit,
    ) {
        val scene =
            ImageComposeScene(
                width = (width * scale).toInt(),
                height = (height * scale).toInt(),
                density = Density(scale),
                content = content,
            )
        try {
            repeat(warmUpFrames) { scene.render() }
            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia failed to encode $name")
            File(outputDir, name).writeBytes(data.bytes)
            println("wrote build/demo/$name (${data.bytes.size / 1024} KiB)")
        } finally {
            scene.close()
        }
    }

    render("plan.png", 1200, 820) { App(survey = survey, initialProjection = Projection2D.PLAN) }
    render("extended-elevation.png", 1200, 820) {
        App(survey = survey, initialProjection = Projection2D.EXTENDED_ELEVATION)
    }
    render("plan-dark.png", 1200, 820) {
        App(survey = survey, initialProjection = Projection2D.PLAN, systemDark = true)
    }
    render("drawing-tool.png", 1200, 820) {
        App(survey = survey, initialTool = SketchTool.DRAW)
    }
    render("table.png", 1200, 820) { App(survey = survey, initialScreen = Screen.TABLE) }
    render("three-d.png", 1200, 820, warmUpFrames = 2) {
        App(survey = survey, initialView3D = true)
    }
    render("export.png", 1200, 820) { App(survey = survey, initialScreen = Screen.EXPORT) }

    render("live-survey.png", 1200, 820) {
        App(survey = buildSurveyFromInstrument(), initialProjection = Projection2D.PLAN)
    }

    for ((name, size) in PHONES) {
        val (width, height) = size
        render("$name-plan.png", width, height) { App(survey = survey) }
        render("$name-draw.png", width, height) {
            App(survey = survey, initialTool = SketchTool.DRAW)
        }
        render("$name-live.png", width, height) {
            App(survey = survey, initialMode = SurveyMode.LIVE)
        }
        render("$name-table.png", width, height) {
            App(survey = survey, initialScreen = Screen.TABLE)
        }
        render("$name-dark.png", width, height) {
            App(survey = survey, systemDark = true, initialTool = SketchTool.DRAW)
        }
        render("$name-3d.png", width, height, warmUpFrames = 2) {
            App(survey = survey, initialView3D = true)
        }
    }

    println("Rendered ${outputDir.listFiles()?.count { it.extension == "png" } ?: 0} images")
}

/**
 * The two phones this will be shown on, in density-independent pixels.
 *
 * Both are comfortably under the 600dp breakpoint, so both get the phone layout — which is the
 * point of rendering them: the argument is that one `App()` suits an iPhone and a Pixel equally,
 * and that is only worth making if somebody has looked at both.
 */
private val PHONES =
    mapOf(
        // iPhone 15 Pro.
        "iphone" to (393 to 852),
        // Pixel 8.
        "android" to (412 to 915),
    )

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
