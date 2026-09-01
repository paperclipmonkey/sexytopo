package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the README point at things that exist?
 *
 * It is the document this whole branch asks people to read, and most of it is claims about *other*
 * files: this class maps to that one, this behaviour comes from that XML. A path that has rotted is
 * a small lie that costs a reviewer real time — they grep, find nothing, and start wondering what
 * else is stale.
 *
 * Written after a one-off scan found two: an instruction to delete `iosApp/Assets.xcassets`, which
 * is a directory that does not exist at that path, and four citations of
 * `action_jump_to_station_in_table`, which conflates the menu item's id (`action_jump_to_table`)
 * with the string resource's name. Both are the kind of thing that is invisible until somebody
 * tries to follow it.
 *
 * ## One thing to know about running it
 *
 * The files it reads are not declared inputs of the test task, so editing the README alone leaves
 * `:demo:jvmTest` **up to date** and this does not re-run. That is how it first appeared to pass
 * against a reference deliberately broken to check it could fail. It runs on a clean checkout and
 * so in CI, which is where it matters; locally, `--rerun-tasks` is the way to ask it again.
 * Declaring the inputs would fix that and would also make every README edit recompile the module,
 * which is a worse trade for a document that changes far more often than the code it describes.
 */
class ReadmeReferencesTest {

    private val kmp = File("..")
    private val readme = File(kmp, "README.md").readText()

    /** Where the README abbreviates a source path, and what it abbreviates. */
    private val prefixes =
        listOf(
            "shared/" to "shared/src/commonMain/kotlin/org/hwyl/sexytopo/shared/",
            "demo/" to "demo/src/commonMain/kotlin/org/hwyl/sexytopo/demo/",
        )

    private fun resolves(path: String): Boolean {
        // `demo/.../Foo.kt` is the README's shorthand for "somewhere under demo", and
        // `shared/iosMain/.../Foo.kt` names the source set on the way past — so the module is the
        // *first* segment, not everything before the ellipsis. Getting that wrong is what this
        // test reported first, against a file that was there all along.
        if ("/.../" in path) {
            val module = File(kmp, path.substringBefore("/"))
            val tail = path.substringAfterLast("/.../")
            return module.walkTopDown().any { it.name == tail }
        }
        if (File(kmp, path).exists()) return true
        return prefixes.any { (from, to) ->
            path.startsWith(from) && File(kmp, to + path.removePrefix(from)).exists()
        }
    }

    /** Every `@Test` under a source directory, counted the way a reader would count them. */
    private fun testsUnder(vararg paths: String): Int =
        paths.sumOf { path ->
            File(kmp, path)
                .walkTopDown()
                .filter { it.extension == "kt" }
                .sumOf { file -> Regex("@Test\\b").findAll(file.readText()).count() }
        }

    /**
     * The numbers the README quotes are the numbers the repository holds.
     *
     * Every one of these has been wrong at least once, and one of them was wrong in *both*
     * directions on the same day — the headline said 772 while the state-of-it paragraph said 766,
     * neither of which was the count. A number in a document about testing is exactly the claim a
     * reader takes on trust, and it goes stale on the commit that adds a test rather than on the
     * commit that touches the paragraph, which is why nobody notices.
     *
     * The counts are derived rather than asserted against a constant, so this fails with the right
     * number in the message and the fix is to paste it in.
     */
    @Test
    fun theCountsTheReadmeQuotesAreTheOnesTheRepositoryHolds() {
        val shared = testsUnder("shared/src/commonTest")
        val sharedJvm = testsUnder("shared/src/jvmTest")
        val demo = testsUnder("demo/src/jvmTest")
        val ios = testsUnder("demo/src/iosTest")
        // A browser check is a `pass(...)`: the harness prints one line per check and counts them
        // itself, and the two agree — 106 static calls, 106 lines in the log.
        val browser =
            Regex("\\bpass\\(")
                .findAll(File(kmp, "demo/browser-test/field.mjs").readText())
                .count()
        val iosFiles =
            File(kmp, "demo/src/iosMain/kotlin/org/hwyl/sexytopo/demo")
                .listFiles()
                .orEmpty()
                .count { it.extension == "kt" }

        val wanted =
            listOf(
                "covered by $shared shared tests" to shared,
                "green in CI: $shared" to shared,
                "$sharedJvm more against `java.util.zip` on the JVM" to sharedJvm,
                "$demo over the UI's own" to demo,
                "$ios running the iOS half in a simulator" to ios,
                "$browser browser checks driving the real page" to browser,
                "`demo/src/iosMain/` holds ${inWords(iosFiles)}" to iosFiles,
            )

        val missing = wanted.filterNot { (phrase, _) -> phrase in readme }.map { it.first }

        assertEquals(
            emptyList(),
            missing,
            "the README does not say these, so one of its counts has drifted from the repository",
        )
    }

