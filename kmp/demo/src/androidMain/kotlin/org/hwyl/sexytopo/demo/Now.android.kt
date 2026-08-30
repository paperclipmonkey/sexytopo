package org.hwyl.sexytopo.demo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `SimpleDateFormat` rather than `java.time`, because minSdk is 23 and `java.time` needs 26 or core
 * library desugaring — the same reason [todayIso] uses `Calendar`. This is also literally the class
 * `Log.Message.FORMAT` is, so the output is identical by construction.
 *
 * `Locale.US` so that a phone set to a locale with its own digits still writes a parseable
 * timestamp.
 */
@Suppress("ConstantLocale")
private val FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

actual fun nowIso(): String = FORMAT.format(Date())
