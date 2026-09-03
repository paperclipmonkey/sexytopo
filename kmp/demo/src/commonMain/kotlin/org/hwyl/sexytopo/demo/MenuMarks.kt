package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The tick beside a checked menu item, drawn rather than typed.
 *
 * A prior version used the character "✓", which rendered as a missing-glyph box: the app bundles
 * Liberation Sans (Skia ships no system fonts on the web) and that font has no Dingbats block. A
 * drawn dot has no such font dependency.
 */
@Composable
fun CheckDot(checked: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .background(
                if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                CircleShape,
            ),
    )
}
