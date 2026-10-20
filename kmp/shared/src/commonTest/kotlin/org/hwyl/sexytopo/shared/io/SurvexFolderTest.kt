package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.imports.SurvexFolder
import org.hwyl.sexytopo.shared.io.imports.SurvexFolder.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which `.svx` and `.espec` a folder import reads, ported from `SurvexImporterFolderTest`.
 *
 * The Java's cases drive a `FakeImportChooser` and look at what it was asked; here the question
 * comes back as a [Step], so the cases look at that instead. Upstream's "None" is the absence of a
 * choice rather than a string, since the wording belongs to whoever puts the question.
 */
class SurvexFolderTest {

    @Test
    fun anSvxFileIsSurvex() {
        assertTrue(SurvexFolder.isSvx("cave.svx"))
        assertTrue(!SurvexFolder.isSvx("cave.th"))
    }

    @Test
    fun aFolderWithAnSvxInItCanBeImported() {
        assertTrue(SurvexFolder.canHandle(listOf("cave.svx", "cave.espec")))
        assertTrue(!SurvexFolder.canHandle(listOf("cave.th")))
    }

    @Test
    fun theEspecWithTheSvxsNameIsTheOne() {
        assertEquals(
            "cave.espec",
            SurvexFolder.matchingEspec("cave.svx", listOf("other.espec", "cave.espec")),
        )
        assertNull(SurvexFolder.matchingEspec("cave.svx", listOf("other.espec")))
    }

    @Test
    fun severalSvxFilesAskWhichOne() {
        assertEquals(
            Step.ChooseSvx(listOf("a.svx", "b.svx")),
            SurvexFolder.nextStep(listOf("a.svx", "b.svx")),
        )
    }

    /** Once one is chosen, the question is not asked again. */
    @Test
    fun aChosenSvxIsRead() {
        assertEquals(
            Step.Read("b.svx", null),
            SurvexFolder.nextStep(listOf("a.svx", "b.svx"), chosenSvx = "b.svx"),
        )
    }

    @Test
    fun oneSvxWithItsOwnEspecAsksNothing() {
        assertEquals(
            Step.Read("cave.svx", "cave.espec"),
            SurvexFolder.nextStep(listOf("cave.svx", "cave.espec")),
        )
    }

    @Test
    fun oneSvxWithNoEspecAsksNothing() {
        assertEquals(Step.Read("cave.svx", null), SurvexFolder.nextStep(listOf("cave.svx")))
    }

    /** Other `.espec` files but none of the `.svx`'s name: offered, with none as a choice too. */
    @Test
    fun anUnmatchedEspecIsOfferedRatherThanGuessed() {
        assertEquals(
            Step.ChooseEspec("cave.svx", listOf("other.espec")),
            SurvexFolder.nextStep(listOf("cave.svx", "other.espec")),
        )
    }

    @Test
    fun aFolderWithNoSvxHasNothingToRead() {
        assertNull(SurvexFolder.nextStep(listOf("cave.th")))
    }
}
