package org.hwyl.sexytopo.demo

import androidx.compose.ui.graphics.Path
import org.hwyl.sexytopo.shared.math.SvgSegment
import org.hwyl.sexytopo.shared.math.parseSvgPath
import org.hwyl.sexytopo.shared.model.sketch.Symbol

/**
 * A cave symbol as something Compose can draw.
 *
 * The shape work is all in `commonMain` — [parseSvgPath] turns the artwork into moves, lines and
 * cubics, arcs included — so this is the short remainder: three cases, one `Path`.
 *
 * The result is in the symbol's own 40-by-40 grid, centred on the origin so that scaling and
 * rotating happen about the middle of the stamp rather than its top-left corner. Callers scale by
 * `size / VIEWPORT` to reach survey metres.
 */
fun Symbol.toPath(): Path {
    val path = Path()
    val half = Symbol.VIEWPORT / 2

    for (data in paths) {
        for (segment in parseSvgPath(data)) {
            when (segment) {
                is SvgSegment.MoveTo -> path.moveTo(segment.x - half, segment.y - half)
                is SvgSegment.LineTo -> path.lineTo(segment.x - half, segment.y - half)
                is SvgSegment.CubicTo ->
                    path.cubicTo(
                        segment.x1 - half,
                        segment.y1 - half,
                        segment.x2 - half,
                        segment.y2 - half,
                        segment.x - half,
                        segment.y - half,
                    )
                SvgSegment.Close -> path.close()
            }
        }
    }
    return path
}

/**
 * Built once and kept, because the canvas repaints on every frame of a drag and re-parsing
 * nineteen symbols' worth of path data each time would be visible on a phone.
 */
val symbolPaths: Map<Symbol, Path> by lazy { Symbol.entries.associateWith { it.toPath() } }

/** Sentence case for the palette, from the Therion name the model already carries. */
fun Symbol.label(): String =
    therionName.replace('-', ' ').replaceFirstChar { it.uppercase() }
