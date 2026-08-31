package org.hwyl.sexytopo.demo

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS asset catalogue, checked as far as it can be without a Mac.
 *
 * It was authored on Linux and had never been near `actool`, which is the only thing that can
 * really approve it — CI now runs `actool` by building the Xcode project on the macOS runner, and
 * that is the real check. This is the part that can fail *here*, in a second, rather than six
 * minutes into a build on somebody else's machine.
 *
 * Every rule below has teeth. An icon with an alpha channel is rejected by `actool` outright; one
 * that is not 1024x1024 is rejected; and a `Contents.json` missing `size` or `idiom` compiles
 * quietly to a catalogue with no icon in it, which is a blank white tile on the home screen and no
 * error anywhere.
 *
 * These are string checks rather than parsed JSON on purpose: the files are a dozen lines each and
 * hand-written, and a test that needs a JSON library to read four constants is a test with a
 * dependency it did not need.
 */
class IosAssetsTest {

    /** Tests run with the module directory as their working directory. */
    private val iosApp = File("../iosApp")
    private val catalogue = File(iosApp, "iosApp/Assets.xcassets")

    private fun read(path: String) = File(catalogue, path).readText()

    @Test
    fun theCatalogueIsWhereTheProjectSaysItIs() {
        // `project.yml` names `iosApp` as the target's source path, so the catalogue has to be
        // inside it; one outside is never compiled and produces no error saying so.
        assertTrue(catalogue.isDirectory, "no asset catalogue at ${catalogue.absolutePath}")
        assertTrue(File(catalogue, "Contents.json").isFile, "a catalogue needs a root Contents.json")
    }

    @Test
    fun theIconIsOneActoolWillAccept() {
        val icon = ImageIO.read(File(catalogue, "AppIcon.appiconset/icon-1024.png"))

        assertEquals(1024, icon.width, "the single-size app icon must be 1024 wide")
        assertEquals(1024, icon.height, "and 1024 tall")
        // The rule that catches people out: an iOS app icon may not be transparent, and one with
        // an alpha channel is rejected by `actool` and again by App Store validation. This icon is
        // drawn on an opaque ground for that reason, and this is what checks it stayed that way.
        assertFalse(icon.colorModel.hasAlpha(), "an iOS app icon may not have an alpha channel")
    }

    @Test
    fun theIconSetDeclaresTheOneSizeXcodeWants() {
        // Xcode 14 and later take a single 1024x1024 universal image and derive the rest. The old
        // twenty-entry catalogue still works, but an entry it lists and does not supply is an
        // error, and this port has no way to preview one.
        val contents = read("AppIcon.appiconset/Contents.json")

        assertEquals(1, Regex("\"filename\"").findAll(contents).count(), "one image, not several")
        for (required in listOf("\"icon-1024.png\"", "\"universal\"", "\"1024x1024\"", "\"ios\"")) {
            assertTrue(contents.contains(required), "the icon set does not declare $required")
        }
    }

    /**
     * The launch screen is a colour named from `Info.plist`, and the two have to agree.
     *
     * `UILaunchScreen`'s `UIColorName` is resolved at launch by name. Get it wrong and there is no
     * error anywhere: the launch screen is simply white, which is exactly what it would have been
     * without the key at all.
     */
    @Test
    fun theLaunchColourIsTheAppsOwnGreenAndThePlistNamesIt() {
        val colour = read("LaunchBackground.colorset/Contents.json")

        // 0x7F / 0xAF / 0x7F is `panelBackground` from the app's own colors.xml.
        assertTrue(colour.contains("\"red\" : \"0x7F\"") || colour.contains("\"red\": \"0x7F\""))
        assertTrue(colour.contains("0xAF"), "the green component is not the app's panel green")
        assertTrue(colour.contains("\"srgb\""), "a colour set with no colour space is not portable")

        val plist = File(iosApp, "iosApp/Info.plist").readText()
        assertTrue(
            plist.contains("<string>LaunchBackground</string>"),
            "Info.plist does not name the colour set, so the launch screen would be white",
        )
    }

    @Test
    fun theProjectNamesTheIconSet() {
        // Without ASSETCATALOG_COMPILER_APPICON_NAME the catalogue still compiles and the app
        // still ships — with no icon. A blank tile on the home screen, and nothing said.
        assertTrue(
            File(iosApp, "project.yml").readText()
                .contains("ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon"),
        )
    }

    /**
     * The two `Info.plist` keys that are the difference between a working app and a crash.
     *
     * `NSBluetoothAlwaysUsageDescription` is required from iOS 13: without it, *constructing* a
     * `CBCentralManager` raises and the app dies the first time somebody taps connect. And the
     * file-sharing pair is what puts the survey folder in the Files app, which is the only way a
     * caver gets their data off an iPhone.
     */
    @Test
    fun thePlistCarriesTheKeysThatWouldOtherwiseCrashOrStrand() {
        val plist = File(iosApp, "iosApp/Info.plist").readText()

        assertTrue(plist.contains("NSBluetoothAlwaysUsageDescription"), "connect would crash")
        assertTrue(plist.contains("UIFileSharingEnabled"), "surveys would be unreachable")
        assertTrue(plist.contains("LSSupportsOpeningDocumentsInPlace"))
    }
}
