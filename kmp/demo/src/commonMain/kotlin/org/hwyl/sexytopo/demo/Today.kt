package org.hwyl.sexytopo.demo

/**
 * Today's date where the surveyor is, as `yyyy-MM-dd`.
 *
 * Local rather than UTC, deliberately. A trip that ends at half past eleven at night is dated that
 * day in the notebook, and an export that disagrees with the notebook by one day is a small,
 * plausible, extremely annoying error to find six months later.
 *
 * The shared exporters take the date as a parameter rather than reading a clock, which is what
 * makes their golden tests possible — see `Compass.export`. This is the caller that supplies it.
 */
expect fun todayIso(): String
