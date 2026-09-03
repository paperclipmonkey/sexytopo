package org.hwyl.sexytopo.demo

/**
 * Today's date where the surveyor is, as `yyyy-MM-dd`.
 *
 * Local rather than UTC, deliberately: a trip that ends late at night is dated by the notebook, and
 * an export that disagrees with it by one day is a small, plausible, annoying bug to find later.
 *
 * The shared exporters take the date as a parameter rather than reading a clock, which is what
 * makes their golden tests possible — see `Compass.export`. This is the caller that supplies it.
 */
expect fun todayIso(): String
