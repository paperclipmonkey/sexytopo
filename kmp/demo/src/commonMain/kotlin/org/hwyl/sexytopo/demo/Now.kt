package org.hwyl.sexytopo.demo

/**
 * The moment, as `yyyy-MM-dd'T'HH:mm:ssZ` — for example `2026-08-30T14:05:11+0100`.
 *
 * The format is `Log.Message.FORMAT`'s, so a log line written here reads the same as one written by
 * the Android app, and a log file moves between them intact.
 *
 * Local time with its offset, rather than UTC, for the same reason [todayIso] is local: somebody
 * reading a log in a cave is matching it against a watch and a notebook, not against Greenwich.
 * Keeping the offset means it is still unambiguous afterwards.
 *
 * The shared module has no clock — see [org.hwyl.sexytopo.shared.log.LogMessage] — so this is where
 * one is read.
 */
expect fun nowIso(): String
