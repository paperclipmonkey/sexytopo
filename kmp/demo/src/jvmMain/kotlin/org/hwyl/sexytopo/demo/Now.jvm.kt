package org.hwyl.sexytopo.demo

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

actual fun nowIso(): String = OffsetDateTime.now().format(FORMAT)
