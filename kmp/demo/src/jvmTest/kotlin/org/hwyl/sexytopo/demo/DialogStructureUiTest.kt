package org.hwyl.sexytopo.demo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import kotlin.test.Test

/**
 * What is on the dialogs, asked of the app rather than counted off a picture of it.
 *
 * Every one of these was checked in the browser by measuring: the toggles by looking down the
 * right-hand margin of the card for things the size of a switch, the settings boxes by finding
 * evenly spaced borders. Both work, and both answer a question about the drawing when the question
 * is about the contents — which is why a rounded corner could be read as a twelfth toggle, and why
 * a half-width box could hide the sixth of six.
 */
@OptIn(ExperimentalTestApi::class)
class DialogStructureUiTest {

    private fun androidx.compose.ui.test.ComposeUiTest.app() {
        setContent { App(survey = ExampleSurvey.create()) }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openSystemSettings(section: String) {
        onNodeWithTag("overflow").performClick()
        onNodeWithTag(tagFor(Strings.actionSettings)).performClick()
        onNodeWithTag(tagFor(Strings.actionSettingsSystem)).performClick()
        onNodeWithTag(tagFor(section)).performClick()
    }

    /**
     * `drawing.xml`'s two checkable groups, in its own order: `drawingMenuBehaviourToggles` and
     * then `drawingMenuDisplayToggles`. Eleven of them — `buttonHighlightLatestLeg` is a sketching
     * preference in the Android app, not a drawing-menu toggle.
     */
    @Test
    fun theDrawingOptionsAreDrawingXmlsOwnTwoGroups() = runComposeUiTest {
        app()
        onNodeWithTag("drawing-menu").performClick()
        onNodeWithTag(tagFor(Strings.toolbarSettings)).performClick()

        // `assertExists`, not `assertIsDisplayed`: these dialogs scroll, and whether a setting
        // needs a scroll to reach is a question about the size of the screen rather than about
        // whether the app offers it.
        for (toggle in BEHAVIOUR_TOGGLES + DISPLAY_TOGGLES) {
            onNodeWithText(toggle.label).assertExists()
        }
        // The one that is not on this dialog, because the Android app does not put it here.
        onNodeWithText(Strings.settingsHighlightLatestLegTitle).assertDoesNotExist()
    }

    /**
     * `preferences_sketching.xml`: its five switches, then its numeric group.
     *
     * This port had the numbers in a different order and no switches at all, which is what put the
     * browser check's "first box" on a different setting from the one it meant.
     */
    @Test
    fun theSketchingScreenIsPreferencesSketching() = runComposeUiTest {
        app()
        openSystemSettings(Strings.settingsSketchingTitle)

        for (row in SKETCHING) onNodeWithText(row).assertExists()
    }

    /** `preferences_instruments.xml`: the tolerances, and the two switches below them. */
    @Test
    fun theInstrumentsScreenIsPreferencesInstruments() = runComposeUiTest {
        app()
        openSystemSettings(Strings.settingsInstrumentsTitle)

        for (row in INSTRUMENTS) onNodeWithText(row).assertExists()
    }

    /** `preferences_general.xml`: the theme list, and the buzz. */
    @Test
    fun theGeneralScreenIsPreferencesGeneral() = runComposeUiTest {
        app()
        openSystemSettings(Strings.settingsGeneralTitle)

        onNodeWithText(Strings.settingsThemeTitle).assertExists()
        for (theme in listOf(Strings.themeAutomatic, Strings.themeLight, Strings.themeDark)) {
            onNodeWithText(theme).assertExists()
        }
        onNodeWithText(Strings.settingsVibrateTitle).assertExists()
    }

    /** `preferences_manual_data_entry.xml`, whose five switches the browser checks by index. */
    @Test
    fun theManualEntryScreenIsPreferencesManualDataEntry() = runComposeUiTest {
        app()
        openSystemSettings(Strings.settingsManualDataEntryTitle)

        for (row in MANUAL_ENTRY) onNodeWithText(row).assertExists()
    }

    private companion object {
        /** In `preferences_sketching.xml`'s order: the switches, then the numbers. */
        val SKETCHING = listOf(
            Strings.settingsHotCornersTitle,
            Strings.settingsDeleteFragmentsTitle,
            Strings.settingsHighlightLatestLegTitle,
            Strings.settingsTwoFingerTitle,
            Strings.settingsLegacyCrossSectionsTitle,
            Strings.settingsTextToolSizeTitle,
            Strings.settingsSymbolSizeTitle,
            Strings.settingsSketchLineWidthTitle,
            Strings.settingsLegWidthTitle,
        )
        val INSTRUMENTS = listOf(
            Strings.settingsMaxDistanceDeltaTitle,
            Strings.settingsMaxAngleDeltaTitle,
            Strings.settingsAutoReconnectTitle,
            Strings.settingsDeveloperModeTitle,
        )
        val MANUAL_ENTRY = listOf(
            Strings.settingsManualControlsTitle,
            Strings.settingsLrudFieldsTitle,
            Strings.settingsLrudDirectionTitle,
            Strings.settingsAzimuthDmsTitle,
            Strings.settingsInclinationDmsTitle,
        )
    }
}
