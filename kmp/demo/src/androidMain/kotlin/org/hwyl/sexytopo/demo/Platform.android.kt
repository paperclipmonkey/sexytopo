package org.hwyl.sexytopo.demo

import android.os.Build

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

/** A phone has no wheel; a mouse plugged into one reports pixels. */
actual val scrollUnitInPixels: Float = 1f
