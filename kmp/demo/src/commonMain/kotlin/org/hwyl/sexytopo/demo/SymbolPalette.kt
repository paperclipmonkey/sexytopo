package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hwyl.sexytopo.shared.model.sketch.Symbol

/**
 * The UIS symbol palette.
 *
 * Each swatch is the symbol's own artwork rather than a name, because that is how a surveyor picks
 * one: they know the shape from the printed key and not the Therion identifier. It is drawn through
 * the same path data and the same parser the canvas uses, so a swatch cannot disagree with what
 * gets stamped.
 *
 * The app reaches this from a spinner on the toolbar. Here it is a dialog off the drawing menu, for
 * the same reason the cross-section tool is: nine toolbar columns, all spoken for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymbolPaletteDialog(onDismiss: () -> Unit, onChosen: (Symbol) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Symbol") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Tap the sketch to stamp one. Directional symbols point the way you are " +
                        "looking; the rest are drawn upright.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (symbol in Symbol.entries) {
                        SymbolSwatch(symbol) { onChosen(symbol) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SymbolSwatch(symbol: Symbol, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .width(76.dp)
                .clickable(onClick = onClick)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp),
                )
                .padding(4.dp),
    ) {
        SymbolGlyph(symbol, ink)
        Text(
            symbol.label(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** The artwork at swatch size, drawn exactly as the canvas draws it. */
@Composable
private fun SymbolGlyph(symbol: Symbol, colour: Color) {
    Canvas(Modifier.size(34.dp)) {
        val path = symbolPaths[symbol] ?: return@Canvas
        val scale = size.minDimension / Symbol.VIEWPORT
        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(path, colour, style = Stroke(width = 1f))
        }
    }
}
