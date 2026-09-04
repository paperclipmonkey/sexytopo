package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every colour this port paints with is one the Android app paints with.
 *
 * `SexyTopoColours` says it is "taken from `colors.xml` … kept exactly rather than restyled", and
 * that is the sort of claim that stays true until somebody nudges a hex value to look better on a
 * screenshot. So this reads `values/colors.xml` and `values-night/colors.xml`, follows the
 * `@color/` references the night file is written in, and checks that every literal in the theme
 * file is a value one of them defines.
 *
 * By value rather than by name, because the names do not line up one to one: the app's
 * `lightBackground` is this port's `canvasBackground`, and one resource often stands behind a light
 * and a night property here. A value the app has *somewhere* is a colour taken from the app; a
 * value it has nowhere is one somebody made up, and the one that was made up is listed.
 */
class ColourParityTest {

    private val values = File("../../app/src/main/res/values")

    private val theme = File("src/commonMain/kotlin/org/hwyl/sexytopo/demo/SexyTopoTheme.kt")

    /** Every colour resource, light and night, resolved to its six-digit RGB. */
    private val appColours: Map<String, String> by lazy {
        val declared = mutableMapOf<String, String>()
        for (file in listOf(File(values, "colors.xml"), File(values.parentFile, "values-night/colors.xml"))) {
            if (!file.isFile) continue
            Regex("""<color name="([^"]+)">\s*(\S+?)\s*</color>""").findAll(file.readText()).forEach {
                // A night entry shadows a light one under the same name: keep both by suffixing.
                val name = it.groupValues[1]
                declared[if (name in declared) "$name@night" else name] = it.groupValues[2]
            }
        }
        // References resolve to whichever plain value they point at, in either file.
        fun resolve(value: String, depth: Int = 0): String? =
            when {
                value.startsWith("#") -> value.removePrefix("#").takeLast(6).lowercase()
                value.startsWith("@color/") && depth < 5 ->
                    declared[value.removePrefix("@color/")]?.let { resolve(it, depth + 1) }
                else -> null
            }
        declared.mapNotNull { (name, value) -> resolve(value)?.let { name to it } }.toMap()
    }

    /**
     * Port colours with no counterpart, each with the reason. The night hot corner is this port's
     * own: the app has no night value for it and draws the day one, which on a dark canvas is
     * invisible.
     */
    private val portsOwn = setOf("hotCornerNight")

    @Test
    fun everyColourInTheThemeIsOneTheAndroidAppDefines() {
        assertTrue(appColours.size > 30, "colors.xml has moved or emptied: ${appColours.size} read")

        val known = appColours.values.toSet()
        val invented =
            Regex("""val (\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
                .findAll(theme.readText())
                .filter { it.groupValues[1] !in portsOwn }
                .filter { it.groupValues[2].takeLast(6).lowercase() !in known }
                .map { "${it.groupValues[1]} = #${it.groupValues[2]}" }
                .toList()

        assertEquals(
            emptyList(),
            invented,
            "these colours appear in SexyTopoTheme.kt and nowhere in the Android app's colors.xml",
        )
    }

    /** A colour the port claims as its own but the app has since defined is no longer its own. */
    @Test
    fun theListOfInventedColoursIsStillTrue() {
        val known = appColours.values.toSet()
        val nowUpstream =
            Regex("""val (\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
                .findAll(theme.readText())
                .filter { it.groupValues[1] in portsOwn && it.groupValues[2].takeLast(6).lowercase() in known }
                .map { it.groupValues[1] }
                .toList()
        assertEquals(emptyList(), nowUpstream, "the app now defines these; drop them from portsOwn")
    }
}
