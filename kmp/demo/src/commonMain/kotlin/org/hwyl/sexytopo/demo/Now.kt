package org.hwyl.sexytopo.demo

/**
 * The moment, as `yyyy-MM-dd'T'HH:mm:ssZ` (e.g. `2026-08-30T14:05:11+0100`) — the same format as
 * `Log.Message.FORMAT` in the Android app, so a log file reads identically on both.
 *
 * Local time with its offset, not UTC: a caver reading a log underground matches it against a watch
 * and notebook, not Greenwich, and the offset keeps it unambiguous later.
 */
expect fun nowIso(): String
