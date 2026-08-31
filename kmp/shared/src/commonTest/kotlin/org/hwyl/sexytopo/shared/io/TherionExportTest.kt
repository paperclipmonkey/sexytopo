package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a Therion export is called, which is what decides whether the project builds.
 *
 * The naming rule is `TherionExporter.buildExtension` and `SurveyFile.withExtension` together — one
 * rule written across two files, carried between them by a `"|"` marker prepended to a string and
 * stripped three files away. The marker is an implementation detail; the answers are not, and a
 * surveyor who has typed `P` rather than `.plan` into that box has a project that either resolves
 * or does not.
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
     * the plan with the elevation — the same loss the port already had once, when both projections
     * were exported under one name before the suffixes were noticed at all. The Android app has
     * the same hole; it is recorded here so that the next person to touch this knows the check
     * describes upstream's behaviour and not a bug introduced here.
     */
    @Test
    fun emptyingBothSuffixesGivesTwoDrawingsOneName() {
        val options = TherionExport(planSuffix = "", elevationSuffix = "")

        assertEquals(
            options.fileNameFor("Name", Projection2D.PLAN, "th2"),
            options.fileNameFor("Name", Projection2D.EXTENDED_ELEVATION, "th2"),
        )
    }

    /** With no folder set, the scrap names the image beside it. */
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
