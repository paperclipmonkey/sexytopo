package org.hwyl.sexytopo.demo

import android.os.Build

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"
