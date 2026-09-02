package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.export.TherionExport
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ten `pref_therion_*` settings, from the box the surveyor types in to the file that comes out.
 *
 * `Th2Exporter.Options` has carried seven of them since the scrap exporter was ported, and every
 * caller passed the defaults. The three that were not represented at all are the ones that decide
 * what the files are *called* — which for a format whose files refer to each other by name is not
 * a detail.
 */
class TherionExportOptionsTest {

    private val survey = ExampleSurvey.create()

    private fun th2(options: TherionExport, projection: Projection2D = Projection2D.PLAN) =
        exportText(
            survey,
            ExportFormat.TH2,
            projection,
            today = "2026-01-01",
            therion = options,
        )

    @Test
    fun theDefaultsChangeNothing() {
        assertEquals(
            fileNameFor(survey, ExportFormat.TH2, Projection2D.PLAN),
            fileNameFor(survey, ExportFormat.TH2, Projection2D.PLAN, TherionExport.DEFAULT),
        )
        assertEquals(
            "${survey.name}.plan.th2",
            fileNameFor(survey, ExportFormat.TH2, Projection2D.PLAN, TherionExport.DEFAULT),
        )
    }

    /**
     * A suffix the surveyor typed reaches the filename, on all three Therion formats: the
     * `.thconfig` names the `.th2`, which names the `.xvi`. A suffix that reached two of the three
     * would produce a project whose files point at a name nothing wrote.
     */
    @Test
    fun aChosenSuffixNamesEveryTherionFileTheSameWay() {
        val options = TherionExport(planSuffix = "P")

        assertEquals(
            "${survey.name}P.th2",
            fileNameFor(survey, ExportFormat.TH2, Projection2D.PLAN, options),
        )
        assertEquals(
            "${survey.name}P.xvi",
            fileNameFor(survey, ExportFormat.XVI, Projection2D.PLAN, options),
        )
        // And the scrap file itself names the image by the same rule.
        assertTrue("${survey.name}P.xvi" in th2(options), "the scrap should name the image it got")

        // The .th is the file that pulls the scraps in. If its input lines named the old suffix,
        // a project built from these files would compile to a centreline and no drawing.
        val th = exportText(survey, ExportFormat.THERION, Projection2D.PLAN, "2026-01-01", therion = options)
        assertTrue(
            "input \"${survey.name}P.th2\"" in th,
            "the .th should pull in the scrap by the name the scrap was actually written under",
        )
    }

    @Test
    fun theDrawingKeepsItsOwnNameWhateverTherionIsSetTo() {
        val options = TherionExport(planSuffix = "P", elevationSuffix = "E")

        assertEquals(
            "${survey.name}.plan.svg",
            fileNameFor(survey, ExportFormat.SVG, Projection2D.PLAN, options),
        )
    }

    /**
     * The image folder reaches the scrap, which is the half xtherion resolves.
     *
     * A `.th2` that names an image which is not where it says opens with a missing-file complaint
     * and no background at all — the same as not exporting it, for a scrap whose whole purpose is
     * to be traced over.
     */
    @Test
    fun theImageFolderReachesTheScrap() {
        val plain = th2(TherionExport.DEFAULT)
        assertTrue("${survey.name}.plan.xvi" in plain, "the default names the image beside it")
        assertFalse("xvi/" in plain, "and does not invent a folder")

        val filed = th2(TherionExport(xviFolder = "xvi"))
        assertTrue("xvi/${survey.name}.plan.xvi" in filed, "a folder should reach the reference")
    }

    @Test
    fun theSwitchesReachTheScrapFile() {
        val withSections = th2(TherionExport.DEFAULT)
        val without = th2(TherionExport(crossSections = false))

        assertTrue(
            withSections.length > without.length,
            "a file with the sections in it should be the longer one",
        )
        assertFalse("PX" in without, "no cross-section scraps means no cross-section names")
    }

    @Test
    fun aChosenScrapSuffixNamesTheScrap() {
        assertTrue(
            "-passage" in th2(TherionExport(planScrapSuffix = "-passage")),
            "the scrap should carry the name it was given",
        )
    }

    @Test
    fun everyTherionOptionSurvivesTheAppBeingClosed() {
        val store = InMemoryFileStore()
        val flipped =
            TherionExport(
                planSuffix = "P",
                elevationSuffix = "E",
                xviFolder = "images",
                planScrapSuffix = "-p",
                elevationScrapSuffix = "-e",
                planCrossSectionSuffix = "X#",
                elevationCrossSectionSuffix = "Y#",
                crossSections = false,
                symbols = false,
                labels = false,
                planScrapCount = 4,
                elevationScrapCount = 2,
                stationsInFirstPlanScrap = false,
                stationsInFirstElevationScrap = false,
            )
        AppPreferencesStore.save(store, AppPreferences(therionExport = flipped))

        assertEquals(flipped, AppPreferencesStore.load(store).therionExport)
    }

    /**
     * And "every" above means every one, checked rather than remembered.
     *
     * The test above is only as good as the list inside it: a setting added to [TherionExport] and
     * not added to `flipped` leaves it at its default, so it round-trips whether or not anybody
     * wrote the key — and the test goes on passing while the setting is quietly forgotten every
     * time the app closes. So: walk the fields and require each to have been moved off its default,
     * via reflection over the data class's backing fields, which only a JVM test can do.
     */
    @Test
    fun theRoundTripAboveActuallyCoversEveryOption() {
        val flipped =
            TherionExport(
                planSuffix = "P",
                elevationSuffix = "E",
                xviFolder = "images",
                planScrapSuffix = "-p",
                elevationScrapSuffix = "-e",
                planCrossSectionSuffix = "X#",
                elevationCrossSectionSuffix = "Y#",
                crossSections = false,
                symbols = false,
                labels = false,
                planScrapCount = 4,
                elevationScrapCount = 2,
                stationsInFirstPlanScrap = false,
                stationsInFirstElevationScrap = false,
            )
        val default = TherionExport.DEFAULT

        val unmoved =
            TherionExport::class.java.declaredFields
                // Instance fields only: the companion holds the two default-suffix constants and
                // DEFAULT itself, which are not settings and are equal to themselves by definition.
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { field ->
                    field.isAccessible = true
                    field.get(flipped) == field.get(default)
                }
                .map { it.name }

        assertEquals(
            emptyList(),
            unmoved,
            "these settings are still at their defaults in the round-trip test above, so it is" +
                " not checking them",
        )
    }

    /**
     * An empty suffix survives being saved, rather than reading back as the default: "no suffix"
     * is a real choice the Android app takes, and a store that reads a written-and-empty value as
     * absent would quietly hand the surveyor `.plan` back every time they closed the app.
     */
    @Test
    fun anEmptySuffixIsAChoiceAndNotAnAbsence() {
        val store = InMemoryFileStore()
        AppPreferencesStore.save(
            store,
            AppPreferences(therionExport = TherionExport(planSuffix = "")),
        )

        assertEquals("", AppPreferencesStore.load(store).therionExport.planSuffix)
    }

    @Test
    fun aPreferencesFileFromBeforeTheseExistedStillLoads() {
        assertEquals(
            TherionExport.DEFAULT,
            AppPreferencesStore.parse("theme=dark\n").therionExport,
        )
    }
}
