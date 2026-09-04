package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every menu item the Android app offers is offered here, or is written down as not being.
 *
 * This was a one-off sweep, twice: finding 101 read every XML file under `res/menu/` by hand, and finding 106 did
 * it again with a script. Both found things. A sweep that finds things is a test that has not been
 * written yet, so this is the script, kept — it reads the same XML the Android app inflates, and for
 * each visible item asks whether some composable in this port actually *shows* the mirrored string.
 *
 * "Shows", not "mirrors": `Strings.kt` carrying a name is what `AndroidStringsTest` checks, and it
 * was exactly the loophole the earlier sweep fell through — *Restore Autosave* was mirrored and
 * offered nowhere, and a search for the wording found the mirror and called it present.
 *
 * The exceptions are listed with a reason each, because an item this port deliberately does not
 * offer is a fact worth a sentence, and an item it forgot is a bug. The list is the whole
 * difference between the two, and it should only ever get shorter.
 */
class MenuParityTest {

    private val menus = File("../../app/src/main/res/menu")

    /** Resource name to the `Strings` property that mirrors it, read off `Strings.kt`. */
    private val mirrorOf: Map<String, String> by lazy {
        val source = File("src/commonMain/kotlin/org/hwyl/sexytopo/demo/Strings.kt").readText()
        // `substituted(` breaks its arguments over lines, so the name may start a line of its own.
        Regex("""val (\w+) =\s*(?:s|substituted)\(\s*"([^"]+)"""")
            .findAll(source)
            .associate { it.groupValues[2] to it.groupValues[1] }
    }

    /** Every Compose source but the mirror itself, joined, so a property can be looked for. */
    private val composables: String by lazy {
        File("src/commonMain/kotlin/org/hwyl/sexytopo/demo")
            .walkTopDown()
            .filter { it.extension == "kt" && it.name != "Strings.kt" }
            .joinToString("\n") { it.readText() }
    }

    /**
     * What this port knowingly leaves off its menus, by the Android title resource, and why. A
     * reason that stops being true is a line to delete, and the test then insists on the item.
     */
    private val deliberatelyAbsent =
        mapOf(
            // The developer submenu: `pref_developer_mode` gates it, and every item on it is either
            // a debugging aid for a process this port does not have (kill the comms thread, force
            // a crash, trigger an autosave that here happens on every edit) or is offered another
            // way (the test instrument is the simulator under Instrument settings, a generated
            // survey is the demo cave).
            "action_dev" to "the developer submenu",
            "action_set_test_instrument" to "the simulator, under Instrument settings",
            "action_trigger_autosave" to "every edit saves itself here",
            "action_kill_connection" to "there is no comms thread to kill",
            "action_force_crash" to "a debugging aid for a crash reporter this port has none of",
            "action_generate_test_survey" to "the demo cave",
            // Neither a browser tab nor an iOS app has an exit; the platform owns that.
            "action_file_exit" to "the platform owns quitting",
            // Saving is continuous, so there is nothing an autosave holds that the survey does not.
            "action_file_restore_autosave" to "every edit saves itself; there is no separate autosave",
            // A survey folder is an Android Storage Access Framework idea; the import here takes a
            // file, and a folder of files is what the zip import is for.
            "action_file_import_directory" to "import takes a file or a zip of them",
            // Cross-survey links, which the README's deliberate-gaps section covers.
            "menu_links" to "cross-survey links are a deliberate gap",
            "action_link_to_existing_survey" to "cross-survey links are a deliberate gap",
            "menu_unlink_survey" to "cross-survey links are a deliberate gap",
            "sketch_menu_show_connect" to "cross-survey links are a deliberate gap",
            // Hidden in the app too — `android:visible="false"` — and redo has a toolbar button.
            "sketch_menu_redo" to "hidden upstream as well; redo is on the toolbar",
            // Disabled title rows and submenu headings: the port's menus are flat, and the leg and
            // station menus carry their own titles through `legTitle` and `splayTitle`.
            "menu_leg" to "a disabled title row; the leg menu is titled through legTitle",
            "menu_elevation" to "a submenu heading; the direction rows sit on the station menu",
            "menu_navigate" to "a submenu heading; the jump rows sit on the station menu",
            // The table's splay menu and the sketch's are one menu here, with one name for the
            // one action: `menu_upgrade_splay`, which the sketch's menu uses.
            "menu_upgrade_row" to "the same action as menu_upgrade_splay, under that name",
            "splay" to "a disabled title row; the splay menu is titled through splayTitle",
            "menu_jump_to" to "a submenu heading; the jump rows sit on the station menu",
        )

    /** Each `<item>` of a menu file, as its id, its title resource, and whether it is shown. */
    private fun itemsIn(file: File): List<Triple<String, String, Boolean>> =
        Regex("""<item\b(.*?)(?:/>|</item>)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val id = Regex("""android:id="@\+id/(\w+)"""").find(block)?.groupValues?.get(1)
                val title = Regex("""android:title="@string/(\w+)"""").find(block)?.groupValues?.get(1)
                if (id == null || title == null) return@mapNotNull null
                Triple(id, title, !block.contains("android:visible=\"false\""))
            }
            .toList()

    @Test
    fun everyVisibleAndroidMenuItemIsOfferedHereOrAccountedFor() {
        val files = menus.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
        assertTrue(files.size >= 8, "the Android menus have moved: found ${files.size} in $menus")

        val missing = mutableListOf<String>()
        for (file in files) {
            for ((id, title, visible) in itemsIn(file)) {
                if (!visible || title in deliberatelyAbsent) continue
                val property = mirrorOf[title]
                val offered = property != null && Regex("""\bStrings\.$property\b""").containsMatchIn(composables)
                if (!offered) missing.add("${file.name}: $id ($title)")
            }
        }

        assertEquals(
            emptyList(),
            missing,
            "the Android app offers these and this port does not: offer them, or add them to " +
                "deliberatelyAbsent with the reason",
        )
    }

    /** An exception whose item no longer exists upstream is a stale reason, and should go. */
    @Test
    fun everyExceptionStillNamesARealAndroidItem() {
        val titles =
            menus.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
                itemsIn(file).map { it.second }
            }.toSet()
        val stale = deliberatelyAbsent.keys.filterNot { it in titles }
        assertEquals(emptyList(), stale, "these exceptions name items the Android app no longer has")
    }
}
