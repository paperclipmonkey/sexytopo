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
 *
 * ## Photographs
 *
 * Those four files are all a survey used to be. A survey with pictures pinned to its sketches also
 * has JPEGs beside them (see [PhotoStore]), and a zip without those is a zip of pins pointing at
 * nothing, so they go in too, under the same names again.
 *
 * The bytes come from the readPhoto function [archive] takes, rather than from a [FileStore] given
 * to it. Handing this object a store would mean handing it a directory as well — two arguments
 * that have to agree with each other, about a thing this object does not otherwise know or care
 * about. Its job is naming and packing; where a photograph is kept is [PhotoStore]'s. A caller
 * with a store writes
 * `SurveyZip.archive(survey) { PhotoStore.load(store, directory, survey.name, it) }`, one that
 * has the images some other way passes its own function, and a test needs neither.
 */
object SurveyZip {

    /** What the zip is called. `Swildons.zip`, beside `Swildons.data.json` and the rest. */
    fun fileNameFor(survey: Survey): String = "${survey.name}.zip"

    /**
     * No photographs: what a caller that cannot reach the images gets, and what keeps every
     * existing caller of [archive] producing exactly the four entries it always did.
     */
    private val NO_PHOTOS: (String) -> ByteArray? = { null }

    fun archive(
        survey: Survey,
        versionName: String = SurveyStorage.DEFAULT_VERSION_NAME,
        versionCode: Int = 0,
        readPhoto: (photoId: String) -> ByteArray? = NO_PHOTOS,
    ): ByteArray = Zip.archive(entries(survey, versionName, versionCode, readPhoto))

    internal fun entries(
        survey: Survey,
        versionName: String,
        versionCode: Int,
        readPhoto: (photoId: String) -> ByteArray? = NO_PHOTOS,
    ): List<Zip.Entry> {
        val name = survey.name
        val files = listOf(
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

        // The photographs the sketches pin, under the names they have in the survey directory, so
        // unzipping the archive over one puts every picture back where its pin looks for it.
        //
        // Nothing is compressed on the way in, and nothing needs to be: Zip stores rather than
        // deflates, with a real CRC-32, so binary arrives exactly as it left — and a JPEG is
        // already compressed, so deflating one would spend time making it very slightly bigger.
        //
        // An id whose file is missing is skipped rather than written empty. A survey can be handed
        // over as JSON alone, and a zip carrying a zero-byte JPEG would turn "the picture was never
        // here" into "the picture is corrupt".
        val photos =
            PhotoStore.photoIdsIn(survey).mapNotNull { photoId ->
                readPhoto(photoId)?.let { bytes ->
                    Zip.Entry(PhotoStore.fileNameFor(name, photoId), bytes)
                }
            }

        return files + photos
    }
}
