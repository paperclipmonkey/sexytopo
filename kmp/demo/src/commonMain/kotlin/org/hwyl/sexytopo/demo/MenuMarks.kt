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
 * It used to be the character "✓", and every one of them rendered as a missing-glyph box. The app
 * bundles Liberation Sans — it has to, because Skia ships no system fonts on the web and text
 * would otherwise not draw at all — and Liberation Sans has no Dingbats block. So every checkable
 * item in every menu showed the same small empty square whether it was on or off, which is a
 * strange way to build a menu of toggles.
 *
 * A drawn dot cannot have that problem on any platform or in any font.
 *
 * The rule this incident produced — distrust any glyph outside Latin-1 — was the wrong lesson, and
 * it cost the About box its bullets and the submenu rows their chevrons before anyone checked it.
 * Liberation Sans has 2388 characters and most of the punctuation a UI wants. `FontCoverageTest`
 * asks Skia which, so the marks that are drawn and the marks that are typed are each justified by
 * a test rather than by this anecdote.
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
