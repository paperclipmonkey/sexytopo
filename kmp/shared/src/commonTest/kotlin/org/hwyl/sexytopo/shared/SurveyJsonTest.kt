package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.demo.ExampleSurvey
import org.hwyl.sexytopo.shared.io.SketchJson
import org.hwyl.sexytopo.shared.io.SurveyJson
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The native format is the interop contract: a survey is a folder of plain JSON files, so an iOS
 * app that reads and writes them byte-compatibly exchanges surveys with the Android app for free.
 *
 * The fixture below is written in the Android app's exact shape (tag names from
 * SurveyJsonTranslater, splays carrying the "-" destination sentinel).
 */
class SurveyJsonTest {

    private val androidWrittenSurvey =
        """
        {
          "versionName": "1.12.3",
          "versionCode": 97,
          "surveyName": "Test Cave",
          "stations": [
            {
              "name": "1",
              "eeDirection": "right",
              "comment": "entrance",
              "legs": [
                {
                  "distance": 5.0, "azimuth": 0.0, "inclination": 0.0,
                  "destination": "2", "wasShotBackwards": false, "index": 0,
                  "promotedFrom": []
                },
                {
                  "distance": 1.5, "azimuth": 270.0, "inclination": 0.0,
                  "destination": "-", "wasShotBackwards": false, "index": 1,
                  "promotedFrom": []
                }
              ]
            },
            {
              "name": "2",
              "eeDirection": "left",
              "comment": "",
              "legs": [
                {
                  "distance": 7.25, "azimuth": 90.0, "inclination": -12.5,
                  "destination": "3", "wasShotBackwards": true, "index": 2,
                  "promotedFrom": []
                }
              ]
            },
            { "name": "3", "eeDirection": "right", "comment": "", "legs": [] }
          ],
          "activeStation": "3"
        }
        """.trimIndent()

    @Test
    fun readsASurveyWrittenByTheAndroidApp() {
        val survey = SurveyJson.parse(androidWrittenSurvey)

        assertEquals("Test Cave", survey.name)
        assertEquals(3, survey.getAllStations().size)
        assertEquals("1", survey.origin.name)
        assertEquals("3", survey.activeStation.name)
        assertEquals("entrance", survey.origin.comment)
    }

    @Test
    fun distinguishesSplaysFromFullLegs() {
        val survey = SurveyJson.parse(androidWrittenSurvey)
        val origin = survey.origin

        assertEquals(1, origin.getConnectedOnwardLegs().size)
        assertEquals(1, origin.getUnconnectedOnwardLegs().size)
        // The "-" destination is the splay sentinel, not a station named "-".
        assertFalse(origin.getUnconnectedOnwardLegs().first().hasDestination())
        assertEquals("2", origin.getConnectedOnwardLegs().first().destination.name)
    }

    @Test
    fun preservesLegDetailIncludingBackwardsShots() {
        val survey = SurveyJson.parse(androidWrittenSurvey)
        val leg = survey.getStationByName("2")!!.getConnectedOnwardLegs().first()

        assertEquals(7.25f, leg.distance)
        assertEquals(90f, leg.azimuth)
        assertEquals(-12.5f, leg.inclination)
        assertTrue(leg.wasShotBackwards)
    }

    @Test
    fun preservesExtendedElevationDirection() {
        val survey = SurveyJson.parse(androidWrittenSurvey)
        assertEquals(
            ExtendedElevationDirection.LEFT,
            survey.getStationByName("2")!!.extendedElevationDirection,
        )
        assertEquals(
            ExtendedElevationDirection.RIGHT,
            survey.getStationByName("3")!!.extendedElevationDirection,
        )
    }

    @Test
    fun rebuildsChronologicalLegOrderFromIndex() {
        val survey = SurveyJson.parse(androidWrittenSurvey)
        val chrono = survey.getAllLegsInChronoOrder()
        assertEquals(3, chrono.size)
        assertEquals(5f, chrono[0].distance)
        assertEquals(1.5f, chrono[1].distance)
        assertEquals(7.25f, chrono[2].distance)
    }

    @Test
    fun surveyRoundTripsThroughJson() {
        val original = SurveyJson.parse(androidWrittenSurvey)
        val reparsed = SurveyJson.parse(SurveyJson.write(original))

        assertEquals(original.name, reparsed.name)
        assertEquals(original.getAllStations().size, reparsed.getAllStations().size)
        assertEquals(original.getAllLegs().size, reparsed.getAllLegs().size)
        assertEquals(original.activeStation.name, reparsed.activeStation.name)

        val originalLeg = original.getStationByName("2")!!.getConnectedOnwardLegs().first()
        val reparsedLeg = reparsed.getStationByName("2")!!.getConnectedOnwardLegs().first()
        assertEquals(originalLeg.distance, reparsedLeg.distance)
        assertEquals(originalLeg.azimuth, reparsedLeg.azimuth)
        assertEquals(originalLeg.inclination, reparsedLeg.inclination)
        assertEquals(originalLeg.wasShotBackwards, reparsedLeg.wasShotBackwards)
    }

