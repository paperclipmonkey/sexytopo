package org.hwyl.sexytopo.demo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.survey.InputMode
import kotlin.test.Test

/**
 * The overflow menu's shape, driven through the app rather than measured off a picture of it.
 *
 * `field.mjs` checks this in a real browser by working out which pixel a row is drawn at, which is
 * the right instrument for "is the cave drawn" and the wrong one for "is *Save As…* on the File
 * page". A menu row that moves breaks the arithmetic in a way that surfaces hundreds of lines
 * later as something unrelated — a survey that was never named, a dialog that would not open.
 *
 * These ask the semantics tree instead. They run headlessly on the JVM in a couple of seconds, so
 * the whole class of breakage fails at `./gradlew :demo:jvmTest` before anything is pushed.
 */
@OptIn(ExperimentalTestApi::class)
class MenuStructureUiTest {

    /** Open the overflow menu, then walk down into one of `action_bar.xml`'s groups. */
    private fun androidx.compose.ui.test.ComposeUiTest.openMenu(vararg groups: String) {
        onNodeWithTag("overflow").performClick()
        for (group in groups) onNodeWithTag(tagFor(group)).performClick()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.app() {
        setContent { App(survey = ExampleSurvey.create()) }
    }

    /**
     * `action_bar.xml`'s own seven submenus and then `connection_group`, in its order.
     *
     * The port had these flattened into one list, which is what sent *New Survey* to a row that
     * had become *Open*.
     */
    @Test
    fun theTopOfTheMenuIsTheActionBarsOwnGroups() = runComposeUiTest {
        app()
        onNodeWithTag("overflow").performClick()

        for (group in TOP) onNodeWithTag(tagFor(group)).assertIsDisplayed()
    }

    /** `action_file`: `basic_file_handling`, then `import_export`. */
    @Test
    fun theFilePageIsActionFile() = runComposeUiTest {
        app()
        openMenu(Strings.actionFile)

        for (row in FILE) onNodeWithTag(tagFor(row)).assertIsDisplayed()
        // One page at a time: the groups the top of the menu offered are gone while this one is up.
        onNodeWithTag(tagFor(Strings.actionView)).assertDoesNotExist()
    }

    /** `action_view`: the six views, `view_display`, and this port's own demo cave. */
    @Test
    fun theViewPageIsActionView() = runComposeUiTest {
        app()
        openMenu(Strings.actionView)

        // By tag rather than by text: the app bar's title is the name of the view being
        // looked at, so *Plan* is drawn twice while this page is open.
        for (row in VIEW) onNodeWithTag(tagFor(row)).assertIsDisplayed()
    }

    /**
     * `action_tools`, whose diagnostics group holds the system log.
     *
     * It was on the Instrument page in this port, which `action_device_menu` has no room for: that
     * menu is *Connect…* and the connected instrument's own commands.
     */
    @Test
    fun theToolsPageIsActionTools() = runComposeUiTest {
        app()
        openMenu(Strings.actionTools)

        for (row in TOOLS) onNodeWithTag(tagFor(row)).assertIsDisplayed()
    }

    /** `action_settings`, and `preferences_main.xml`'s sections one level below it. */
    @Test
    fun theSettingsPagesArePreferencesMain() = runComposeUiTest {
        app()
        openMenu(Strings.actionSettings)

        onNodeWithTag(tagFor(Strings.actionSettingsSystem)).assertIsDisplayed()
        onNodeWithTag(tagFor(Strings.actionSettingsSurvey)).assertIsDisplayed()

        onNodeWithTag(tagFor(Strings.actionSettingsSystem)).performClick()
        for (row in SYSTEM_SETTINGS) onNodeWithTag(tagFor(row)).assertIsDisplayed()
    }

    /** `action_input`'s `input_mode_group`, which had no UI in this port at all. */
    @Test
    fun theInputPageOffersEveryInputMode() = runComposeUiTest {
        app()
        openMenu(Strings.actionInput)

        for (mode in InputMode.entries) {
            onNodeWithTag(tagFor(labelFor(mode))).assertIsDisplayed()
        }
    }

    /** Every group page starts with a way back, because an Android submenu has the system's own. */
    @Test
    fun backLeavesAGroupPage() = runComposeUiTest {
        app()
        openMenu(Strings.actionHelp)
        onNodeWithTag(tagFor(Strings.actionAbout)).assertIsDisplayed()

        onNodeWithTag(tagFor(BACK_ROW)).performClick()

        // The top of the menu again, not the page that was showing.
        onNodeWithTag(tagFor(Strings.actionFile)).assertIsDisplayed()
        onNodeWithTag(tagFor(Strings.actionAbout)).assertDoesNotExist()
    }

    /** And *Back* from a page two levels down goes to the page that opened it, not to the top. */
    @Test
    fun backFromAPageTwoDeepGoesToTheOneThatOpenedIt() = runComposeUiTest {
        app()
        openMenu(Strings.actionSettings, Strings.actionSettingsSystem)

        onNodeWithTag(tagFor(BACK_ROW)).performClick()

        onNodeWithTag(tagFor(Strings.actionSettingsSurvey)).assertIsDisplayed()
    }

    private companion object {
        val TOP = listOf(
            Strings.actionFile,
            Strings.actionView,
            Strings.actionDevice,
            Strings.actionInput,
            Strings.actionTools,
            Strings.actionSettings,
            Strings.actionHelp,
            Strings.actionConnection,
        )
        val FILE = listOf(
            Strings.actionFileNew,
            Strings.actionFileOpen,
            Strings.actionFileSave,
            Strings.actionFileSaveAs,
            Strings.actionFileDelete,
            Strings.actionFileImport,
            Strings.actionFileExport,
            Strings.actionFileShare,
        )
        val VIEW = listOf(
            Strings.actionTrip,
            Strings.actionTable,
            Strings.actionPlan,
            Strings.actionElevation,
            Strings.action3d,
            Strings.actionStats,
            Strings.actionFullscreen,
        )
        val TOOLS = listOf(
            Strings.actionUndoLastLeg,
            Strings.actionFindStation,
            Strings.actionAddLeg,
            Strings.actionAddSplay,
            Strings.actionSystemLog,
        )
        val SYSTEM_SETTINGS = listOf(
            Strings.settingsGeneralTitle,
            Strings.settingsSketchingTitle,
            Strings.settingsManualDataEntryTitle,
            Strings.settingsInstrumentsTitle,
        )
    }
}
