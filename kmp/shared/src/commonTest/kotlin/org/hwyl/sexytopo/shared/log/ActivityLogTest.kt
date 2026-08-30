package org.hwyl.sexytopo.shared.log

import org.hwyl.sexytopo.shared.io.LogJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log is what you have instead of a console when the thing that will not work is a metre of
 * cave passage away from a phone with no signal. Worth having tests.
 */
class ActivityLogTest {

    private fun aMessage(n: Int) = LogMessage("2026-08-30T10:00:${n % 60}+0000", "line $n")

    @Test
    fun theDeviceLogKeepsTheLastHundredLines() {
        val log = ActivityLog(LogType.DEVICE)
        for (n in 1..150) log.add(aMessage(n))

        assertEquals(100, log.size)
        assertEquals("line 51", log.entries.first().text)
        assertEquals("line 150", log.entries.last().text)
    }

    @Test
    fun theSystemLogKeepsTenTimesAsMany() {
        assertEquals(1000, LogType.SYSTEM.limit)
        assertEquals(100, LogType.DEVICE.limit)
    }

    @Test
    fun clearingLeavesNothing() {
        val log = ActivityLog(LogType.DEVICE)
        log.add(aMessage(1))
        log.clear()
        assertEquals(0, log.size)
        assertEquals("", log.asText())
    }

    /**
     * A file written by a version with a bigger limit must not leave the log permanently over it,
     * shedding one line for every line added.
     */
    @Test
    fun loadingAnOversizedLogKeepsTheRecentEnd() {
        val log = ActivityLog(LogType.DEVICE)
        log.replaceAll((1..500).map { aMessage(it) })

        assertEquals(100, log.size)
        assertEquals("line 401", log.entries.first().text)
        assertEquals("line 500", log.entries.last().text)

        log.add(LogMessage("2026-08-30T11:00:00+0000", "and one more"))
        assertEquals(100, log.size)
        assertEquals("and one more", log.entries.last().text)
    }

    @Test
    fun theTextFormIsWhatSomebodyCanPasteIntoABugReport() {
        val log = ActivityLog(LogType.DEVICE)
        log.add("2026-08-30T10:00:00+0000", "connected to DistoX-1234")
        log.add("2026-08-30T10:00:04+0000", "frame not understood", isError = true)

        assertEquals(
            "2026-08-30T10:00:00+0000 connected to DistoX-1234\n" +
                "2026-08-30T10:00:04+0000 ERROR frame not understood",
            log.asText(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The file format
    // ---------------------------------------------------------------------------------------

    /**
     * `Log.Message.marshal` builds a `Map<String, String>`, so `isError` is written as the string
     * "true" rather than as a boolean, and `unmarshal` reads it back with `getString`. Writing a
     * real boolean would make the Android app's own reader throw.
     */
    @Test
    fun isErrorIsWrittenAsAStringBecauseTheAndroidAppReadsItAsOne() {
        val json = LogJson.write(listOf(LogMessage("2026-08-30T10:00:00+0000", "oh dear", true)))

        assertTrue(json.contains("\"isError\": \"true\""), json)
        assertFalse(json.contains("\"isError\": true"), json)
    }

    @Test
    fun aLogRoundTrips() {
        val messages =
            listOf(
                LogMessage("2026-08-30T10:00:00+0000", "connected", false),
                LogMessage("2026-08-30T10:00:04+0000", "lost the instrument", true),
            )

        val read = LogJson.read(LogJson.write(messages))

        assertEquals(2, read.size)
        assertEquals("connected", read[0].text)
        assertFalse(read[0].isError)
        assertEquals("2026-08-30T10:00:04+0000", read[1].timestamp)
        assertTrue(read[1].isError)
    }

    /** A file this port had written more sensibly should still load. */
    @Test
    fun aRealBooleanIsAcceptedToo() {
        val read = LogJson.read("""[{"timestamp":"t","isError":true,"text":"bang"}]""")
        assertEquals(1, read.size)
        assertTrue(read[0].isError)
    }

    /**
     * The log is what you read when something else has already gone wrong. It must not become the
     * next thing that goes wrong.
     */
    @Test
    fun aCorruptLogReadsAsAnEmptyOneRatherThanThrowing() {
        assertEquals(0, LogJson.read("this is not JSON at all").size)
        assertEquals(0, LogJson.read("").size)
        assertEquals(0, LogJson.read("{}").size)
    }

    @Test
    fun aLineWithNoTextIsDroppedAndOneWithNoTimestampIsKept() {
        val read =
            LogJson.read(
                """[{"timestamp":"t","isError":"false"},{"text":"still worth having"}]""",
            )

        assertEquals(1, read.size)
        assertEquals("still worth having", read[0].text)
        assertEquals("", read[0].timestamp)
    }
}
