package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.SketchDetail
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.sketch.colourForSymbol

/**
 * A symbol the surveyor stamped on the rock: which one, where the crosshair met the rock, in the
 * scan's frame, and for a directional symbol such as water flow, which way it points as a bearing
 * in the scan's frame.
 */
class SymbolStamp(val symbol: Symbol, val inScan: Coord3D, val bearingInScan: Float = 0f)

/** A pen sweep and the colour it is drawn in: water lines blue, walls in the surveyor's brush. */
class PenLine(val stroke: PenStroke, val colour: Colour)

/**
 * What a scan has put into a survey's sketches, so it can be taken out again.
 *
 * Generated content is not hand drawing. When the placement is corrected, or the smoothing
 * changed in review, everything the scan drew is rebuilt; a hand-drawn stroke must survive that.
 * The sketch model has no layers, so instead the scan remembers exactly which details it added,
 * by identity, and removes only those.
 */
class DerivedContent internal constructor(
    internal val plan: List<SketchDetail>,
    internal val elevation: List<SketchDetail>,
) {
    val size: Int get() = plan.size + elevation.size

    companion object {
        val NONE = DerivedContent(emptyList(), emptyList())
    }
}

/**
 * Drawing a placed scan into the survey: pen lines and generated walls as strokes, stamps as
 * symbols, on both the plan and the extended elevation.
 *
 * A symbol goes on the elevation too, where its point falls when unrolled, because that is where
 * a drawer would look for it; its direction there is meaningless and is dropped.
 */
object ScanSketchWriter {

    /** How big a stamped symbol is drawn, in metres: the demo survey's size. */
    const val SYMBOL_SIZE_METRES = 2.5f

    /**
     * Replaces whatever [previous] drew with what the scan draws now, and returns what that is.
     *
     * [walls] are generated wall lines already in survey coordinates; pen lines and stamps are in
     * the scan's frame and are placed here with [placement].
     */
    fun write(
        survey: Survey,
        frame: SurveyFrame,
        placement: ScanPlacement,
        penLines: List<PenLine>,
        stamps: List<SymbolStamp>,
        walls: List<List<Coord3D>> = emptyList(),
        wallColour: Colour = Colour.BLACK,
        previous: DerivedContent = DerivedContent.NONE,
    ): DerivedContent {
        remove(survey, previous)

        val plan = mutableListOf<SketchDetail>()
        val elevation = mutableListOf<SketchDetail>()

        fun drawLine(points: List<Coord3D>, colour: Colour) {
            for ((projection, added) in listOf(
                Projection2D.PLAN to plan,
                Projection2D.EXTENDED_ELEVATION to elevation,
            )) {
                val sketch = survey.getSketch(projection)
                for (stroke in frame.toStrokes(points, projection)) {
                    val detail = PathDetail(stroke, colour)
                    sketch.pathDetails.add(detail)
                    added.add(detail)
                }
            }
        }

        for (line in penLines) {
            drawLine(line.stroke.line().map { placement.place(it) }, line.colour)
        }
        for (wall in walls) drawLine(wall, wallColour)

        for (stamp in stamps) {
            val point = placement.place(stamp.inScan)
            val name = stamp.symbol.therionName
            val colour = colourForSymbol(name, Colour.BLACK, blueWater = true)
            val angle =
                if (stamp.symbol.isDirectional) {
                    (stamp.bearingInScan + placement.headingDegrees) % 360
                } else {
                    0f
                }
            plan.add(
                survey
                    .getSketch(Projection2D.PLAN)
                    .addSymbolDetail(frame.toPlan(point), name, SYMBOL_SIZE_METRES, angle, colour),
            )
            frame.toExtendedElevation(point)?.let { position ->
                elevation.add(
                    survey
                        .getSketch(Projection2D.EXTENDED_ELEVATION)
                        .addSymbolDetail(position, name, SYMBOL_SIZE_METRES, 0f, colour),
                )
            }
        }

        return DerivedContent(plan, elevation)
    }

    /** Takes out of the sketches exactly what [content] put in, and nothing a surveyor drew. */
    fun remove(survey: Survey, content: DerivedContent) {
        removeFrom(survey, Projection2D.PLAN, content.plan)
        removeFrom(survey, Projection2D.EXTENDED_ELEVATION, content.elevation)
    }

    private fun removeFrom(survey: Survey, projection: Projection2D, details: List<SketchDetail>) {
        if (details.isEmpty()) return
        // Sketch details do not override equals, so a set of them is a set of identities: a
        // hand-drawn stroke with the same points as a generated one is still kept.
        val generated = details.toHashSet()
        val sketch = survey.getSketch(projection)
        sketch.pathDetails.removeAll { it in generated }
        sketch.symbolDetails.removeAll { it in generated }
    }
}
