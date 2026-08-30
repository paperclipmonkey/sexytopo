package org.hwyl.sexytopo.shared.model.sketch

import org.hwyl.sexytopo.shared.math.getDistanceFromLine
import org.hwyl.sexytopo.shared.model.graph.Coord2D

/**
 * Ported from `org.hwyl.sexytopo.model.sketch`.
 *
 * The sketch model uses no platform graphics types at all: a path is a list of [Coord2D] in survey
 * space (metres, never pixels) and a colour is a packed RGB int. That is the single biggest reason
 * this app is portable — the drawing data needs no translation, only the renderer does.
 */

/**
 * In dark mode black ink is drawn white, as in the original.
 *
 * A property of the colour rather than of the thing drawn in it, so a toolbar swatch can show what
 * the brush will actually put on the page — otherwise a black swatch on a black background paints
 * white, which is wrong twice over.
 */
fun Colour.forDarkMode(isDarkModeActive: Boolean): Colour =
    if (isDarkModeActive && this == Colour.BLACK) Colour.WHITE else this

abstract class SketchDetail(val colour: Colour) {

    fun getDrawColour(isDarkModeActive: Boolean): Colour = colour.forDarkMode(isDarkModeActive)

    abstract fun getDistanceFrom(point: Coord2D): Float

    abstract fun translate(translation: Coord2D): SketchDetail

    abstract fun scale(scale: Float): SketchDetail
}

class PathDetail(path: List<Coord2D>, colour: Colour) : SketchDetail(colour) {

    constructor(start: Coord2D, colour: Colour) : this(listOf(start), colour)

    private val points: MutableList<Coord2D> = path.toMutableList()

    val path: List<Coord2D>
        get() = points

    fun lineTo(point: Coord2D) {
        points.add(point)
    }

    override fun getDistanceFrom(point: Coord2D): Float {
        var minDistance = Float.MAX_VALUE
        for (i in 0 until points.size - 1) {
            minDistance = minOf(minDistance, getDistanceFromLine(point, points[i], points[i + 1]))
        }
        return minDistance
    }

    override fun translate(translation: Coord2D): PathDetail =
        PathDetail(points.map { it + translation }, colour)

    override fun scale(scale: Float): PathDetail =
        PathDetail(points.map { it.scale(scale) }, colour)

    /**
     * Erasing splits a path rather than deleting it: the fragments that survive are the runs of
     * the polyline further than [radius] from the eraser.
     */
    fun getPathFragmentsOutsideRadius(targetPoint: Coord2D, radius: Float): List<PathDetail> {
        val fragments = mutableListOf<PathDetail>()
        var currentLine = mutableListOf<Coord2D>()

        for (currentPoint in points) {
            if (currentLine.isEmpty()) {
                currentLine.add(currentPoint)
                continue
            }
            val lastPoint = currentLine[currentLine.size - 1]
            val distance = getDistanceFromLine(targetPoint, lastPoint, currentPoint)
            if (distance < radius) {
                if (currentLine.size > 1) {
                    fragments.add(PathDetail(currentLine, colour))
                }
                currentLine = mutableListOf()
            }
            currentLine.add(currentPoint)
        }

        if (currentLine.size > 1) {
            fragments.add(PathDetail(currentLine, colour))
        }
        return fragments
    }
}

class SymbolDetail(
    val position: Coord2D,
    val symbolName: String,
    val size: Float,
    val angle: Float,
    colour: Colour,
) : SketchDetail(colour) {

    override fun getDistanceFrom(point: Coord2D): Float =
        org.hwyl.sexytopo.shared.math.getDistance(position, point)

    override fun translate(translation: Coord2D): SymbolDetail =
        SymbolDetail(position + translation, symbolName, size, angle, colour)

    /**
     * Grows the stamp but leaves it where it is, as in the original.
     *
     * That asymmetry looks like a bug and is not. `SinglePositionDetail.scale` returns `this`
     * unchanged with the comment "if just a point, do we need to scale anything?", and the
     * subclasses override it only to scale [size]. Scaling the position too would be right if
     * `scale` meant "zoom the whole sketch about the origin" — but its callers mean "make the
     * marks bigger", so moving them would drag every symbol away from the passage it annotates.
     */
    override fun scale(scale: Float): SymbolDetail =
        SymbolDetail(position, symbolName, size * scale, angle, colour)
}

