package org.hwyl.sexytopo.shared.io.store

import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.sketch.CrossSection
import org.hwyl.sexytopo.shared.model.sketch.CrossSectionDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a survey's photographs are kept, what they are called, and what comes back out.
 *
 * Both halves matter to a surveyor rather than to a program. A picture that goes in and comes out
 * altered is a picture of a passage nobody can now read; a picture given an id another pin already
 * uses is a picture written over one taken an hour earlier, in a cave nobody is going back to.
 */
class PhotoStoreTest {

    private val home = listOf("Documents", "Caves")

    private fun directory(name: String = "Swildons") = home + name

    private fun survey(name: String = "Swildons"): Survey =
        Survey(name).also { SurveyBuilder.updateWithNewStation(it, Leg(5f, 90f, 0f)) }

    /** One pin on this drawing, standing for the photograph with that id. */
    private fun Sketch.pin(photoId: String) {
        addPhotoDetail(Coord2D.ORIGIN, photoId = photoId, size = 1f, angle = 0f)
    }

    /**
     * Bytes that are emphatically not text: a JPEG's own opening and closing markers around a run
     * of sequences no UTF-8 decoder will accept.
     *
     * A store that put a photograph through a String on the way in or out would hand these back
     * with every one of them replaced by U+FFFD, which is not a picture any more. The guard in
     * [aPhotographComesBackAsTheExactBytesThatWentIn] checks that this sample really does have that
     * property, so the test cannot quietly stop testing anything.
     */
    private val notTextAtAll = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0xC3.toByte(), 0x28, 0x80.toByte(), 0xFE.toByte(), 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun aPhotographIsNamedAfterItsSurvey() {
        assertEquals("Swildons.photo-1.jpg", PhotoStore.fileNameFor("Swildons", "1"))
        assertEquals("Swildons.photo-12.jpg", PhotoStore.fileNameFor("Swildons", "12"))
    }

    /**
     * Flat and beside the JSON, not tucked into a `photos/` subdirectory.
     *
     * That is what lets a survey be copied, zipped or handed over as a folder without anybody
     * having to be told the pictures are in there too.
     */
    @Test
    fun aPhotographSitsBesideTheSurveysOtherFiles() {
        assertFalse(
            PhotoStore.fileNameFor("Swildons", "1").contains("/"),
            "the name carries a directory in it",
        )

        val store = InMemoryFileStore()
        SurveyStorage.save(store, survey(), directory())
        PhotoStore.save(store, directory(), "Swildons", "1", notTextAtAll)

        assertEquals(
            listOf(
                "Swildons.data.json",
                "Swildons.ext-elevation.json",
                "Swildons.metadata.json",
                "Swildons.photo-1.jpg",
                "Swildons.plan.json",
            ),
            store.list(directory()),
        )
        assertTrue(
            store.list(directory()).none { store.isDirectory(directory() + it) },
            "a subdirectory appeared in the survey folder",
        )
    }

    @Test
    fun theFirstPhotographOfASurveyIsNumberOne() {
        assertEquals("1", PhotoStore.nextPhotoId(survey()))
    }

    /**
     * Highest plus one, not count plus one.
     *
     * The middle photograph here has been deleted, so a count would hand out an id a surviving pin
     * already points at, and saving under it would replace that pin's picture with the new one —
     * two pins, one photograph, and the older of the two gone for good.
     */
    @Test
    fun theNextIdIsOnePastTheHighestRatherThanACount() {
        val survey = survey()
        survey.planSketch.pin("1")
        survey.planSketch.pin("3")

        val next = PhotoStore.nextPhotoId(survey)

        assertEquals("4", next)
        assertFalse(
            PhotoStore.photoIdsIn(survey).contains(next),
            "the next id belongs to a photograph the survey still has",
        )
    }

    /** A survey has two drawings and one set of photographs, so both have to be counted. */
    @Test
    fun theNextIdLooksAtBothSketches() {
        val survey = survey()
        survey.planSketch.pin("1")
        survey.elevationSketch.pin("7")

        assertEquals("8", PhotoStore.nextPhotoId(survey))
    }

    /**
     * A survey folder is somebody's own directory and can be edited by hand, so an id that is not
     * a number is stepped over rather than crashed on.
     */
    @Test
    fun anIdThatIsNotANumberIsIgnoredWhenChoosingTheNext() {
        val survey = survey()
        survey.planSketch.pin("scan-of-the-old-survey")
        survey.planSketch.pin("2")

        assertEquals("3", PhotoStore.nextPhotoId(survey))
    }

    @Test
    fun aPhotographComesBackAsTheExactBytesThatWentIn() {
        assertFalse(
            notTextAtAll.contentEquals(notTextAtAll.decodeToString().encodeToByteArray()),
            "the sample bytes survive a trip through a String, so this test proves nothing",
        )

        val store = InMemoryFileStore()
        PhotoStore.save(store, directory(), "Swildons", "1", notTextAtAll)

        val loaded = PhotoStore.load(store, directory(), "Swildons", "1")

        assertTrue(
            notTextAtAll.contentEquals(loaded),
            "the photograph came back altered: ${loaded?.toList()}",
        )
    }

