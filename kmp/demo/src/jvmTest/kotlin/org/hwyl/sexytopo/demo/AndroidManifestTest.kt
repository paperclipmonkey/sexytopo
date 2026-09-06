package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the Android app ask for everything its own code needs?
 *
 * A permission is the quietest thing to get wrong on Android. The feature compiles, the switch
 * appears in the settings, the call is made, and the platform simply does not do it — no error, no
 * log a surveyor will ever see. That is exactly how the buzz on a new station arrived here: the
 * `Vibrator` was asked for two hundred milliseconds, `hasVibrator` answered yes so the switch was
 * offered, the preference defaulted to on, and this manifest had never declared `VIBRATE`.
 *
 * So rather than a list of permissions somebody has to remember to update, this reads the Android
 * source and asks what it *does*. A call that needs telling Android first has to have told it.
 *
 * Like `ReadmeReferencesTest`, the files it reads are not declared inputs of the test task, so an
 * edit to the manifest alone leaves `:demo:jvmTest` up to date and this does not re-run. It runs on
 * a clean checkout and so in CI; locally, `--rerun-tasks` is the way to ask it again.
 */
class AndroidManifestTest {

    /** Tests run with the module directory as their working directory. */
    private val manifest = File("../androidApp/src/main/AndroidManifest.xml").readText()

    /** Every line of Android-only Kotlin in the app, from both modules that hold any. */
    private val androidSource: String =
        listOf(File("src/androidMain"), File("../shared/src/androidMain"))
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .joinToString("\n") { it.readText() }

    /** What the platform will not do unasked, and the call that gives the game away. */
    private val whatNeedsAsking =
        listOf(
            Triple("VIBRATE", Regex("""\.vibrate\("""), "buzz to say a station was made"),
            Triple("BLUETOOTH_SCAN", Regex("""\.startScan\("""), "look for an instrument"),
            Triple("BLUETOOTH_CONNECT", Regex("""\.connectGatt\("""), "connect to an instrument"),
            Triple(
                "ACCESS_FINE_LOCATION",
                Regex("""\.requestLocationUpdates\("""),
                "take a position at the entrance",
            ),
        )

    @Test
    fun everythingTheAndroidCodeDoesIsDeclaredInTheManifest() {
        assertTrue(androidSource.isNotEmpty(), "found no Android sources to read, so this proves nothing")

        val undeclared =
            whatNeedsAsking
                .filter { (_, call, _) -> call.containsMatchIn(androidSource) }
                .filterNot { (permission, _, _) ->
                    "android.permission.$permission" in manifest
                }
                .map { (permission, _, what) -> "$permission, to $what" }

        assertTrue(
            undeclared.isEmpty(),
            "the app does these and never asks Android for them: ${undeclared.joinToString("; ")}",
        )
    }

    /**
     * The scan permission carries `neverForLocation`, which is worth a test of its own because
     * dropping it is invisible: everything still works, and Android 12 and later quietly start
     * demanding a location permission as well. A caver asked for their location by a cave survey
     * app is a caver who says no, and then cannot connect to their instrument.
     *
     * This used to assert that the app declared no location permission at all, on the grounds that
     * it had no business knowing where the surveyor was. It has business now — one station of a
     * survey has to be fixed to the world for the survey to be worth anything on a map — and the
     * two claims were never the same claim. `neverForLocation` is about what *scanning* implies,
     * and it holds whether or not something else in the app asks for a location for its own
     * reasons. The half that had to go is asserted the other way round below.
     */
    @Test
    fun scanningDoesNotDragInALocationPermission() {
        assertTrue(
            "neverForLocation" in manifest,
            "BLUETOOTH_SCAN without neverForLocation makes Android ask for the surveyor's location",
        )
        assertTrue(
            "ACCESS_COARSE_LOCATION" !in manifest,
            "coarse location is accurate to a few kilometres, which is no use for an entrance " +
                "and is one more thing to refuse",
        )
    }

    /**
     * The location permission belongs to one screen, and nothing else may quietly start using it.
     *
     * A permission that exists gets used, and each new use is a place a surveyor's location can go
     * somewhere they did not expect. There is exactly one reason for this app to know where the
     * phone is — the position of the station a surveyor is deliberately standing on — and this
     * says so in a way that a second use would break.
     *
     * By file rather than by call, because the ways to ask are many and the files that should be
     * asking are one.
     */
    @Test
    fun onlyThePositionScreenAsksWhereThePhoneIs() {
        val asking =
            listOf(File("src/androidMain"), File("../shared/src/androidMain"))
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
                .filter { "ACCESS_FINE_LOCATION" in it.readText() }
                .map { it.name }

        assertEquals(
            listOf("Position.android.kt"),
            asking,
            "something other than the position screen has started asking where the phone is",
        )
    }
}
