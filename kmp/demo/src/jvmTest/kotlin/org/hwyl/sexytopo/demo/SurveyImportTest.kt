package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importing is the other half of exporting, and the half that decides whether a survey can be
 * recovered after a phone dies or continued from somebody else's.
 */
class SurveyImportTest {

    private fun store() = InMemoryFileStore()

    private fun aSurvey(name: String): Survey =
        Survey(name).also {
            SurveyBuilder.updateWithNewStation(it, Leg(5.42f, 12.5f, -3f))
            it.origin.comment = "entrance"
        }

    @Test
    fun onlyFilesAtTheRootAreOffered() {
        val store = store()
        store.writeText(listOf("Swildons.data.json"), "{}")
        store.writeText(listOf("notes.txt"), "not a survey")
        // A folder this app wrote is already in the library; offering it again would duplicate it.
        store.writeText(listOf("surveys", "Eastwater", "Eastwater.data.json"), "{}")

        assertEquals(listOf("Swildons.data.json"), SurveyImport.candidates(store))
    }

    @Test
    fun anImportedSurveyKeepsItsLegs() {
        val store = store()
        store.writeText(listOf("Swildons.data.json"), SurveyJson.write(aSurvey("Swildons")))
        val library = SurveyLibrary(store)

        val imported = SurveyImport.import(library, store, "Swildons.data.json")

        assertNotNull(imported)
        assertEquals("Swildons", imported.name)
        assertEquals(1, imported.getAllLegsInChronoOrder().size)
        assertEquals("entrance", imported.origin.comment)
        assertTrue(library.list().contains("Swildons"))
    }

    /**
     * The case importing exists for: a colleague sends you their copy of a cave you are also
     * surveying. Overwriting yours with theirs would be the worst possible outcome.
     */
    @Test
    fun anImportNeverOverwritesASurveyAlreadyInTheLibrary() {
        val store = store()
        val library = SurveyLibrary(store)
        library.save(aSurvey("Swildons"))
        store.writeText(listOf("Swildons.data.json"), SurveyJson.write(aSurvey("Swildons")))

        val imported = SurveyImport.import(library, store, "Swildons.data.json")

        assertEquals("Swildons 2", assertNotNull(imported).name)
        assertEquals(listOf("Swildons", "Swildons 2"), library.list().sorted())
    }

    @Test
    fun somethingThatIsNotASurveyIsRefusedRatherThanThrown() {
        val store = store()
        store.writeText(listOf("shopping.json"), "[1, 2, 3]")

        assertNull(SurveyImport.import(SurveyLibrary(store), store, "shopping.json"))
    }

    @Test
    fun aMissingFileIsRefusedRatherThanThrown() {
        val store = store()
        assertNull(SurveyImport.import(SurveyLibrary(store), store, "gone.data.json"))
    }

    /** `Swildons.data.json` is a survey called Swildons, not one called "Swildons.data". */
    @Test
    fun theAppsOwnExtensionsAreStrippedFromTheName() {
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.data.json"))
        assertEquals("Swildons", SurveyImport.nameFor("Swildons.data.autosave.json"))
        assertEquals("Eastwater Cavern", SurveyImport.nameFor("Eastwater Cavern.json"))
    }
}
