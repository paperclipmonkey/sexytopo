package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a Therion export is called, which is what decides whether the project builds.
 *
 * The naming rule is `TherionExporter.buildExtension` and `SurveyFile.withExtension` together, one
 * rule written across two files and carried between them by a `"|"` marker prepended to a string
 * and stripped three files away.
 */
class TherionExportTest {

    @Test
    fun theDefaultNamesAreTheOnesEverythingElseAlreadyExpects() {
        val options = TherionExport.DEFAULT

        assertEquals("Swildons.plan.th2", options.fileNameFor("Swildons", Projection2D.PLAN, "th2"))
        assertEquals(
            "Swildons.ee.xvi",
            options.fileNameFor("Swildons", Projection2D.EXTENDED_ELEVATION, "xvi"),
        )
    }

    /** All four cases of the rule, which is three different answers about one dot. */
    @Test
    fun aSuffixIsJoinedOnTheWayTheAndroidAppJoinsIt() {
        assertEquals("Name.th2", TherionExport.fileName("Name", "", "th2"))
        assertEquals("Name.plan.th2", TherionExport.fileName("Name", ".plan", "th2"))
        assertEquals("NameP.th2", TherionExport.fileName("Name", "P.", "th2"))
        assertEquals("NameP.th2", TherionExport.fileName("Name", "P", "th2"))
    }

    /**
     * A surveyor who empties both boxes gets one name for two drawings.
     *
     * Worth asserting rather than assuming, because it is the case where this app would overwrite
     * the plan with the elevation. The Android app has the same hole; recorded here so the next
     * person to touch this knows the check describes upstream's behaviour, not a bug introduced here.
     */
    @Test
    fun emptyingBothSuffixesGivesTwoDrawingsOneName() {
        val options = TherionExport(planSuffix = "", elevationSuffix = "")

        assertEquals(
            options.fileNameFor("Name", Projection2D.PLAN, "th2"),
            options.fileNameFor("Name", Projection2D.EXTENDED_ELEVATION, "th2"),
        )
    }

    @Test
    fun theImageIsNamedBesideTheScrapUnlessAFolderIsGiven() {
        assertEquals(
            "Swildons.plan.xvi",
            TherionExport.DEFAULT.xviReference("Swildons", Projection2D.PLAN),
        )
    }

    /**
     * And with one, the reference carries it — which is the half that makes xtherion find it.
     *
     * Slashes are trimmed off both ends rather than being taken literally: somebody typing a
     * folder into a text box types `xvi/` or `/xvi` about as often as `xvi`, and `xvi//Name.xvi`
     * is not a path Therion resolves.
     */
    @Test
    fun aFolderReachesTheReferenceHoweverItWasTyped() {
        for (typed in listOf("xvi", "xvi/", "/xvi", "/xvi/")) {
            assertEquals(
                "xvi/Swildons.plan.xvi",
                TherionExport(xviFolder = typed).xviReference("Swildons", Projection2D.PLAN),
                "a folder typed as \"$typed\" should resolve the same way",
            )
        }
    }

    /** The seven the scrap exporter already took are handed over unchanged. */
    @Test
    fun theScrapOptionsAreCarriedThroughRatherThanRestated() {
        val options =
            TherionExport(
                planScrapSuffix = "-p",
                elevationScrapSuffix = "-e",
                planCrossSectionSuffix = "X#",
                elevationCrossSectionSuffix = "Y#",
                crossSections = false,
                symbols = false,
                labels = false,
            )

        val th2 = options.th2Options(xviFileName = "Name.plan.xvi")

        assertEquals("-p", th2.planScrapSuffix)
        assertEquals("-e", th2.elevationScrapSuffix)
        assertEquals("X#", th2.planCrossSectionSuffix)
        assertEquals("Y#", th2.elevationCrossSectionSuffix)
        assertEquals(false, th2.crossSections)
        assertEquals(false, th2.symbols)
        assertEquals(false, th2.labels)
        assertEquals("Name.plan.xvi", th2.xviFileName)
    }
}
