package org.hwyl.sexytopo.shared.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android app's `CalibrationReadingListTest`, ported.
 *
 * Every reading here has the same six counts, as the Java test's `new CalibrationReading()` do,
 * which is what makes the identity checks mean something: [CalibrationReading] is a data class,
 * so a list that found readings by value would pass none of these.
 */
class CalibrationReadingListTest {

    private fun reading() = CalibrationReading(0, 0, 0, 0, 0, 0)

    private fun listOfReadings(count: Int): CalibrationReadingList =
        CalibrationReadingList().apply { repeat(count) { add(reading()) } }

    @Test
    fun replacingFromTheMiddleLeavesAGap() {
        val list = listOfReadings(3)
        val third = list.all[2]

        list.replace(1)

        assertEquals(3, list.all.size)
        assertNull(list.all[1])
        assertSame(third, list.all[2])
        assertEquals(2, list.count)
        assertEquals(1, list.nextIndex)
    }

    @Test
    fun theNextReadingFillsTheFirstGap() {
        val list = listOfReadings(4)
        list.replace(2)
        list.replace(1)

        val retaken = reading()
        list.add(retaken)

        assertSame(retaken, list.all[1])
        assertEquals(2, list.nextIndex)
        assertSame(retaken, list.last)
    }

    @Test
    fun replacingTheEndLeavesNoTrailingGaps() {
        val list = listOfReadings(3)
        list.replace(1)
        list.replace(2)

        assertEquals(1, list.all.size)
        assertEquals(1, list.nextIndex)
    }

    @Test
    fun deletingMovesLaterReadingsUp() {
        val list = listOfReadings(3)
        val third = list.all[2]

        list.delete(1)

        assertEquals(2, list.all.size)
        assertSame(third, list.all[1])
        assertEquals(2, list.nextIndex)
    }

    @Test
    fun deletingAGapClosesIt() {
        val list = listOfReadings(3)
        list.replace(1)

        list.delete(1)

        assertEquals(2, list.all.size)
        assertTrue(list.isComplete(2))
    }

    @Test
    fun isCompleteOnlyWithoutGaps() {
        val list = listOfReadings(4)
        assertTrue(list.isComplete(4))
        assertTrue(list.isComplete(3))

        list.replace(0)
        assertFalse(list.isComplete(4))
        assertFalse(list.isComplete(1))
    }

    @Test
    fun extraReadingsStillNeedTheirGapsFilled() {
        val list = listOfReadings(5)
        assertTrue(list.isComplete(4))

        list.replace(3)
        assertFalse(list.isComplete(2))
    }

    @Test
    fun deletingTheLastReadingShowsTheOneBefore() {
        val list = listOfReadings(2)
        val first = list.all[0]

        list.deleteLastAdded()

        assertEquals(1, list.count)
        assertSame(first, list.last)

        list.deleteLastAdded()
        assertNull(list.last)
        assertTrue(list.isEmpty)
    }

    @Test
    fun deletingARetakeLeavesTheGapAgain() {
        val list = listOfReadings(4)
        list.replace(1)
        list.add(reading())

        list.deleteLastAdded()

        assertEquals(4, list.all.size)
        assertNull(list.all[1])
        assertEquals(1, list.nextIndex)
    }

    @Test
    fun setAllKeepsGapsButTrimsTheEnd() {
        val list = CalibrationReadingList()
        val first = reading()
        val third = reading()

        list.setAll(listOf(first, null, third, null))

        assertEquals(3, list.all.size)
        assertEquals(1, list.nextIndex)
        assertSame(third, list.last)
    }
}
