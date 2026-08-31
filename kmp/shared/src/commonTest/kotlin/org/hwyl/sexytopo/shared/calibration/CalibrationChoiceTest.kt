package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which calibration fit runs: `pref_calibration_algorithm`'s three values.
 *
 * The interesting one is *Auto*, because the right answer is a property of the instrument rather
 * than of the surveyor. `DistoX.prefersNonLinearCalibration` is a four-row table upstream — X310
 * and DistoX-BLE yes, A3 no, anything unrecognised no — and it lives on [InstrumentProfile] here
 * so the device matrix stays in one place.
 */
class CalibrationChoiceTest {

    @Test
    fun theDefaultIsTheSaferFit() {
        // `getString("pref_calibration_algorithm", "linear")`, and the Java's own comment on the
        // variable it feeds: "linear probably safer as default".
        assertEquals(CalibrationChoice.LINEAR, CalibrationChoice.DEFAULT)
    }

    @Test
    fun theTwoFixedChoicesIgnoreWhatIsAttached() {
        for (attached in listOf(null, InstrumentProfile.DISTOX_BLE, InstrumentProfile.BRIC4)) {
            assertFalse(CalibrationChoice.LINEAR.useNonLinearity(attached))
            assertTrue(CalibrationChoice.NON_LINEAR.useNonLinearity(attached))
        }
    }

    /** Auto asks the device, and the DistoX-BLE is one that wants the extra terms. */
    @Test
    fun autoAsksTheInstrument() {
        assertTrue(CalibrationChoice.AUTO.useNonLinearity(InstrumentProfile.DISTOX_BLE))
        assertFalse(CalibrationChoice.AUTO.useNonLinearity(InstrumentProfile.CAVWAY_X1))
    }

    /**
     * And with nothing attached it is linear, rather than a crash.
     *
     * Upstream reaches the same answer by accident: `getDistox()` throws when nothing is
     * connected, the whole switch is wrapped in a `try`, and the comment says "just return false
     * and deal with issues elsewhere". Arriving there on purpose is the same behaviour without the
     * exception.
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
