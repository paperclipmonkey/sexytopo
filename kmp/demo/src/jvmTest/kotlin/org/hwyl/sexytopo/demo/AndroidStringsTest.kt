package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every string this port shows, held to the Android app's own `strings.xml`.
 *
 * The Android app keeps its wording in one file and this port keeps a mirror of it in [Strings].
 * A mirror nobody checks is a mirror that drifts, and the drift is invisible: nothing breaks, the
 * two apps simply stop being the same app. So this reads the real resource file and compares, name
 * by name. Renaming or rewording a string upstream fails here, which is the point — the port is
 * then told what to change rather than left saying something the app no longer says.
 *
 * The comparison is against the *decoded* value, because `strings.xml` is XML: `&#176;` is a
 * degree sign and `\'` is an apostrophe, and a mirror written in escapes would be checking the
 * encoding rather than the words.
 */
class AndroidStringsTest {

    private val resourceFile = File("../../app/src/main/res/values/strings.xml")

    private val arrayFile = File("../../app/src/main/res/values/arrays.xml")

    private val declared: Map<String, String> by lazy {
        assertTrue(resourceFile.isFile, "the Android app's strings have moved: $resourceFile")
        // Some arrays live in their own file; both are searched so a moved array is still found.
        val extra = if (arrayFile.isFile) arrayFile.readText() else ""
        parseAndroidStrings(resourceFile.readText()) + parseAndroidStrings(extra)
    }

    @Test
    fun everyMirroredStringIsOneTheAndroidAppHas() {
        val missing = Strings.resources.keys.filterNot { it in declared }
        assertEquals(
            emptyList(),
            missing,
            "these are mirrored in Strings.kt but no longer exist in strings.xml; find what " +
                "they were renamed to rather than deleting the mirror",
        )
    }

    @Test
    fun everyMirroredStringSaysWhatTheAndroidAppSays() {
        val wrong =
            Strings.resources
                .filter { (name, value) -> name in declared && declared[name] != value }
                .map { (name, value) -> "$name: mirrored \"$value\", app says \"${declared[name]}\"" }
        assertEquals(
            emptyList(),
            wrong,
            "Strings.kt has drifted from app/src/main/res/values/strings.xml",
        )
    }

    /**
     * The mirror is worth having only if it is what the app actually types, so this looks for
     * user-facing text left inline in the composables.
     *
     * Not a general lint — a heuristic against the strings this port is known to have hardcoded,
     * so a reverted edit is caught. It deliberately names the wording rather than a pattern:
     * a regex over every string literal in a Compose file matches content descriptions, test
     * hooks and format fragments, and would be turned off within a week.
     */
    @Test
    fun theWordingTheAppOnceHardcodedIsGone() {
        val sources =
            File("src/commonMain/kotlin/org/hwyl/sexytopo/demo")
                .walkTopDown()
                .filter { it.extension == "kt" && it.name != "Strings.kt" }
                .toList()
        assertTrue(sources.size > 20, "the Compose sources have moved: found ${sources.size}")

        val banned =
            listOf(
                "\"Statistics…\"",
                "\"Trip details…\"",
                "\"Delete the last leg\"",
                "\"Centre view\"",
                "\"Find a station…\"",
                "\"Show grid\"",
                "\"Show north\"",
                "\"Show station labels\"",
                "\"Snap to lines\"",
                "\"Pinch to zoom\"",
                "\"Water is blue\"",
                "\"Follow the survey\"",
                "\"Fade all but the working end\"",
                "\"Show cross-sections\"",
                "\"Show sketch\"",
                "\"Show splays\"",
                "\"New survey…\"",
                "\"Rename survey…\"",
                "\"Add a leg\"",
                "\"Add a splay\"",
                "\"Full screen\"",
                "\"Distance\"",
                "\"Azimuth\"",
                "\"Inclination\"",
            )

        val offences = mutableListOf<String>()
        for (source in sources) {
            val text = source.readText()
            for (phrase in banned) {
                if (text.contains(phrase)) offences.add("${source.name} still types $phrase")
            }
        }
        assertEquals(
            emptyList(),
            offences.sorted(),
            "use Strings.kt so the wording stays the Android app's",
        )
    }
}

/**
 * The `name` to text of every `<string>` in an Android resource file, with XML and Android
 * escaping undone.
 *
 * Enough of the format for this one file: no plurals, no string arrays, no CDATA. Anything else
 * appearing upstream shows up as a mirrored name that cannot be found, which is a failing test
 * rather than a silent pass.
 */
internal fun parseAndroidStrings(xml: String): Map<String, String> {
    val strings = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    val arrays =
        Regex("""<string-array name="([^"]+)"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
    val items = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

    val found = mutableMapOf<String, String>()
    for (match in strings.findAll(xml)) {
        found[match.groupValues[1]] = unescapeAndroidString(match.groupValues[2])
    }
    // Array items are keyed `name[index]`, which is how `Strings.item` registers them.
    for (match in arrays.findAll(xml)) {
        val name = match.groupValues[1]
        items.findAll(match.groupValues[2]).forEachIndexed { index, item ->
            found["$name[$index]"] = unescapeAndroidString(item.groupValues[1])
        }
    }
    return found
}

internal fun unescapeAndroidString(raw: String): String {
    val entities = Regex("""&(#\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos);""")
    val decoded =
        entities.replace(raw) { match ->
            when (val body = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else ->
                    if (body.startsWith("#x")) {
                        body.drop(2).toInt(16).toChar().toString()
                    } else {
                        body.drop(1).toInt().toChar().toString()
                    }
            }
        }
    return decoded
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\@", "@")
}