class TextDetail(
    val position: Coord2D,
    val text: String,
    val size: Float,
    colour: Colour,
) : SketchDetail(colour) {

    override fun getDistanceFrom(point: Coord2D): Float =
        org.hwyl.sexytopo.shared.math.getDistance(position, point)

    override fun translate(translation: Coord2D): TextDetail =
        TextDetail(position + translation, text, size, colour)

    /** Grows the lettering in place; see [SymbolDetail.scale] for why the position is untouched. */
    override fun scale(scale: Float): TextDetail =
        TextDetail(position, text, size * scale, colour)
}

class Sketch {

    var pathDetails: MutableList<PathDetail> = mutableListOf()
    var symbolDetails: MutableList<SymbolDetail> = mutableListOf()
    var textDetails: MutableList<TextDetail> = mutableListOf()
    var crossSectionDetails: MutableList<CrossSectionDetail> = mutableListOf()

    /** How much bigger than life a cross-section is drawn, as in the Android app's sketch. */
    var crossSectionScale: Float = 1f

    var activeColour: Colour = Colour.BLACK

    /**
     * A working copy: new lists, the same details.
     *
     * Ported from the Java copy constructor, shallow copies and all — its own comment says "shallow
     * copies are OK here because paths are immutable", which is true of everything that matters:
     * editing a sketch adds, removes or replaces details in the list rather than mutating a detail
     * in place. (`PathDetail.lineTo` is the exception, and it is only ever called on a path the
     * editor is still drawing, which by definition is not in a copy taken beforehand.)
     *
     * Used by the cross-section editor, which draws into a copy so that cancelling really does
     * leave the original alone.
     */
    fun copy(): Sketch {
        val copy = Sketch()
        copy.pathDetails = pathDetails.toMutableList()
        copy.symbolDetails = symbolDetails.toMutableList()
        copy.textDetails = textDetails.toMutableList()
        copy.crossSectionDetails = crossSectionDetails.toMutableList()
        copy.crossSectionScale = crossSectionScale
        copy.activeColour = activeColour
        return copy
    }

    /**
     * Everything in this sketch scaled about the origin, and everything in it moved.
     *
     * Ported from `Sketch.scale` and `Sketch.translate`. Used when a cross-section's sub-sketch —
     * which is drawn in the section's own station-relative coordinates — has to be placed on the
     * main drawing, which is what the XVI exporter does for each section.
     *
     * Cross-sections are carried across unchanged: `CrossSectionDetail.scale` returns itself in the
     * original, and cross-sections do not nest anyway.
     */
    fun scale(factor: Float): Sketch {
        val scaled = Sketch()
        scaled.pathDetails = pathDetails.map { it.scale(factor) }.toMutableList()
        scaled.symbolDetails = symbolDetails.map { it.scale(factor) }.toMutableList()
        scaled.textDetails = textDetails.map { it.scale(factor) }.toMutableList()
        scaled.crossSectionDetails = crossSectionDetails.toMutableList()
        scaled.crossSectionScale = crossSectionScale
        scaled.activeColour = activeColour
        return scaled
    }

    fun translate(translation: Coord2D): Sketch {
        val moved = Sketch()
        moved.pathDetails = pathDetails.map { it.translate(translation) }.toMutableList()
        moved.symbolDetails = symbolDetails.map { it.translate(translation) }.toMutableList()
        moved.textDetails = textDetails.map { it.translate(translation) }.toMutableList()
        moved.crossSectionDetails =
            crossSectionDetails.map { it.translate(translation) }.toMutableList()
        moved.crossSectionScale = crossSectionScale
        moved.activeColour = activeColour
        return moved
    }

    fun startNewPath(start: Coord2D, colour: Colour = activeColour): PathDetail {
        val path = PathDetail(start, colour)
        pathDetails.add(path)
        return path
    }

    fun addSymbolDetail(
        position: Coord2D,
        symbolName: String,
        size: Float,
        angle: Float,
        colour: Colour = activeColour,
    ): SymbolDetail {
        val detail = SymbolDetail(position, symbolName, size, angle, colour)
        symbolDetails.add(detail)
        return detail
    }

    fun addTextDetail(
        position: Coord2D,
        text: String,
        size: Float,
        colour: Colour = activeColour,
    ): TextDetail {
        val detail = TextDetail(position, text, size, colour)
        textDetails.add(detail)
        return detail
    }

    fun addCrossSection(crossSection: CrossSection, position: Coord2D): CrossSectionDetail {
        val detail = CrossSectionDetail(position, crossSection)
        crossSectionDetails.add(detail)
        return detail
    }

    fun isEmpty(): Boolean =
        pathDetails.isEmpty() &&
            symbolDetails.isEmpty() &&
            textDetails.isEmpty() &&
            crossSectionDetails.isEmpty()
}
