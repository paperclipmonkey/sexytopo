package org.hwyl.sexytopo.demo

/**
 * Built by hand rather than with `toISOString`, which is always UTC and always ends in `Z`.
 *
 * The offset is written the way `SimpleDateFormat`'s `Z` writes it — `+0100`, no colon — so a log
 * line from the browser build and one from the Android app are the same shape. `getTimezoneOffset`
 * returns *minutes behind* UTC, so its sign is the opposite of the one that goes in the string.
 */
private fun nowParts(): String =
    js(
        """(function(){
          var d = new Date();
          function two(n) { return n.toString().padStart(2, '0'); }
          var offset = -d.getTimezoneOffset();
          var sign = offset < 0 ? '-' : '+';
          var abs = Math.abs(offset);
          return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate())
            + 'T' + two(d.getHours()) + ':' + two(d.getMinutes()) + ':' + two(d.getSeconds())
            + sign + two(Math.floor(abs / 60)) + two(abs % 60);
        })()""",
    )

actual fun nowIso(): String = nowParts()
