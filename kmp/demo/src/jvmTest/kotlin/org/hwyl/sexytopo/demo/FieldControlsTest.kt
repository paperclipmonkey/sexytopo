package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the field bar offers, and the one thing it must never offer.
 */
class FieldControlsTest {

    /**
     * *Simulate* is not on the screen when a real instrument is attached.
     *
     * `SurveySession.takeReading` detaches whatever is attached and emits a fabricated shot into
     * the live survey. Pressed with a BRIC on the tripod that is two harms at once: a made-up leg
     * that is indistinguishable from a real one for ever afterwards, and an instrument silently
     * disconnected while the surveyor carries on shooting into nothing.
     *
     * The button is ten millimetres from *Add reading*, on a phone, in a wet bag, with cold hands.
     * See finding 58.
     */
    @Test
    fun theSimulatorIsNotOfferedOverARealInstrument() {
        val withInstrument =
            FieldControls.of(AppPreferences.DEFAULT, InstrumentProfile.BRIC4)

        assertFalse(withInstrument.simulator, "a fabricated shot is one tap from a real survey")
        assertTrue(withInstrument.manualEntry, "typing a reading is still how a DistoX is read out")
    }

    /** And it is there when there is nothing to demonstrate the app with. */
    @Test
    fun theSimulatorIsOfferedWhenNoInstrumentIsAttached() {
        assertTrue(FieldControls.of(AppPreferences.DEFAULT, null).simulator)
    }

    /** `pref_manual_controls`, on by default as upstream has it. */
    @Test
    fun manualEntryIsOfferedUntilTurnedOff() {
        assertTrue(AppPreferences.DEFAULT.manualControls)
        assertFalse(
            FieldControls.of(AppPreferences(manualControls = false), null).manualEntry,
        )
    }

    @Test
    fun theManualControlsPreferenceSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(store, AppPreferences(manualControls = false))

        assertFalse(AppPreferencesStore.load(store).manualControls)
    }

    /**
     * The harm itself, asserted rather than described: taking a simulated reading over a real
     * instrument both fabricates a leg and drops the link.
     *
     * This is what the button did, and it is why hiding it is a fix rather than a tidy-up.
     */
    @Test
    fun takingASimulatedReadingReallyDoesAbandonTheInstrument() {
        val session = SurveySession(Survey("Swildons"))
        session.attachForTest(
            org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument(),
            org.hwyl.sexytopo.shared.comms.InstrumentDecoder.classicDistoX(),
            InstrumentProfile.BRIC4,
        )
        assertEquals(InstrumentProfile.BRIC4, session.profile)

        session.takeReading()

        assertEquals(null, session.profile, "the real instrument was silently swapped out")
    }
}
