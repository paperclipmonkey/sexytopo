package org.hwyl.sexytopo.demo

actual fun platformName(): String = "JVM desktop (Skia)"

/**
 * A notch of a wheel, about a finger's width of paper.
 *
 * Unlike the browser's, this one is *not* measured - there is no headless way to put a real wheel
 * event into a Swing window here, and no check in this repository exercises it. It is Compose
 * desktop's documented unit (`MouseWheelEvent`'s scroll amount, roughly one per click) times a
 * comfortable step. Worth knowing before trusting the feel of it on the desktop host, which is a
 * development target rather than one anybody surveys with.
 */
actual val scrollUnitInPixels: Float = 48f
