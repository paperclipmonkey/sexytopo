package org.hwyl.sexytopo.demo

actual fun platformName(): String = "the browser (Wasm)"

/** A browser reports a wheel in pixels, measured against Chromium: `deltaY` straight through. */
actual val scrollUnitInPixels: Float = 1f
