package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop build keeping its surveys.
 *
 * Worth testing rather than trying: the desktop app is the quickest way to see the whole thing on a
 * Mac without Xcode, and the failure mode — a survey that is there until the window closes — is one
 * nobody notices until they have lost something.
 */
class DesktopStorageTest {

    @Test
    fun macOsPutsFilesWhereTheFinderShowsThem() {
        val root = desktopStorageRoot(osName = "Mac OS X", home = "/Users/caver")
        assertEquals("/Users/caver/Library/Application Support/SexyTopo KMP", root.path)
    }

    @Test
    fun windowsFollowsAppData() {
        assertEquals(
            File("C:\\Users\\caver\\AppData\\Roaming", "SexyTopo KMP").path,
            desktopStorageRoot(
                osName = "Windows 11",
                home = "C:\\Users\\caver",
                appData = "C:\\Users\\caver\\AppData\\Roaming",
            ).path,
        )
        // And falls back to where APPDATA usually points when it is not set.
        assertTrue(
            desktopStorageRoot(osName = "Windows 11", home = "C:/Users/caver", appData = null)
                .path
                .contains("Roaming"),
        )
    }

    @Test
    fun linuxFollowsTheXdgSpecification() {
        assertEquals(
            "/home/caver/.local/share/sexytopo-kmp",
            desktopStorageRoot(osName = "Linux", home = "/home/caver", xdgDataHome = null).path,
        )
        assertEquals(
            "/data/xdg/sexytopo-kmp",
            desktopStorageRoot(osName = "Linux", home = "/home/caver", xdgDataHome = "/data/xdg").path,
        )
    }

    /** An unset environment variable arrives as an empty string as often as it does as null. */
    @Test
    fun anEmptyEnvironmentVariableIsNotADirectory() {
        assertEquals(
            "/home/caver/.local/share/sexytopo-kmp",
            desktopStorageRoot(osName = "Linux", home = "/home/caver", xdgDataHome = "  ").path,
        )
    }

    /**
     * The whole point: a survey written by one run of the app is there for the next one. Over a
     * temporary directory rather than the real storage root, so running the tests does not write
     * into the developer's own library.
     */
    @Test
    fun aSurveyWrittenToDiskIsThereWhenTheAppOpensAgain() {
        val root = createTempDirectory()
        try {
            val survey =
                Survey("Swildons").also {
                    SurveyBuilder.updateWithNewStation(it, Leg(5.42f, 12.5f, -3f))
                    it.origin.comment = "entrance"
                }

            val first = SurveyLibrary(directoryStoreAt(root))
            assertTrue(first.save(survey))

            // A second library over the same directory is what the app does after a restart.
            val second = SurveyLibrary(directoryStoreAt(root))
            assertEquals(listOf("Swildons"), second.list())
            val reopened = assertNotNull(second.open("Swildons"))
            assertEquals(1, reopened.getAllLegsInChronoOrder().size)
            assertEquals("entrance", reopened.origin.comment)

            // And the files on disk are SexyTopo's own layout, not something only this app can read.
            assertTrue(File(root, "surveys/Swildons/Swildons.data.json").isFile)
            assertTrue(File(root, "surveys/Swildons/Swildons.plan.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aBinaryFileSurvivesTheDiskToo() {
        val root = createTempDirectory()
        try {
            val store = directoryStoreAt(root)
            val bytes = ByteArray(256) { it.toByte() }
            File(root, "Ceiled Up.top").writeBytes(bytes)

            assertTrue(bytes.contentEquals(assertNotNull(store.readBytes(listOf("Ceiled Up.top")))))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File =
        File.createTempFile("sexytopo-desktop", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    /** The same store [platformFileStore] builds, over a directory of the test's choosing. */
    private fun directoryStoreAt(root: File) = DirectoryFileStore(root)
}
