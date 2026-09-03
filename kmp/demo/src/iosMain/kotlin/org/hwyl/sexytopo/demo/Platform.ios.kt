package org.hwyl.sexytopo.demo

import platform.UIKit.UIDevice

actual fun platformName(): String =
    UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

/** A phone has no wheel; a trackpad on an iPad reports pixels, as the browser does. */
actual val scrollUnitInPixels: Float = 1f
