package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from `SurveyToolsTest`: whether one station lies under another, which is the one question
 * `moveLeg` has to ask before re-hanging a leg — a move into its own subtree is a cycle, and the
 * next traversal never comes back.
 *
 * The eight cases are the Java's eight, on the same three surveys. The port's function existed
 * without a test against it, which the ledger in `PortedTestsTest` is there to notice.
 */
class SurveyTreeTest {

    @Test
    fun nothingIsInTheSubtreeOfNothing() {
        val origin = Survey().origin
        assertFalse(Survey.isInSubtree(origin, null))
        assertFalse(Survey.isInSubtree(null, origin))
        assertFalse(Survey.isInSubtree(null, null))
    }

    @Test
    fun aStationIsInItsOwnSubtree() {
        val origin = Survey().origin
        assertTrue(Survey.isInSubtree(origin, origin))
    }

    @Test
    fun aDirectChildIsInTheSubtree() {
        val survey = TestSurveys.createStraightNorth()
        assertTrue(Survey.isInSubtree(survey.origin, survey.getStationByName("2")))
    }

    @Test
    fun aGrandchildIsInTheSubtree() {
        val survey = TestSurveys.createStraightNorth()
        assertTrue(Survey.isInSubtree(survey.origin, survey.getStationByName("3")))
    }

    @Test
    fun anAncestorIsNot() {
        val survey = TestSurveys.createStraightNorth()
        assertFalse(Survey.isInSubtree(survey.getStationByName("2"), survey.origin))
    }

    @Test
    fun unrelatedStationsAreNotInEachOthersSubtrees() {
        val survey = TestSurveys.createStraightNorthWith1EBranch()
        val two = survey.getStationByName("2")
        val five = survey.getStationByName("5")
        assertFalse(Survey.isInSubtree(five, two))
        assertFalse(Survey.isInSubtree(two, five))
    }

    @Test
    fun aBranchIsUnderTheStationItHangsOff() {
        val survey = TestSurveys.createStraightNorthWith1EBranch()
        assertTrue(Survey.isInSubtree(survey.getStationByName("1"), survey.getStationByName("5")))
    }

    @Test
    fun aDeepDescendantDownABranchIsInTheSubtree() {
        val survey = TestSurveys.createStraightNorthWith2EBranch()
        assertTrue(Survey.isInSubtree(survey.origin, survey.getStationByName("6")))
    }
}
