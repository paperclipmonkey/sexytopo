package org.hwyl.sexytopo.demo

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale

/**
 * `en_US_POSIX` is not decoration: a date formatter with a fixed pattern must be given a fixed
 * locale, or a phone set to a Japanese or Buddhist calendar writes a year no log reader can parse.
 * Apple's own documentation says so, and [todayIso] avoids the same trap by asking the calendar for
 * numbers rather than formatting at all.
 */
private val formatter =
    NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
        locale = NSLocale("en_US_POSIX")
    }

actual fun nowIso(): String = formatter.stringFromDate(NSDate())
