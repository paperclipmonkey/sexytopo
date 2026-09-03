package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.io.MetadataJson
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * A whole survey as one file, so it can be handed to somebody, matching `SurveyZipSharer` in the
 * Android app.
 *
 * Four files rather than three: the fourth is the metadata file the Android app keeps the active
 * station in — a zip missing it still opens there, just at the cave entrance rather than where
 * the surveyor stopped. The entries use the same names [SurveyStorage] writes, so the result is
 * importable without teaching the other end anything new.
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
