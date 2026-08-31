package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
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
