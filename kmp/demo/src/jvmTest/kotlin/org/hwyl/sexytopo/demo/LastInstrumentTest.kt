package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The app remembering which instrument is the surveyor's.
 *
 * A cave trip is one instrument, all day. Making somebody pick theirs out of a list of nine every
 * time the app opens is the part of "I had to close and reopen SexyTopo" that is nobody's radio's
 * fault.
 *
 * What is *not* here is the connecting: [platformTransportFor] returns null on every target a test
 * can run on, so a JVM session never attaches and never has a profile. What these check is the
 * memory either side of that — which is where the defect was.
 */
class LastInstrumentTest {

    private fun state(library: SurveyLibrary) =
        DemoState(
            exampleSurvey = ExampleSurvey.create(),
            initialProjection = Projection2D.PLAN,
            initialSystemDark = false,
            initialTool = null,
            initialMode = SurveyMode.EXAMPLE,
            initialScreen = Screen.SKETCH,
            library = library,
        )

    @Test
    fun choosingAnInstrumentIsRememberedForNextTime() {
        val library = SurveyLibrary(InMemoryFileStore())
        val first = state(library)
        first.loadSettings()

        first.useInstrument(InstrumentProfile.BRIC4)

        assertEquals(InstrumentProfile.BRIC4.name, first.preferences.lastInstrument)

        // A whole new launch, reading the file the first one wrote.
        val second = state(library)
        second.loadSettings()
        assertEquals(InstrumentProfile.BRIC4, second.lastInstrument)
    }

    /** Remembered even where there is no radio to try it on: it is still what the surveyor owns. */
    @Test
    fun anInstrumentIsRememberedEvenWhereNothingCanConnectToIt() {
        val state = state(SurveyLibrary(InMemoryFileStore()))
        state.loadSettings()

        state.useInstrument(InstrumentProfile.CAVWAY_X1)

        assertNull(state.session.profile, "the JVM has no radio, so nothing should have attached")
        assertEquals(InstrumentProfile.CAVWAY_X1, state.lastInstrument)
    }

    /** A name from a newer version, or a typed file: no instrument rather than a crash. */
    @Test
    fun anInstrumentThisVersionDoesNotKnowIsNoInstrument() {
        val library = SurveyLibrary(InMemoryFileStore())
        val state = state(library)
        state.loadSettings()

        state.updatePreferences(state.preferences.copy(lastInstrument = "Theodolite"))

        assertNull(state.lastInstrument)
        state.resumeLastInstrument() // must not throw
    }

    /** And nothing is picked up on a launch by somebody who has turned chasing off. */
    @Test
    fun withChasingOffTheRadioIsNotWokenByOpeningTheApp() {
        val state = state(SurveyLibrary(InMemoryFileStore()))
        state.loadSettings()
        state.useInstrument(InstrumentProfile.BRIC4)
        state.updatePreferences(state.preferences.copy(autoReconnect = false))

        state.resumeLastInstrument()

        assertNull(state.session.profile)
    }
}
