package org.hwyl.sexytopo.shared.log

/**
 * One line in the log: when, what, and whether it was a problem.
 *
 * Ported from `Log.Message`. The timestamp is a formatted string rather than a date, because this
 * module has no clock — the same decision the exporters make, and for the same reason: a value that
 * is passed in can be asserted on, and a value read from a clock cannot.
 */
class LogMessage(
    /** ISO 8601 with an offset, as `Log.Message.FORMAT` writes it: `2026-08-30T14:05:11+0000`. */
    val timestamp: String,
    val text: String,
    val isError: Boolean = false,
) {
    override fun toString(): String = "$timestamp${if (isError) " ERROR" else ""} $text"
}

/**
 * Which log, and how much of it is kept.
 *
 * The two limits are the Java's `MAX_DEVICE_LOG_SIZE` and `MAX_SYSTEM_LOG_SIZE`, and the file names
 * are what `Log.getLogFile` builds, so a log written by either app is readable by the other.
 */
enum class LogType(val limit: Int, val fileName: String) {
    /** What the instrument did: connected, sent this frame, went away. A hundred lines. */
    DEVICE(100, "device.log.json"),

    /** What the app did. A thousand lines, because this is the one you send somebody. */
    SYSTEM(1000, "system.log.json"),
}

/**
 * A bounded log of what has happened, kept so that it can be read *on the phone*.
 *
 * Ported from `control/Log`, minus the parts that cannot travel: the Android `Log` proxy, the
 * `LocalBroadcastManager` events, and Firebase crash reporting. What is left is the part that
 * matters underground — when an instrument will not connect in a cave there is no logcat, no Xcode
 * console and no signal, and the difference between "it didn't work" and a fixable bug report is
 * whether the app kept what it saw.
 *
 * An instance rather than the Java's statics: statics are why the Android app needs a `Context`
 * handed to the logger before anything can be logged, and why its own tests cannot exercise it.
 */
class ActivityLog(val type: LogType) {

    private val messages = ArrayDeque<LogMessage>()

    /** Oldest first, as the Java's queue iterates. */
    val entries: List<LogMessage> get() = messages.toList()

    val size: Int get() = messages.size

    fun add(message: LogMessage) {
        // A while rather than an if: a log loaded from a file written by a version with a larger
        // limit would otherwise stay over the limit for ever, shedding one line per line added.
        while (messages.size >= type.limit) messages.removeFirst()
        messages.addLast(message)
    }

    fun add(timestamp: String, text: String, isError: Boolean = false) {
        add(LogMessage(timestamp, text, isError))
    }

    fun clear() {
        messages.clear()
    }

    /**
     * Replaces everything, keeping only the most recent [LogType.limit] lines.
     *
     * The tail rather than the head: a file longer than the limit has the interesting end last.
     */
    fun replaceAll(loaded: List<LogMessage>) {
        messages.clear()
        for (message in loaded.takeLast(type.limit)) messages.addLast(message)
    }

    /** The whole log as text, which is the form somebody can paste into a bug report. */
    fun asText(): String = messages.joinToString("\n") { it.toString() }
}
