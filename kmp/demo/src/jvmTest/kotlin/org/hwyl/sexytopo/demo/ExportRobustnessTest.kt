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
 * throw *inside a composition*: on the web there is no error, no blank page, the last frame
 * simply stays up and the app looks frozen. So the one place an exporter can
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

    /**
     * The same file twice, for every format.
     *
     * Reproducibility is one of this port's headline findings about the original —
     * `PocketTopoTxtExporter` and `SvgExporter` iterate a `HashMap`, so two exports of an unchanged
     * survey differ — and the ported exporters fix it by iterating in survey order. But that was
     * asserted exporter by exporter, and only two of them were asked. It is a property of *every*
     * file this screen writes, and the cheapest guard against the next exporter that reaches for a
     * set: export twice, compare.
     *
     * A survey with everything in it, because the order that varies is the order of a collection
     * and an empty one cannot vary. Stations with comments, a splay, and a drawing with strokes,
     * a symbol and a label — the things exporters walk.
     *
     * And built **twice**, rather than exported twice. That distinction is the whole test: two
     * exports of the same objects share one hash order within a run and would agree even if an
     * exporter iterated a `HashSet`, which is exactly the bug being guarded against. Fresh objects
     * get fresh identity hashes, which is how the original's unreproducibility was found in the
     * first place — one JVM, two builds of the same survey, and the STATIONS lines came out in
     * different orders.
     */
    @Test
    fun everyExportGivesTheSameFileForTheSameSurvey() {
        for (format in ExportFormat.entries) {
            val projections =
                if (format.perProjection) {
                    Projection2D.entries.filter { it.isDrawable }
                } else {
                    listOf(Projection2D.PLAN)
                }
            for (projection in projections) {
                val once = exportText(everything(), format, projection, today = "2026-01-01")
                val twice = exportText(everything(), format, projection, today = "2026-01-01")
                assertTrue(
                    once == twice,
                    "$format on $projection is not reproducible: the same survey built twice " +
                        "exports differently, which is the bug this port reported in the original",
                )
            }
        }
    }

    /** A survey with one of everything an exporter walks. */
    private fun everything(): Survey {
        val survey = Survey("Swildons")
        val two = SurveyBuilder.updateWithNewStation(survey, Leg(5.4f, 12.5f, -3f))
        two.comment = "junction"
        SurveyBuilder.addSplay(survey, two, Leg(1.5f, 180f, -3f))
        SurveyBuilder.updateWithNewStation(survey, Leg(7f, 100f, 2f))
        survey.origin.comment = "entrance"
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(1f, 1f), Coord2D(2f, 0.5f)), Colour.BLACK),
        )
        survey.planSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(3f, 3f), Coord2D(4f, 4f)), Colour.RED),
        )
        survey.planSketch.addSymbolDetail(Coord2D(1f, 1f), "ENTRANCE", 1f, 0f, Colour.BLACK)
        survey.planSketch.addTextDetail(Coord2D(2f, 2f), "sump", 1f, Colour.BLUE)
        survey.elevationSketch.pathDetails.add(
            PathDetail(listOf(Coord2D(0f, 0f), Coord2D(2f, -1f)), Colour.BROWN),
        )
        return survey
    }

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
