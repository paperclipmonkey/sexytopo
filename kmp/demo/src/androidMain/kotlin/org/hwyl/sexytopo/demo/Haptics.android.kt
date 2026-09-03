package org.hwyl.sexytopo.demo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Two API levels are handled because minSdk is 23: `VibratorManager` arrived in 31 and the direct
 * service lookup is deprecated there, and `VibrationEffect` arrived in 26. The deprecated
 * `vibrate(long)` is the only route below 26 and is correct there.
 */
private fun vibrator(): Vibrator? {
    val context = AndroidHost.appContext ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}

actual fun canBuzz(): Boolean = vibrator()?.hasVibrator() == true

actual fun buzz(milliseconds: Int): Boolean {
    val vibrator = vibrator() ?: return false
    if (!vibrator.hasVibrator()) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                milliseconds.toLong(),
                VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(milliseconds.toLong())
    }
    return true
}