    @Test
    fun toleratesPartlyUnreadableSurveys() {
        // The Android loader deliberately loads what it can rather than failing outright.
        val damaged =
            """
            { "surveyName": "Broken", "stations": [
                { "name": "1", "legs": [
                    { "distance": 5.0, "azimuth": 0.0, "inclination": 0.0, "destination": "2" },
                    { "distance": 5.0, "azimuth": 999.0, "inclination": 0.0, "destination": "-" }
                ]},
                { "name": "2", "legs": [] }
            ]}
            """.trimIndent()

        val survey = SurveyJson.parse(damaged)
        assertEquals("Broken", survey.name)
        // The illegal azimuth is dropped; the good leg survives.
        assertEquals(1, survey.origin.onwardLegs.size)
    }

    @Test
    fun readsASketchWrittenByTheAndroidApp() {
        val sketchJson =
            """
            {
              "surveyName": "Test Cave",
              "paths": [
                { "colour": "BLACK", "points": [
                    {"x": 0.0, "y": 0.0}, {"x": 1.5, "y": -2.5}, {"x": 3.0, "y": -4.0}
                ]},
                { "colour": "BROWN", "points": [{"x": 5.0, "y": 5.0}, {"x": 6.0, "y": 6.0}] }
              ],
              "labels": [
                { "colour": "BLUE", "location": {"x": 1.0, "y": 1.0},
                  "text": "Entrance", "size": 1.2 }
              ],
              "symbols": [
                { "colour": "BLACK", "location": {"x": 2.0, "y": 2.0},
                  "symbol-id": "STALACTITE", "size": 0.5, "angle": 90.0 }
              ],
              "x-sections": []
            }
            """.trimIndent()

        val sketch = SketchJson.parse(sketchJson)

        assertEquals(2, sketch.pathDetails.size)
        assertEquals(3, sketch.pathDetails[0].path.size)
        assertEquals(Colour.BLACK, sketch.pathDetails[0].colour)
        assertEquals(Colour.BROWN, sketch.pathDetails[1].colour)

        assertEquals(1, sketch.textDetails.size)
        assertEquals("Entrance", sketch.textDetails[0].text)
        assertEquals(Colour.BLUE, sketch.textDetails[0].colour)

        assertEquals(1, sketch.symbolDetails.size)
        assertEquals("STALACTITE", sketch.symbolDetails[0].symbolName)
        assertEquals(90f, sketch.symbolDetails[0].angle)
    }

    @Test
    fun colourValuesMatchTheAndroidEnum() {
        // Sketch colours are packed RGB ints in the app's own enum; these must not drift.
        assertEquals(0x000000, Colour.BLACK.baseValue)
        assertEquals(0xFFFFFF, Colour.WHITE.baseValue)
        assertEquals(0x0000FF, Colour.BLUE.baseValue)
        assertNotNull(Colour.fromNameOrNull("BLACK"))
        assertEquals(null, Colour.fromNameOrNull("NOT_A_COLOUR"))
        assertFalse(Colour.NONE.isDrawable)
        assertTrue(Colour.BLACK.isDrawable)
    }

    @Test
    fun exampleSurveyIsDeterministicAndProjectable() {
        val first = ExampleSurvey.create()
        val second = ExampleSurvey.create()

        assertEquals(first.getAllStations().size, second.getAllStations().size)
        assertEquals(first.getAllLegs().size, second.getAllLegs().size)
        assertTrue(first.getAllStations().size > 10, "example survey should have real content")

        val plan = Projection2D.PLAN.project(first)
        assertEquals(first.getAllStations().size, plan.stationMap.size)
        assertEquals(first.getAllLegs().size, plan.legMap.size)

        val elevation = Projection2D.EXTENDED_ELEVATION.project(first)
        assertEquals(first.getAllStations().size, elevation.stationMap.size)

        assertTrue(
            first.getSketch(Projection2D.PLAN).pathDetails.isNotEmpty(),
            "example survey should have wall sketch lines to draw",
        )
    }

    @Test
    fun exampleSurveySketchRoundTripsThroughJson() {
        val survey = ExampleSurvey.create()
        val sketch = survey.getSketch(Projection2D.PLAN)
        val reparsed = SketchJson.parse(SketchJson.write(sketch, survey.name))

        assertEquals(sketch.pathDetails.size, reparsed.pathDetails.size)
        assertEquals(sketch.textDetails.size, reparsed.textDetails.size)
        assertEquals(
            sketch.pathDetails.first().path.size,
            reparsed.pathDetails.first().path.size,
        )
    }
}
