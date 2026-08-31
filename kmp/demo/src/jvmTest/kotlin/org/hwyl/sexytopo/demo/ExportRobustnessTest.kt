package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every export screen, on the shapes of survey that have no shape.
 *
 * The export screen computes its file inside a `remember` block, which means a throw there is a
 * throw *inside a composition* — and that is finding 11: on the web there is no error, no blank
 * page, the last frame simply stays up and the app looks frozen. So the one place an exporter can
 * fail is the one place nobody would see why.
 *
 * The interesting inputs are the empty ones, because they are what a surveyor has for the first
 * two minutes of every trip: a survey with nothing in it, one station and no legs, one splay that
 * never became a station. `BigSurveyTest` covers the other end. This is the end somebody actually
 * opens the export screen on by accident.
 *
 * All twelve pass today; this is a guard, not a fix. It is cheap only because `exportText` was
 * lifted out of the composable — while it was an expression in a `remember` block there was no way
 * to ask it anything at all.
 */
class ExportRobustnessTest {

    private fun shapes(): Map<String, Survey> = mapOf(
        "a survey with nothing in it" to Survey("Empty"),
        "one station and no legs" to Survey("Origin").also { it.origin.comment = "entrance" },
        "one splay that never became a station" to
            Survey("Splay").also { SurveyBuilder.addSplay(it, it.activeStation, Leg(2f, 90f, 0f)) },
        "a leg but nothing drawn" to
            Survey("Bare").also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 10f, 0f)) },
        "a drawing but only one station" to
            Survey("Drawn").also {
                it.planSketch.pathDetails.add(
                    PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f)), Colour.BLACK),
                )
            },
    )

    @Test
    fun noExportThrowsOnASurveyThatHasBarelyStarted() {
        for ((description, survey) in shapes()) {
            for (format in ExportFormat.entries) {
                val projections =
                    if (format.perProjection) {
                        Projection2D.entries.filter { it.isDrawable }
                    } else {
                        listOf(Projection2D.PLAN)
                    }
                for (projection in projections) {
                    val text =
                        runCatching { exportText(survey, format, projection, today = "2026-01-01") }
                            .getOrElse {
                                fail(
                                    "$format on $description threw ${it::class.simpleName}: " +
                                        "${it.message}. On the web that is a frozen screen with " +
                                        "nothing in the console.",
                                )
                            }
                    // Not merely "did not throw": an exporter that returns nothing has produced a
                    // file the surveyor will send to somebody, and an empty one fails on the
                    // laptop rather than on the phone.
                    assertTrue(
                        text.isNotBlank(),
                        "$format on $description produced an empty file",
                    )
                }
            }
        }
    }

    /** And the sketch files the native export writes alongside are written for those shapes too. */
    @Test
    fun theNativeExportsCompanionsSurviveThemToo() {
        for ((description, survey) in shapes()) {
            for ((name, body) in companionFiles(survey, ExportFormat.NATIVE)) {
                assertTrue(name.isNotBlank(), "a companion file with no name, on $description")
                assertTrue(body.isNotBlank(), "$name came out empty on $description")
            }
        }
    }
}
