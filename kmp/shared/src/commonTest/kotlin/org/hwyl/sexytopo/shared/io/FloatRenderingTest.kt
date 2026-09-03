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
 * 1e-3..1e7; Kotlin/Wasm does not (`1e-5f` is `1.0E-5` on the JVM, `0.00001` on Wasm). Ordinary
 * survey magnitudes agree, so this is an edge case - but worth knowing about before somebody diffs
 * a survey file written on an iPhone against the same survey written on Android.
 *
 * Two consequences, and the tests below pin both: every number in a Survex, Therion or Compass file
 * goes through [formatFixed], which builds its output from integers and is byte-identical across
 * targets; but the survey and sketch JSON hands raw `Float`s to kotlinx.serialization, which can
 * spell an extreme coordinate differently per platform - value-safe (the Android app parses with
 * `getDouble`) but not byte-safe.
 */
class FloatRenderingTest {

    @Test
    fun exportFormattingNeverUsesScientificNotation() {
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
