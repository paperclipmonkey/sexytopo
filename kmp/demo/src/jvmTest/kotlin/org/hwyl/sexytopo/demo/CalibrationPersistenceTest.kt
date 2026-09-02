package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An interrupted calibration comes back.
 *
 * Fifty-six shots is a twenty-minute job, and twenty minutes underground is long enough for a
 * phone to be dropped, a battery to go flat, or the app to be killed in a pocket. Losing the run
 * means doing all of it again in the same cave.
 */
class CalibrationPersistenceTest {

    private fun library() = SurveyLibrary(InMemoryFileStore())

    @Test
    fun aPartFinishedRunIsWrittenAndReadBack() {
        val library = library()
        val partial = ExampleCalibration.READINGS.take(23)

        assertTrue(library.saveCalibration(partial))

        assertEquals(partial, library.loadCalibration())
    }

    /** Nothing saved is not an error — a first run has no calibration and should not say so. */
    @Test
    fun anEmptyStoreLoadsAnEmptyRun() {
        assertEquals(emptyList(), library().loadCalibration())
    }

    /** Clearing the run clears the file, so a discarded calibration does not come back. */
    @Test
    fun clearingTheRunClearsWhatIsStored() {
        val library = library()
        library.saveCalibration(ExampleCalibration.READINGS.take(5))

        library.saveCalibration(emptyList())

        assertEquals(emptyList(), library.loadCalibration())
    }

    /** The whole loop the app performs: shoot, restart, carry on from where the run left off. */
    @Test
    fun aRunCanBeCarriedOnAfterARestart() {
        val store = InMemoryFileStore()
        val before = SurveyLibrary(store)
        before.saveCalibration(ExampleCalibration.READINGS.take(30))

        val after = SurveyLibrary(store)
        val restored = after.loadCalibration()

        assertEquals(30, restored.size)
        assertEquals(ExampleCalibration.READINGS[29], restored.last())
    }
}
