package org.hwyl.sexytopo.shared.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.hwyl.sexytopo.shared.io.export.formatFixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How floats reach a file, and the one place the port cannot make every target agree.
 *
 * `Float.toString()` is **not** the same function on every Kotlin target. Java (and therefore
 * Kotlin/JVM, and therefore the Android app) switches to scientific notation outside roughly
 * 1e-3..1e7; Kotlin/Wasm does not:
 *
 * | value   | JVM       | Wasm         |
 * | ------- | --------- | ------------ |
 * | `1e-5f` | `1.0E-5`  | `0.00001`    |
 * | `1e7f`  | `1.0E7`   | `10000000.0` |
 *
 * Ordinary survey magnitudes agree, so this is an edge case - but it is a real one, and worth
 * knowing about before somebody diffs a survey file written on an iPhone against the same survey
 * written on Android and concludes the port is broken.
 *
 * Two consequences, and the tests below pin both:
 *
 *  - **The exporters are safe.** Every number in a Survex, Therion or Compass file goes through
 *    [formatFixed], which builds its output from integers and never calls `Float.toString`. Those
 *    formats are byte-identical across targets, and their golden tests would catch it if that
 *    changed.
 *  - **The JSON is value-safe but not byte-safe.** The survey and sketch writers hand raw `Float`s
 *    to kotlinx.serialization, which stringifies them - so an extreme coordinate can be spelled
 *    differently on different platforms. Both spellings are valid JSON for the same number, and the
 *    Android app parses with `getDouble`, so nothing is lost; the file just is not byte-identical.
 *
 * Making the JSON byte-identical too would mean reimplementing Java's shortest-round-trip
 * `Float.toString`, which is a real piece of work for a case no surveyor will hit. Documented and
 * guarded is the proportionate answer.
 */
class FloatRenderingTest {

    @Test
    fun exportFormattingNeverUsesScientificNotation() {
        // The values that diverge under Float.toString must not diverge here.
        assertEquals("0.00001", formatFixed(1e-5f, 5))
        assertEquals("10000000.00", formatFixed(1e7f, 2))
        assertEquals("0.00", formatFixed(1e-5f, 2))
        for (rendered in listOf(formatFixed(1e-5f, 5), formatFixed(1e7f, 2))) {
            assertTrue(!rendered.contains("E") && !rendered.contains("e"), "got $rendered")
        }
    }

    /**
     * Value fidelity is the property that matters, and it holds on every target regardless of how
     * the number is spelled in the file.
     *
     * Tested at the JSON layer rather than through [SketchJson], because `SketchJson.parse` applies
     * the Java's load-time stroke simplification - points legitimately disappear on the way back in,
     * which is correct behaviour and would mask what this is checking.
     */
    @Test
    fun extremeFloatsSurviveAJsonRoundTripExactly() {
        val extremes = listOf(1e-5f, -1e-5f, 1e7f, -1e7f, 12.3456f, 0f, -0.0f, 3.28084f)
        for (value in extremes) {
            val written = buildJsonObject { put("v", value) }.toString()
            val readBack = Json.parseToJsonElement(written).jsonObject["v"]!!.jsonPrimitive.float
            assertEquals(value, readBack, "round trip of $value via $written")
        }
    }

    /**
     * Records the divergence itself, so a change in either direction is noticed.
     *
     * Deliberately not asserting one spelling: the point is that both occur and mean the same
     * thing. If a future Kotlin makes the targets agree, this still passes - and if the port ever
     * needs byte-identical JSON, this is the test to tighten.
     */
    @Test
    fun anExtremeFloatIsSpelledOneOfTwoWaysDependingOnTarget() {
        val written = buildJsonObject { put("v", 1e-5f) }.toString()
        val spellings = listOf("1.0E-5", "0.00001")
        assertTrue(
            spellings.any { written.contains(it) },
            "expected one of $spellings, got $written",
        )
    }
}
