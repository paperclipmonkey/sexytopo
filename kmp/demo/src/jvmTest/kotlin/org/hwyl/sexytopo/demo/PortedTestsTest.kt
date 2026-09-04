package org.hwyl.sexytopo.demo

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every test the Android app has is either ported here or written down as not being.
 *
 * The README says the Java test suite was ported and quotes the count. The count is checked; the
 * *claim* was not, because the ported tests were renamed to say what they test rather than
 * `testFoo`, and nothing linked an Android test class to the Kotlin one that carries its cases.
 * Thirty-two of the app's fifty-seven test classes were cited nowhere in this module — most of
 * them ported under other names, and four not ported at all, which nobody could have known.
 *
 * So this is the ledger: each Android test class, and the test class here that holds its cases,
 * checked to exist. A class in [notPorted] carries the reason. Renaming a test here fails this,
 * which is the point — the link is worth as much as the count.
 */
class PortedTestsTest {

    private val androidTests = File("../../app/src/test")

    /** Every test class the port holds, by simple name. */
    private val portTests: Set<String> by lazy {
        listOf(File("src"), File("../shared/src"))
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" && f.name.endsWith("Test.kt") }.toList() }
            .map { it.nameWithoutExtension }
            .toSet()
    }

    /** Android test class to the test class or classes here that carry its cases. */
    private val portedTo =
        mapOf(
            "CalibrationCalculatorTest" to listOf("CalibrationAlgorithmTest", "CalibrationRunTest"),
            "CalibrationProtocolTest" to listOf("DistoXProtocolTest"),
            "ConnectedSurveysTest" to listOf("SurveyUpdaterTest"),
            "Coord2DTest" to listOf("CoordTest"),
            "Coord3DTest" to listOf("CoordTest"),
            "CrossSectionDetailTest" to listOf("SketchCrossSectionJsonTest"),
            "CrossSectionerTest" to listOf("CrossSectionTest"),
            "DistoXBleManagerTest" to listOf("DistoXBleTest"),
            "DistoXProtocolTest" to listOf("DistoXProtocolTest"),
            "EditLegFormTest" to listOf("LegEditingTest", "ReadingValidationTest", "AddLegOutrightTest"),
            "ExtendedElevationProjectionTest" to listOf("ExtendedElevationTest"),
            "GeneralPreferencesLicenceTest" to listOf("LicenceTest"),
            "GraphToListTranslatorTest" to listOf("SurveyTableTest"),
            "LegAmalgamatorTest" to listOf("LegAmalgamatorTest"),
            "LegTest" to listOf("SurveyUpdaterTest", "GeometryTest"),
            "LrudTest" to listOf("LrudTest"),
            "MeasurementProtocolTest" to listOf("DistoXProtocolTest"),
            "MetadataTranslaterTest" to listOf("SurveyJsonTest"),
            "NumberToolsTest" to listOf("GeometryTest", "FloatRenderingTest"),
            "PathDetailTest" to listOf("DetailBoundsTest"),
            "PocketTopoFileTest" to listOf("PocketTopoFileTest"),
            "PocketTopoImporterTest" to listOf("PocketTopoImportTest"),
            "PocketTopoTxtImporterTest" to listOf("PocketTopoTxtImportTest"),
            "SexyTopoVersionTest" to listOf("SurvexTherionImportTest"),
            "SketchJsonTranslaterTest" to listOf("SketchCrossSectionJsonTest"),
            "SketchTest" to listOf("SketchEditorTest"),
            "Space2DUtilsTest" to listOf("SketchSimplificationTest", "GeometryTest"),
            "Space3DTransformerForElevationTest" to listOf("ExtendedElevationTest"),
            "Space3DUtilsTest" to listOf("GeometryTest"),
            "StationNamerTest" to listOf("StationNamerTest"),
            "StationRenameTest" to listOf("SurveyUpdaterTest"),
            "StationTest" to listOf("StationNamingTest"),
            "SurveyStatsTest" to listOf("SurveyStatsTest"),
            "SurveyTest" to listOf("SurveyUpdaterTest"),
            "SurveyToolsTest" to listOf("SurveyTreeTest"),
            "SurveyUpdaterInheritedDirectionTest" to listOf("ExtendedElevationTest"),
            "SurveyUpdaterTest" to listOf("SurveyUpdaterTest"),
            "SurvexExporterTest" to listOf("ExportTest"),
            "SurvexImporterTest" to listOf("SurvexTherionImportTest"),
            "SurvexTherionUtilTest" to listOf("Th2ExportTest", "SvgLegendTest"),
            "SurveyFormatTeamTest" to listOf("SurveyImportTest", "ExportTest"),
            "SurveyJsonTranslaterTest" to listOf("SurveyJsonTest", "SurveyLoaderFidelityTest"),
            "SvgExporterTest" to listOf("SvgExportTest", "SvgLegendTest"),
            "TestSurveyCreatorTest" to listOf("ExampleSurveyTest"),
            "TextDetailTest" to listOf("SketchEditingTest"),
            "TextToolsTest" to listOf("StationNamerTest"),
            "Th2ExporterTest" to listOf("Th2ExportTest"),
            "ThExporterTest" to listOf("ExportTest"),
            "TherionImporterTest" to listOf("SurvexTherionImportTest"),
            "TripTest" to listOf("TripDetailsTest", "ExportTest"),
            "XviExporterTest" to listOf("XviExportTest"),
            "XviImporterTest" to listOf("XviImportTest"),
        )

    /** Android test classes with nothing to port, and why. Each is a sentence, not a shrug. */
    private val notPorted =
        mapOf(
            "CodeStyleTest" to "checks Java files for trailing whitespace; ktlint's job here",
            "FormTest" to "an abstract base with no cases of its own",
            "FrameTest" to "an abstract base with no cases of its own",
            "GuideActivityTest" to "asserts an Android WebView is visible; the manual here is Compose, covered by ManualContentTest and BundledManualTest",
            "OldStyleLoaderTest" to "the pre-JSON tab-separated survey format, which this port does not read — a real gap, recorded in the README",
        )

    @Test
    fun everyAndroidTestClassIsPortedOrAccountedFor() {
        val classes =
            androidTests.walkTopDown()
                .filter { it.name.endsWith("Test.java") || it.name.endsWith("Test.kt") }
                .map { it.nameWithoutExtension }
                .toSortedSet()
        assertTrue(classes.size >= 50, "the Android tests have moved: found ${classes.size} under $androidTests")

        val unaccounted = classes.filterNot { it in portedTo || it in notPorted }
        assertEquals(emptyList(), unaccounted, "the Android app has tests this ledger does not mention")

        val danglingEntries = portedTo.keys.filterNot { it in classes } + notPorted.keys.filterNot { it in classes }
        assertEquals(emptyList(), danglingEntries, "this ledger names Android tests that no longer exist")
    }

    @Test
    fun everyPortedClassNamedHereExists() {
        val missing =
            portedTo.flatMap { (android, ported) -> ported.filterNot { it in portTests }.map { "$android -> $it" } }
        assertEquals(emptyList(), missing, "the ledger points at tests this module does not have")
    }
}
