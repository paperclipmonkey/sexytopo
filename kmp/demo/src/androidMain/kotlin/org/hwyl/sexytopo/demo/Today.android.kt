package org.hwyl.sexytopo.demo

import java.util.Calendar

/**
 * `Calendar` rather than `java.time.LocalDate`, because minSdk is 23 and `java.time` needs 26 or
 * core library desugaring. Neither is worth a dependency for one date.
 */
actual fun todayIso(): String {
    val now = Calendar.getInstance()
    val year = now.get(Calendar.YEAR)
    val month = now.get(Calendar.MONTH) + 1
    val day = now.get(Calendar.DAY_OF_MONTH)
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
