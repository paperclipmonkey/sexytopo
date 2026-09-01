package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.io.MetadataJson
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * A whole survey as one file, so it can be handed to somebody.
 *
 * `SurveyZipSharer` in the Android app builds exactly this and gives it to the share sheet. This
 * port had the receiving half and not the sending half: [org.hwyl.sexytopo.shared.io.imports] will
 * take a survey somebody unzipped, and there was no way to make the zip. So handing a survey to a
 * caving partner meant exporting the data file and both drawings separately and hoping all three
 * arrived, which is the loss this branch has already fixed twice in other guises.
 *
 * Four files rather than three, the fourth being the metadata file the Android app keeps the
 * active station in. A zip that is missing it still opens over there; it just opens at the
 * entrance of the cave rather than where the surveyor stopped.
 *
 * The entries are the files [SurveyStorage] writes, under the same names, so what comes out of the
 * share sheet is what a survey directory looks like — which is what makes it importable at the
 * other end without anything new being taught to read it.
 */
object SurveyZip {

    /** What the zip is called. `Swildons.zip`, beside `Swildons.data.json` and the rest. */
    fun fileNameFor(survey: Survey): String = "${survey.name}.zip"

    fun archive(
        survey: Survey,
        versionName: String = SurveyStorage.DEFAULT_VERSION_NAME,
        versionCode: Int = 0,
    ): ByteArray = Zip.archive(entries(survey, versionName, versionCode))

    internal fun entries(survey: Survey, versionName: String, versionCode: Int): List<Zip.Entry> {
        val name = survey.name
        return listOf(
            Zip.Entry(
                SurveyFileType.DATA.filenameFor(name),
                SurveyJson.write(survey, versionName, versionCode).encodeToByteArray(),
            ),
            Zip.Entry(
                SurveyFileType.METADATA.filenameFor(name),
                MetadataJson.write(survey, versionName, versionCode).encodeToByteArray(),
            ),
            Zip.Entry(
                SurveyFileType.PLAN_SKETCH.filenameFor(name),
                SketchJson.write(survey.planSketch, name).encodeToByteArray(),
            ),
            Zip.Entry(
                SurveyFileType.EXTENDED_ELEVATION_SKETCH.filenameFor(name),
                SketchJson.write(survey.elevationSketch, name).encodeToByteArray(),
            ),
        )
    }
}
