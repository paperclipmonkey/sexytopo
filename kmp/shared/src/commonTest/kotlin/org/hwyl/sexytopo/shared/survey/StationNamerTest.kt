package org.hwyl.sexytopo.shared.survey

import kotlin.test.Test
import kotlin.test.assertEquals
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Ported from `StationNamerTest` and the naming half of `TextToolsTest`.
 */
class StationNamerTest {

    @Test
    fun nameAdvancesDigitInStraightLine() {
        val survey = TestSurveys.createStraightNorth()
        val newName = StationNamer.generateNextStationName(survey, survey.activeStation)
        assertEquals((survey.getAllStations().size + 1).toString(), newName)
    }

    @Test
    fun nameAdvancesNumberOnPotentialBranch() {
        val survey = TestSurveys.createStraightNorth()
        val newName =
            StationNamer.generateNextStationName(survey, survey.getStationByName("1")!!)
        assertEquals((survey.getAllStations().size + 1).toString(), newName)
    }

    @Test
    fun nameAdvancesNumberOnEstablishedBranch() {
        val survey = TestSurveys.createStraightNorthWith1EBranch()
        val newName =
            StationNamer.generateNextStationName(survey, survey.getStationByName("5")!!)
        assertEquals((survey.getAllStations().size + 1).toString(), newName)
    }

    @Test
    fun nameAdvancesNumberFromMiddleOfBranch() {
        val survey = TestSurveys.createStraightNorthWith2EBranch()
        val newName =
            StationNamer.generateNextStationName(survey, survey.getStationByName("5")!!)
        assertEquals((survey.getAllStations().size + 1).toString(), newName)
    }

    @Test
    fun originIsAlwaysStationOne() {
        assertEquals("1", StationNamer.generateOriginName())
        assertEquals("1", Survey().origin.name)
    }

    @Test
    fun advanceLastNumberFollowsTheJavaCases() {
        assertEquals("S2", StationNamer.advanceLastNumber("S1"))
        assertEquals("S2-1.2", StationNamer.advanceLastNumber("S2-1.1"))
        assertEquals("2", StationNamer.advanceLastNumber("1"))
        assertEquals("foo1", StationNamer.advanceLastNumber("foo"))
        assertEquals("a100f", StationNamer.advanceLastNumber("a99f"))
        assertEquals("a02f", StationNamer.advanceLastNumber("a01f"))
        assertEquals("a10f", StationNamer.advanceLastNumber("a09f"))
        assertEquals("1", StationNamer.advanceLastNumber(""))
    }
}
