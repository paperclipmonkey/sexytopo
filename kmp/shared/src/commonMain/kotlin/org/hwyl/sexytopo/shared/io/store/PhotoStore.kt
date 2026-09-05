package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Where a survey's photographs live: beside its other files, flat, one JPEG each.
 *
 * A survey called Swildons with two pictures pinned to its sketches holds `Swildons.photo-1.jpg`
 * and `Swildons.photo-2.jpg` next to `Swildons.data.json`, named the same way [SurveyFileType]
 * names everything else. Flat and beside rather than in a `photos/` subdirectory because that is
 * how a survey directory already works, and it means copying, zipping or handing over the folder
 * brings the pictures along without anything having to know they are there.
 *
 * The sketch holds only the id — see `PhotoDetail` — so the JSON stays text however many
 * photographs hang off it, and the image is fetched by name when something wants to draw it.
 *
 * There are no `.autosave` partners, unlike the four survey files: the bytes of a photograph never
 * change once written, so there is nothing an autosave could hold that the file does not.
 */
object PhotoStore {

    private const val PHOTO_PREFIX = "photo-"

    /**
     * JPEG, which is what every phone camera hands over, and what a caver receiving the zip can
     * open without being told anything.
     */
    private const val PHOTO_EXTENSION = "jpg"

    fun fileNameFor(surveyName: String, photoId: String): String =
        SurveyFileType.withExtension(surveyName, "$PHOTO_PREFIX$photoId.$PHOTO_EXTENSION")

    /**
     * The id to give the next photograph: one past the highest already used by either sketch.
     *
     * Highest-plus-one rather than count-plus-one, because a count reuses an id the moment a
     * photograph is deleted and would then have two pins pointing at one picture. It is still not
     * a permanent register: delete the newest photograph and the next one takes its id back,
     * writing over the file the deleted pin left behind. That is the right outcome for the file,
     * since nothing refers to it any more — but it does mean undoing that deletion afterwards
     * brings the pin back pointing at the new picture.
     */
    fun nextPhotoId(survey: Survey): String {
        // Anything not a decimal number is ignored rather than crashing: a survey folder is a
        // user's own directory and can be edited by hand.
        val highest = photoIdsIn(survey).mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
        return (highest + 1).toString()
    }

    /**
     * Writes the picture. [path] is the survey's own directory, as [SurveyStorage.save] takes it.
     *
     * On the web this is the call that can throw: `localStorage` holds a few megabytes for the
     * whole origin and a photograph is a good fraction of that, so a caller that means to keep
     * going must be ready for it.
     */
    fun save(
        store: FileStore,
        path: List<String>,
        surveyName: String,
        photoId: String,
        bytes: ByteArray,
    ) {
        store.writeBytes(path + fileNameFor(surveyName, photoId), bytes)
    }

    /**
     * The picture back, or null if it is not there.
     *
     * Missing is ordinary rather than exceptional: a survey can arrive as a `.data.json` and a
     * sketch someone sent on their own without the images, and a pin whose picture is absent should
     * still draw as a pin.
     */
    fun load(
        store: FileStore,
        path: List<String>,
        surveyName: String,
        photoId: String,
    ): ByteArray? = store.readBytes(path + fileNameFor(surveyName, photoId))

    /**
     * Every photo id the survey refers to, plan sketch first, in the order the pins were made,
     * with duplicates dropped — the same picture can be pinned twice, and is one file either way.
     */
    fun photoIdsIn(survey: Survey): List<String> =
        (photoIdsIn(survey.planSketch) + photoIdsIn(survey.elevationSketch)).distinct()

    /**
     * Every photograph a sketch names, its cross-sections' own drawings included.
     *
     * The nested half is the part that is easy to leave out, and it was: this walked the two
     * top-level sketches only, while [SketchJson] writes photographs into a cross-section's
     * sub-sketch and reads them back again. Two things go wrong when the two disagree, and both
     * lose a photograph rather than merely mislaying one. [nextPhotoId] would hand back an id a
     * nested pin already held, and [save] would then write over that picture; and `SurveyZip` packs
     * what this returns, so a nested photograph would be left out of the archive and arrive at the
     * other end as a pin with nothing behind it.
     *
     * Nothing in this port puts a pin inside a cross-section yet — `SketchEditor` only ever writes
     * to the top-level sketch — but the reader will happily load a file that has one, and a survey
     * is a file a person can be handed. Recursive rather than one level deep for the same reason:
     * what the format allows is what has to be counted, not what this app happens to write.
     */
    private fun photoIdsIn(sketch: Sketch): List<String> =
        sketch.photoDetails.map { it.photoId } +
            sketch.crossSectionDetails.flatMap { photoIdsIn(it.sketch) }
}
