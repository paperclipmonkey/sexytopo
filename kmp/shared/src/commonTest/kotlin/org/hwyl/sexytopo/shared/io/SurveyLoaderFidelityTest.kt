package org.hwyl.sexytopo.shared.io

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader's structural guarantees, each one a bug this port had and a review caught.
 *
 * These are not round-trip smoke tests — [SurveyJsonTest] covers that. Every case here is a
 * malformed or awkwardly-ordered file of the kind that actually reaches a caver's phone: written
 * by an older version, half-written when the battery died, or edited by hand. The question each
 * asks is the same one: when the file cannot be read as written, does the loader lose *only* the
 * unreadable part, or does it silently take a branch of the cave with it?
 */
class SurveyLoaderFidelityTest {

    /**
     * The critical one. A leg naming a station the file does not contain used to resolve to
     * `Station.NULL_STATION`, which turns a connecting leg into a splay — so the loader would
     * happily produce a survey missing every station beyond the break, with no error and no clue
     * that anything had gone.
     */
    @Test
    fun aLegToAMissingStationIsDroppedRatherThanTurnedIntoASplay() {
        val text =
            """
            {
              "name": "Broken Cave",
              "stations": [
                {
                  "name": "1", "eeDirection": "right", "comment": "",
                  "legs": [
                    {
                      "distance": 5.0, "azimuth": 0.0, "inclination": 0.0,
                      "destination": "2", "wasShotBackwards": false, "index": 0,
                      "promotedFrom": []
                    },
                    {
                      "distance": 9.0, "azimuth": 90.0, "inclination": 0.0,
                      "destination": "999", "wasShotBackwards": false, "index": 1,
                      "promotedFrom": []
                    }
                  ]
                },
                { "name": "2", "eeDirection": "right", "comment": "", "legs": [] }
              ],
              "activeStation": "2"
            }
            """.trimIndent()

        val result = SurveyJson.load(text)

        assertTrue(result.hadPartialErrors, "the load was incomplete and should say so")
        assertTrue(
            result.problems.any { it.contains("999") },
            "the report should name the missing station; was ${result.problems}",
        )

        val onwardLegs = result.survey.origin.onwardLegs
        assertEquals(1, onwardLegs.size, "the unresolvable leg should be dropped, not kept")
        assertTrue(
            onwardLegs.none { !it.hasDestination() },
            "no leg should have been silently demoted to a splay",
        )
    }

    /**
     * Legs written before the format gained an index tag still belong in the chronological record,
     * and they belong at the front of it: they predate everything that carries an index. Without
     * the else-branch that puts them there they vanished from the record entirely, which breaks
     * undo, the triple-shot detector and (via `checkSurveyIntegrity`) the station they created.
     */
    @Test
    fun legsWithNoIndexAreRecordedFirstRatherThanNotAtAll() {
        val text =
            """
            {
              "name": "Old Format Cave",
              "stations": [
                {
                  "name": "1", "eeDirection": "right", "comment": "",
                  "legs": [
                    {
                      "distance": 5.0, "azimuth": 0.0, "inclination": 0.0,
                      "destination": "2", "wasShotBackwards": false,
                      "promotedFrom": []
                    }
                  ]
                },
                {
                  "name": "2", "eeDirection": "right", "comment": "",
                  "legs": [
                    {
                      "distance": 7.0, "azimuth": 45.0, "inclination": 0.0,
                      "destination": "3", "wasShotBackwards": false, "index": 4,
                      "promotedFrom": []
                    }
                  ]
                },
                { "name": "3", "eeDirection": "right", "comment": "", "legs": [] }
              ],
              "activeStation": "3"
            }
            """.trimIndent()

        val survey = SurveyJson.parse(text)
        val chrono = survey.getAllLegsInChronoOrder()

        assertEquals(2, chrono.size, "both legs belong in the record")
        assertEquals(5.0f, chrono[0].distance, "the unindexed leg sorts before the indexed one")
        assertEquals(7.0f, chrono[1].distance)

        // And with the record intact, integrity checking keeps both stations.
        assertEquals(3, survey.getAllStations().size)
    }

