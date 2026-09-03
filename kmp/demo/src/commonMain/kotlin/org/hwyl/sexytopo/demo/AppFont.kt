package org.hwyl.sexytopo.demo

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.hwyl.sexytopo.demo.resources.LiberationSans_Bold
import org.hwyl.sexytopo.demo.resources.LiberationSans_Regular
import org.hwyl.sexytopo.demo.resources.Res
import org.jetbrains.compose.resources.Font

/**
 * The app bundles its own font rather than relying on the platform's: Skia has **no system fonts
 * on the web target**, so every text draw throws and the whole app renders blank.
 *
 * Liberation Sans, SIL OFL 1.1 — see demo/LICENSE-LiberationSans.txt.
 */
val LocalAppFontFamily = compositionLocalOf<FontFamily> { FontFamily.Default }

/** Loads and preloads the bundled font, then renders [content] with it in scope. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun WithBundledFont(content: @Composable (Typography) -> Unit) {
    val fontFamily =
        FontFamily(
            Font(Res.font.LiberationSans_Regular, FontWeight.Normal),
            Font(Res.font.LiberationSans_Bold, FontWeight.Bold),
        )

    val resolver = LocalFontFamilyResolver.current
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(fontFamily, resolver) {
        runCatching { resolver.preload(fontFamily) }
        ready = true
    }

    if (!ready) return

    CompositionLocalProvider(LocalAppFontFamily provides fontFamily) {
        content(typographyUsing(fontFamily))
    }
}

/** Material's default type scale, restated on the bundled family so no style falls back. */
private fun typographyUsing(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}