    /** The README spells small counts out, so this has to as well to find them. */
    private fun inWords(n: Int): String =
        listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty",
        ).getOrElse(n) { n.toString() }

    @Test
    fun everyPathTheReadmeNamesIsThere() {
        val paths =
            Regex("`((?:shared|demo|androidApp|iosApp|gradle)/[A-Za-z0-9_./-]+)`")
                .findAll(readme)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(paths.size > 40, "the README stopped naming files, which is unlikely")

        val missing = paths.filterNot(::resolves)
        assertTrue(missing.isEmpty(), "the README points at ${missing.size} things that are gone: $missing")
    }

    /**
     * Every test the README cites as evidence has to exist.
     *
     * This document's central claim is a table of things marked **Verified**, and most rows back
     * that word with the name of a test. A cited test that does not exist — renamed, deleted, or
     * never written — turns the strongest sentence in the file into the weakest kind of assertion,
     * and it is invisible: nothing else here reads the README, and nothing in the README reads the
     * tests.
     *
     * Names only, not what they assert. That a test called `ManualContentTest` exists says nothing
     * about whether it checks the manual, and no regex will. What it does catch is the citation
     * that has quietly stopped pointing at anything, which is the failure that actually happens.
     *
     * Both trees, because the README cites two kinds: this port's own tests, and the Android app's
     * — whose fixtures several of these claims are measured against. The first version of this
     * looked only in `kmp/` and reported `PocketTopoImporterTest` as missing when it is a real
     * Java test the port's goldens are taken from, which is the whole reason it is credited.
     */
    @Test
    fun everyTestItCitesAsEvidenceExists() {
        val cited =
            Regex("`([A-Z][A-Za-z0-9]*Test)`")
                .findAll(readme)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(cited.size >= 8, "the README stopped citing tests by name, which is unlikely")

        val trees = listOf(File(kmp, "."), File(kmp, "../app/src")).filter { it.isDirectory }
        val declared =
            trees
                .asSequence()
                .flatMap { it.walkTopDown() }
                .filter {
                    it.isFile &&
                        (it.extension == "kt" || it.extension == "java") &&
                        "/build/" !in it.path
                }
                .flatMap { file ->
                    Regex("class ([A-Za-z0-9]+)").findAll(file.readText()).map { it.groupValues[1] }
                }
                .toSet()

        val absent = cited.filterNot { it in declared }
        assertTrue(
            absent.isEmpty(),
            "the README cites ${absent.size} tests that do not exist: $absent",
        )
    }
    /**
     * The three Android context menus this README calls dead are still dead.
     *
     * The claim is load-bearing: it is the answer to a reviewer who greps `res/menu/` and asks why
     * this port has no *Move to Different Station*. The answer is that neither has the Android app
     * - the item lives in `table_splay_selected.xml` and `table_full_leg_selected.xml`, and nothing
     * inflates either file. That is a fact about somebody else's tree, which is exactly the kind
     * that goes stale without anyone here noticing, so it is asserted rather than left in prose.
     *
     * Two ways of being dead are checked, because either alone can mislead: the file is never
     * passed to `inflate`, and not one of its item ids is ever looked up. If upstream wires them
     * back up, this fails and the paragraph in the README gets revisited - which is the point.
     */
    @Test
    fun theTableContextMenusTheReadmeCallsDeadStillAre() {
        val menus = File(kmp, "../app/src/main/res/menu")
        if (!menus.isDirectory) return // A checkout of `kmp/` alone; nothing to check against.

        val java =
            File(kmp, "../app/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .joinToString("\n") { it.readText() }

        val dead =
            listOf("table_full_leg_selected", "table_splay_selected", "table_station_selected")
        for (name in dead) {
            val file = File(menus, "$name.xml")
            assertTrue(file.isFile, "$name.xml is gone, so the README's paragraph about it is stale")
            assertTrue(
                "R.menu.$name" !in java,
                "$name.xml is inflated somewhere now; the README says nothing opens it",
            )
            val ids =
                Regex("android:id=\"@\\+id/([A-Za-z0-9_]+)\"")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .toList()
            assertTrue(ids.isNotEmpty(), "$name.xml has no item ids at all, which is unlikely")
            // Whole word, not substring: `R.id.editLeg` is a prefix of the live
            // `R.id.editLegComment`, and matching loosely reported this file's ids as used when
            // none of them are. The first version of this check failed for exactly that reason.
            val used = ids.filter { Regex("R\\.id\\.$it\\b").containsMatchIn(java) }
            assertTrue(
                used.isEmpty(),
                "$name.xml has ids the app now uses ($used); the README says none of them appear",
            )
        }

        // And the five the README calls live really are, so "five of the eight" stays true.
        val live = listOf("action_bar", "context_leg", "context_station", "cross_section", "drawing")
        val notInflated = live.filterNot { "R.menu.$it" in java }
        assertTrue(
            notInflated.isEmpty(),
            "the README calls these menus live and nothing inflates them: $notInflated",
        )
    }

    /**
     * The Android identifiers it credits — menu ids, preference keys, class names — have to be
     * findable in `app/`, or the mapping table is describing an app nobody has.
     *
     * Only the id-shaped names — `action_*`, `button*`, `pref_*`, `menu_*` — because those are
     * what a reviewer greps for verbatim. Not class names: those are covered by the paths above,
     * and a wider net would catch every Compose and Apple name in the document, which are
     * correctly not in the Android app at all.
     */
    @Test
    fun everyAndroidIdItCreditsExistsInTheApp() {
        val app = File(kmp, "../app/src")
        if (!app.isDirectory) return // the kmp build is meant to stand alone

        val files = app.walkTopDown().filter { it.isFile }.toList()
        val sources =
            files
                .filter { it.extension == "java" || it.extension == "xml" }
                .joinToString("\n") { it.readText() }
        val names = files.map { it.name }.toSet()

        val cited =
            Regex("`((?:action_|button[A-Z]|pref_|sketch_menu_|menu_)[A-Za-z0-9_.]+)`")
                .findAll(readme)
                .map { it.groupValues[1] }
                .toSet()

        // A floor rather than a target: it is here so that a regex which silently stops matching
        // cannot leave this test passing while checking nothing.
        assertTrue(cited.size >= 5, "the README stopped naming Android ids, which is unlikely")

        // `action_bar.xml` is a file and `action_fullscreen` is an id inside one; the regex cannot
        // tell them apart and does not need to, as long as each is looked for in the right place.
        val absent =
            cited.filterNot { if (it.endsWith(".xml")) it in names else it in sources }
        assertTrue(absent.isEmpty(), "the README credits ${absent.size} things the app has not: $absent")
    }
}
