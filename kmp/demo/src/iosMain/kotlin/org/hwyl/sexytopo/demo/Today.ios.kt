package org.hwyl.sexytopo.demo

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

/**
 * `NSCalendar` rather than `NSDateFormatter`, because a formatter's output depends on the device's
 * locale and calendar: a phone set to a Japanese or Buddhist calendar would otherwise export a year
 * no survey tool can parse. Asking for the numeric components of the *current* calendar and
 * formatting them here keeps the ISO date an ISO date.
 */
actual fun todayIso(): String {
    val calendar = NSCalendar.currentCalendar
    val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay
    val parts = calendar.components(units, fromDate = NSDate())
    val month = parts.month.toString().padStart(2, '0')
    val day = parts.day.toString().padStart(2, '0')
    return "${parts.year}-$month-$day"
}