    /**
     * Station order in the file is not guaranteed to put the origin first — a survey extended
     * backwards from its original entrance ends up with a station that nothing leads to further
     * down the array. A leg arriving at the assumed origin is the proof, and re-rooting on it is
     * what keeps the earlier stations reachable at all.
     */
    @Test
    fun theOriginIsReRootedWhenALegArrivesAtIt() {
        val text =
            """
            {
              "name": "Backwards Cave",
              "stations": [
                { "name": "2", "eeDirection": "right", "comment": "", "legs": [] },
                {
                  "name": "1", "eeDirection": "right", "comment": "",
                  "legs": [
                    {
                      "distance": 5.0, "azimuth": 0.0, "inclination": 0.0,
                      "destination": "2", "wasShotBackwards": false, "index": 0,
                      "promotedFrom": []
                    }
                  ]
                }
              ],
              "activeStation": "2"
            }
            """.trimIndent()

        val survey = SurveyJson.parse(text)

        assertEquals("1", survey.origin.name, "station 1 leads to 2, so 1 is the root")
        assertEquals(
            setOf("1", "2"),
            survey.getAllStations().map { it.name }.toSet(),
            "both stations must still be reachable",
        )
        assertEquals(1, survey.getAllLegsInChronoOrder().size)
    }

    /**
     * A survey is a tree: a station is the destination of exactly one leg. A file claiming two legs
     * into the same station would build a cycle-free but doubly-attached graph, and every traversal
     * would then visit that branch twice — including the exporters, which would emit it twice.
     */
    @Test
    fun aSecondLegIntoTheSameStationIsRejected() {
        val text =
            """
            {
              "name": "Doubled Cave",
              "stations": [
                {
                  "name": "1", "eeDirection": "right", "comment": "",
                  "legs": [
                    {
                      "distance": 5.0, "azimuth": 0.0, "inclination": 0.0,
                      "destination": "2", "wasShotBackwards": false, "index": 0,
                      "promotedFrom": []
                    },
                    {
                      "distance": 5.1, "azimuth": 1.0, "inclination": 0.0,
                      "destination": "2", "wasShotBackwards": false, "index": 1,
                      "promotedFrom": []
                    }
                  ]
                },
                { "name": "2", "eeDirection": "right", "comment": "", "legs": [] }
              ],
              "activeStation": "2"
            }
            """.trimIndent()

        val result = SurveyJson.load(text)

        assertTrue(result.hadPartialErrors)
        assertEquals(
            1,
            result.survey.origin.getConnectedOnwardLegs().size,
            "only the first leg into station 2 should survive",
        )
        assertEquals(
            2,
            result.survey.getAllStations().size,
            "and the tree should still be a tree, visited once",
        )
    }

    /** Two stations with the same name is corruption; the second is dropped, as in the Java. */
    @Test
    fun aDuplicateStationNameIsSkipped() {
        val text =
            """
            {
              "name": "Confused Cave",
              "stations": [
                { "name": "1", "eeDirection": "right", "comment": "first", "legs": [] },
                { "name": "1", "eeDirection": "right", "comment": "second", "legs": [] }
              ],
              "activeStation": "1"
            }
            """.trimIndent()

        val result = SurveyJson.load(text)

        assertTrue(result.hadPartialErrors)
        assertEquals("first", result.survey.origin.comment, "the first definition wins")
        assertEquals(1, result.survey.getAllStations().size)
    }

    /** A clean file reports no problems at all — the flag has to mean something. */
    @Test
    fun aWellFormedSurveyReportsNoProblems() {
        val survey = Survey("Clean Cave")
        val two = Station("2")
        val leg = Leg(5f, 0f, 0f, two)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)
        survey.origin.addOnwardLeg(Leg(2f, 45f, 10f))

        val result = SurveyJson.load(SurveyJson.write(survey))

