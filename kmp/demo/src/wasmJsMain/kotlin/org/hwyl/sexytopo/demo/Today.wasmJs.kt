package org.hwyl.sexytopo.demo

/**
 * The local date, assembled from the parts rather than sliced off `toISOString()`: that returns
 * UTC, so anywhere west of Greenwich an evening trip would export with tomorrow's date, and
 * anywhere east of it a morning one with yesterday's.
 */
private fun localDateParts(): String =
    js(
        "(function(){var d=new Date();" +
            "var m=(d.getMonth()+1).toString().padStart(2,'0');" +
            "var day=d.getDate().toString().padStart(2,'0');" +
            "return d.getFullYear()+'-'+m+'-'+day})()",
    )

actual fun todayIso(): String = localDateParts()
