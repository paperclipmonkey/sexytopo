package org.hwyl.sexytopo.demo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.StationFix
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The position screen, driven rather than described.
 *
 * The receiver is the half of this that a build server cannot run — the JVM has none, which is
 * exactly what makes these tests possible: the screen opens in its typed-in mode, which is the
 * mode with all the validation in it and the one a surveyor uses when the sky is no good.
 *
 * What is being checked is the join. `StationFixTest` says what a coordinate may be and
 * `SurvexTherionFixTest` says what gets written; between them sits a dialog that has to put the
 * right numbers on the right survey, and refuse to when they are not numbers. A screen that
 * silently saved nothing would pass both of those.
 */
@OptIn(ExperimentalTestApi::class)
class StationPositionUiTest {

    private fun survey(): Survey =
        Survey("Test").also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 90f, 0f)) }

    private fun androidx.compose.ui.test.ComposeUiTest.dialog(survey: Survey, onSaved: () -> Unit = {}) {
        setContent {
            StationPositionDialog(survey = survey, onDismiss = {}, onSaved = onSaved)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.type(tag: String, text: String) {
        onNodeWithTag(tag).performTextClearance()
        onNodeWithTag(tag).performTextInput(text)
    }

    /**
     * A position typed in reaches the survey, which is the whole point of the screen.
     *
     * Saved as *entered* rather than measured, because that is what it was: the export writes no
     * accuracy for a typed position, and a screen that labelled hand-typed coordinates as measured
     * would put an error bar on them that nobody had measured.
     */
    @Test
    fun aTypedPositionReachesTheSurvey() = runComposeUiTest {
        val survey = survey()
        var saved = false
        dialog(survey) { saved = true }

        type("position-latitude", "54.12345")
        type("position-longitude", "-2.34567")
        type("position-altitude", "312")
        onNodeWithTag("position-save").performClick()

        val fix = survey.fix
        assertTrue(fix != null, "saving did not put a position on the survey")
        assertEquals("1", fix.station)
        assertEquals(54.12345, fix.latitude)
        assertEquals(-2.34567, fix.longitude)
        assertEquals(312.0, fix.altitude)
        assertEquals(StationFix.Source.ENTERED, fix.source)
        assertEquals(null, fix.horizontalAccuracy, "a typed position was given an accuracy")
        assertTrue(saved, "the survey was never told it had changed")
    }

    /**
     * Half a position cannot be saved, and neither can one that is not numbers.
     *
     * Disabled rather than refused on the way out: a Save that does nothing is the same screen as
     * a Save that worked, right up until somebody exports the survey a week later.
     */
    @Test
    fun anIncompleteOrUnreadablePositionCannotBeSaved() = runComposeUiTest {
        val survey = survey()
        dialog(survey)

        onNodeWithTag("position-save").assertIsNotEnabled()

        type("position-latitude", "54.12345")
        type("position-longitude", "-2.34567")
        onNodeWithTag("position-save").assertIsNotEnabled()

        // Degrees and minutes, which is what a paper map says and what this deliberately refuses.
        type("position-altitude", "312")
        type("position-latitude", "54 07.4")
        onNodeWithTag("position-save").assertIsNotEnabled()

        type("position-latitude", "54.12345")
        onNodeWithTag("position-save").assertIsEnabled()

        assertEquals(null, survey.fix, "nothing should have been saved along the way")
    }

    /**
     * A fix has to name a station this survey has, because a fix that does not stops both
     * exporters — see the commented-out block `SurvexTherionFixTest` insists on for the case where
     * the station is renamed *afterwards*. This is the same fault caught a week earlier.
     */
    @Test
    fun aPositionCannotBeSavedAgainstAStationThatIsNotThere() = runComposeUiTest {
        val survey = survey()
        dialog(survey)

        type("position-latitude", "54.12345")
        type("position-longitude", "-2.34567")
        type("position-altitude", "312")
        onNodeWithTag("position-save").assertIsEnabled()

        type("position-station", "nowhere")
        onNodeWithTag("position-save").assertIsNotEnabled()
    }

    /**
     * Opening the screen on a survey that already has a position shows it rather than an empty
     * form, and Remove takes it off.
     *
     * The first half is what stops somebody opening it to look and losing what was there. The
     * second is deliberately not next to Save: the app has already learned once, in finding 110,
     * what happens when something destructive sits where a stray tap lands.
     */
    @Test
    fun anExistingPositionIsShownAndCanBeTakenOff() = runComposeUiTest {
        val survey = survey()
        survey.fix = StationFix("1", 51.24939, -2.72519, 236.0, source = StationFix.Source.ENTERED)
        var saved = false
        dialog(survey) { saved = true }

        // Shown: Save is available without anything being typed, which it is not on a blank form.
        onNodeWithTag("position-save").assertIsEnabled()

        onNodeWithTag("position-remove").performClick()

        assertEquals(null, survey.fix, "Remove left the position on the survey")
        assertTrue(saved, "the survey was never told it had changed")
    }
}