    /**
     * The ordinary case of a survey handed over without its pictures — sent as JSON, or copied
     * file by file by somebody who did not know the JPEGs went with it.
     *
     * Null rather than a throw because a pin whose picture is absent should still draw as a pin:
     * the surveyor knows they took a photograph there, which is worth more than a crash.
     */
    @Test
    fun aPhotographThatWasNeverWrittenLoadsAsNull() {
        val store = InMemoryFileStore()
        PhotoStore.save(store, directory(), "Swildons", "1", notTextAtAll)

        assertNull(PhotoStore.load(store, directory(), "Swildons", "2"))
    }

    /** Ids are per survey, so one survey's photograph is never fetched for another's pin. */
    @Test
    fun aPhotographIsFoundOnlyUnderItsOwnSurveysName() {
        val store = InMemoryFileStore()
        PhotoStore.save(store, directory(), "Swildons", "1", notTextAtAll)

        assertNull(PhotoStore.load(store, directory("Eastwater"), "Eastwater", "1"))
        assertNull(
            PhotoStore.load(store, directory(), "Eastwater", "1"),
            "the survey name was not used to find the file",
        )
    }

    /**
     * Saving over an id replaces the picture, which is what makes reusing the newest id after a
     * deletion safe to do and unsafe to get wrong.
     */
    @Test
    fun savingUnderAnIdAgainReplacesThePicture() {
        val store = InMemoryFileStore()
        val second = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        PhotoStore.save(store, directory(), "Swildons", "1", notTextAtAll)

        PhotoStore.save(store, directory(), "Swildons", "1", second)

        assertTrue(second.contentEquals(PhotoStore.load(store, directory(), "Swildons", "1")))
        assertEquals(1, store.list(directory()).size, "the replacement was written as a second file")
    }

    /**
     * The list the zip is packed from and the next id is chosen from: both drawings, in the order
     * the pins were made, and one entry for a picture pinned on both.
     *
     * A duplicate would put the same JPEG into the archive twice, which some unzippers take as a
     * damaged file rather than as two copies.
     */
    @Test
    fun everyPhotographTheSurveyRefersToIsListedOnceEach() {
        val survey = survey()
        survey.planSketch.pin("1")
        survey.planSketch.pin("2")
        survey.elevationSketch.pin("2")
        survey.elevationSketch.pin("3")

        assertEquals(listOf("1", "2", "3"), PhotoStore.photoIdsIn(survey))
    }

    @Test
    fun aSurveyWithNoPhotographsRefersToNone() {
        assertEquals(emptyList<String>(), PhotoStore.photoIdsIn(survey()))
    }

    /**
     * A pin inside a cross-section counts too, and for two reasons that both lose a photograph.
     *
     * [PhotoStore.nextPhotoId] takes the highest id in use, so a nested pin it cannot see is an id
     * it will hand out again — and the next [PhotoStore.save] writes over a picture somebody took
     * underground. `SurveyZip` packs exactly this list, so a nested photograph it cannot see never
     * reaches the archive, and whoever is handed the zip gets a pin pointing at nothing.
     *
     * `SketchEditor` does not put pins in cross-sections today, but [SketchJson] writes and reads
     * them there, so a survey that arrives from anywhere else can have one. What the format allows
     * is what has to be counted.
     */
    @Test
    fun aPhotographPinnedInsideACrossSectionIsCountedLikeAnyOther() {
        val survey = survey()
        survey.planSketch.pin("1")
        survey.planSketch.crossSectionDetails.add(
            CrossSectionDetail(
                Coord2D(3f, 0f),
                CrossSection(survey.activeStation, 0f),
                Sketch().also { it.pin("2") },
            ),
        )

        assertEquals(listOf("1", "2"), PhotoStore.photoIdsIn(survey))
        assertEquals("3", PhotoStore.nextPhotoId(survey))
    }

    /**
     * A photograph is still there when the survey is closed and opened again.
     *
     * The cycle nothing covered, and the one every survey goes through. Everything else here works
     * on a survey held in memory: the pins are put on a sketch and read straight back off it, so a
     * fault anywhere in writing the survey out and reading it in again — a pin dropped by the
     * sketch reader, an id that does not round-trip, a picture written under one name and looked
     * for under another — would have left every test above green.
     *
     * The last assertion is the one that is easy to leave out and expensive to get wrong. Ids are
     * highest-plus-one over the pins the survey holds, so if reopening a survey lost its pins
     * without losing its pictures, the next photograph taken would be handed an id already in use
     * and would write over a picture taken on an earlier trip.
     */
    @Test
    fun aPhotographIsStillThereWhenTheSurveyIsOpenedAgain() {
        val store = InMemoryFileStore()
        val survey = survey()
        survey.planSketch.pin("1")
        PhotoStore.save(store, directory(), survey.name, "1", notTextAtAll)
        SurveyStorage.save(store, survey, directory())

        val reopened = SurveyStorage.load(store, directory())

        assertEquals(
            listOf("1"),
            PhotoStore.photoIdsIn(reopened),
            "the pin did not survive being written out and read back",
        )
        assertContentEquals(
            notTextAtAll,
            PhotoStore.load(store, directory(), reopened.name, "1"),
            "the picture was not found where the reopened survey looks for it",
        )
        assertEquals(
            "2",
            PhotoStore.nextPhotoId(reopened),
            "the next photograph would have been given an id the drawing already uses",
        )
    }
}