        assertFalse(result.hadPartialErrors, "problems were ${result.problems}")
        assertEquals(emptyList(), result.problems)
    }

    // -----------------------------------------------------------------------------------------
    // Trip metadata
    // -----------------------------------------------------------------------------------------

    /**
     * Trip metadata used to be neither read nor written, so loading a survey on iOS and saving it
     * again would strip the date, the team, the instrument and — the one with legal consequences —
     * the copyright holder and licence.
     */
    @Test
    fun tripMetadataSurvivesARoundTrip() {
        val survey = Survey("Documented Cave")
        val trip = Trip(SurveyDate(2026, 8, 29))
        trip.explorationDate = SurveyDate(1998, 12, 1)
        trip.explorationDateLinked = false
        trip.comments = "Wet. Very wet."
        trip.instrument = "DistoX2"
        trip.copyrightHolder = "Some Caving Club"
        trip.licence = "CC-BY-SA-4.0"
        trip.team =
            listOf(
                Trip.TeamEntry("Alice", listOf(Trip.Role.BOOK, Trip.Role.INSTRUMENTS)),
                Trip.TeamEntry("Bob", listOf(Trip.Role.DOG)),
            )
        survey.trip = trip

        val reloaded = SurveyJson.parse(SurveyJson.write(survey)).trip

        assertNotNull(reloaded)
        assertEquals(trip, reloaded, "every field should come back")
        assertEquals("2026-08-29", reloaded.surveyDate.toString())
    }

    /**
     * Old files put the survey date under `tripDate`; new ones use `surveyDate` and repurpose
     * `tripDate` as the (earlier) exploration date. Reading an old file as if it were a new one
     * would lose the date entirely and take the whole trip block with it.
     */
    @Test
    fun anOldFormatTripDateIsReadAsTheSurveyDate() {
        val text =
            """
            {
              "name": "Historic Cave",
              "stations": [
                { "name": "1", "eeDirection": "right", "comment": "", "legs": [] }
              ],
              "trip": {
                "tripDate": "2015-06-14",
                "comment": "From an old version",
                "team": [ { "name": "Rich", "role": ["BOOK"] } ]
              },
              "activeStation": "1"
            }
            """.trimIndent()

        val trip = SurveyJson.parse(text).trip

        assertNotNull(trip)
        assertEquals(SurveyDate(2015, 6, 14), trip.surveyDate)
        assertNull(trip.explorationDate, "an old file has no separate exploration date")
        assertTrue(trip.explorationDateLinked)
        assertEquals(listOf(Trip.TeamEntry("Rich", listOf(Trip.Role.BOOK))), trip.team)
    }

    /**
     * A trip block that will not parse costs the trip block, not the survey.
     *
     * This is a deliberate divergence: the Java lets the `ParseException` from a malformed date
     * escape `toSurvey`, which aborts the entire load — so one bad character in a date field makes
     * the cave unopenable. Dropping the metadata and keeping the survey is the better trade, and
     * the load is still reported as partial.
     */
    @Test
    fun anUnparseableTripDoesNotCostTheSurvey() {
        val text =
            """
            {
              "name": "Salvageable Cave",
              "stations": [
                {
                  "name": "1", "eeDirection": "right", "comment": "",
                  "legs": [
                    {
                      "distance": 5.0, "azimuth": 0.0, "inclination": 0.0,
                      "destination": "2", "wasShotBackwards": false, "index": 0,
                      "promotedFrom": []
                    }
                  ]
                },
                { "name": "2", "eeDirection": "right", "comment": "", "legs": [] }
              ],
              "trip": { "surveyDate": "not a date", "team": [] },
              "activeStation": "2"
            }
            """.trimIndent()

        val result = SurveyJson.load(text)

        assertNull(result.survey.trip, "the unreadable trip is dropped")
        assertTrue(result.hadPartialErrors, "but the loss is reported")
        assertEquals(2, result.survey.getAllStations().size, "the survey itself survives")
    }

    /** A survey with no trip block writes none, rather than an empty one an old app would choke on. */
    @Test
    fun aSurveyWithNoTripWritesNoTripBlock() {
        val text = SurveyJson.write(Survey("Undocumented Cave"))
        assertFalse(text.contains("\"trip\""), "was:\n$text")
    }

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    /**
     * The origin must be written first, because the reader takes the first entry as the root and
     * only re-roots if a leg contradicts it. Writing stations in tree order rather than
     * origin-then-chronological order happened to keep that property; asserting it stops a future
     * change to the ordering from quietly moving the root of the cave.
     */
    @Test
    fun theOriginIsWrittenFirst() {
        val survey = Survey("Ordered Cave")
        val two = Station("2")
        val leg = Leg(5f, 0f, 0f, two)
        survey.origin.addOnwardLeg(leg)
        survey.addLegRecord(leg)

        val text = SurveyJson.write(survey)
        assertTrue(
            text.indexOf("\"${survey.origin.name}\"") < text.indexOf("\"2\""),
            "origin should appear before its children; was:\n$text",
        )
        assertEquals(
            survey.origin.name,
            SurveyJson.parse(text).origin.name,
            "and the round trip should not move the root",
        )
    }

    /**
     * A survey the size of a real system round-trips intact.
     *
     * Caves get big — a connected system can run to thousands of stations — and both the writer and
     * the reader used to be quadratic in that number: one linear scan of the chronological record
     * per leg written, and one linear scan of the stations already written per station. Both are
     * now single hash lookups, keyed by identity since neither `Leg` nor `Station` overrides
     * `equals`. This asserts the result is unchanged; it deliberately does not assert a time, since
     * a timing test on shared CI is a flaky test.
     */
    @Test
    fun aLargeSurveyRoundTripsIntact() {
        val survey = Survey("Big System")
        var previous = survey.origin
        val stations = mutableListOf(previous)

        repeat(500) { index ->
            val next = Station("s$index")
            val leg = Leg(5f + index % 7, (index * 13 % 360).toFloat(), 0f, next)
            previous.addOnwardLeg(leg)
            survey.addLegRecord(leg)
            // A splay off every station, so the file has both kinds of leg throughout. Recorded as
            // well as hung on the station, because that is what the survey engine does — and a
            // splay left out of the record comes back from the file as an *unindexed* leg, which
            // sorts ahead of everything and so silently reorders the survey's history.
            val splay = Leg(1.5f, 90f, 0f)
            previous.addOnwardLeg(splay)
            survey.addLegRecord(splay)
            stations.add(next)
            previous = next
        }

        val reloaded = SurveyJson.load(SurveyJson.write(survey))

        assertFalse(reloaded.hadPartialErrors, "problems were ${reloaded.problems}")
        assertEquals(stations.size, reloaded.survey.getAllStations().size)
        assertEquals(
            stations.map { it.name },
            reloaded.survey.getAllStations().map { it.name },
            "and in the same order, so the origin has not moved",
        )
        val chrono = reloaded.survey.getAllLegsInChronoOrder()
        assertEquals(1000, chrono.size, "500 legs and 500 splays, all recorded")
        assertEquals(
            survey.getAllLegsInChronoOrder().map { it.distance },
            chrono.map { it.distance },
            "and in the order they were taken",
        )
    }

    /**
     * A station whose creating leg is missing from the chronological record still exists in the
     * tree. The Java writes only the origin plus each recorded leg's destination, so it drops such
     * a station and everything past it; this port appends the remainder.
     */
    @Test
    fun aStationMissingFromTheRecordIsStillWritten() {
        val survey = Survey("Unrecorded Cave")
        val two = Station("2")
        // Attached to the tree but never recorded — the shape left behind by an edit that removed
        // the record entry without unhooking the leg.
        survey.origin.addOnwardLeg(Leg(5f, 0f, 0f, two))

        val reloaded = SurveyJson.parse(SurveyJson.write(survey))

        assertEquals(
            setOf(survey.origin.name, "2"),
            reloaded.getAllStations().map { it.name }.toSet(),
        )
    }
}
