package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.model.sketch.Symbol

/**
 * One UIS symbol at button size, drawn through the same path data and parser the canvas uses — so
 * what the symbol strip shows cannot disagree with what a tap stamps.
 *
 * The strip itself is in [SketchToolbar], because that is where `activity_graph.xml` puts it: a
 * `HorizontalScrollView` between the drawing and the button grid, not a dialog. This port did
 * offer it as a dialog off the drawing menu, which was one tap further away and did not look like
 * the app.
 */
@Composable
internal fun SymbolGlyph(symbol: Symbol, colour: Color, size: Dp = 34.dp) {
    Canvas(Modifier.size(size)) {
        val path = symbolPaths[symbol] ?: return@Canvas
        val scale = this.size.minDimension / Symbol.VIEWPORT
        withTransform({
            translate(this.size.width / 2, this.size.height / 2)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(path, colour, style = Stroke(width = 1f))
        }
    }
}
