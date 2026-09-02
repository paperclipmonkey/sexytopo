package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which calibration fit runs: `pref_calibration_algorithm`'s three values. *Auto* is the
 * interesting one, since the right answer is a property of the instrument, not the surveyor.
 */
class CalibrationChoiceTest {

    @Test
    fun theDefaultIsTheSaferFit() {
        // The Java's own comment on the variable it feeds: "linear probably safer as default".
        assertEquals(CalibrationChoice.LINEAR, CalibrationChoice.DEFAULT)
    }

    @Test
    fun theTwoFixedChoicesIgnoreWhatIsAttached() {
        for (attached in listOf(null, InstrumentProfile.DISTOX_BLE, InstrumentProfile.BRIC4)) {
            assertFalse(CalibrationChoice.LINEAR.useNonLinearity(attached))
            assertTrue(CalibrationChoice.NON_LINEAR.useNonLinearity(attached))
        }
    }

    @Test
    fun autoAsksTheInstrument() {
        assertTrue(CalibrationChoice.AUTO.useNonLinearity(InstrumentProfile.DISTOX_BLE))
        assertFalse(CalibrationChoice.AUTO.useNonLinearity(InstrumentProfile.CAVWAY_X1))
    }

    /**
     * With nothing attached it is linear, rather than a crash — upstream reaches the same answer
     * by accident, since `getDistox()` throws and the whole switch is wrapped in a `try`.
     */
    @Test
    fun nothingAttachedIsLinear() {
        assertFalse(CalibrationChoice.AUTO.useNonLinearity(null))
    }

    /** A value written by a version that knew a fourth answer reads as the default. */
    @Test
    fun anUnknownAlgorithmReadsAsTheDefault() {
        assertNull(CalibrationChoice.of("quadratic"))
        assertNull(CalibrationChoice.of(null))
        assertEquals(CalibrationChoice.AUTO, CalibrationChoice.of("auto"))
    }

    /** The stored strings are the Android app's own, so a preferences file crosses over. */
    @Test
    fun theStoredValuesAreTheAndroidApps() {
        assertEquals("auto", CalibrationChoice.AUTO.key)
        assertEquals("linear", CalibrationChoice.LINEAR.key)
        assertEquals("nonlinear", CalibrationChoice.NON_LINEAR.key)
    }
}
