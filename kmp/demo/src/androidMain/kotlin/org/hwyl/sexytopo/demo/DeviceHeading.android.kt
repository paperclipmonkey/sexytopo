package org.hwyl.sexytopo.demo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * `GraphActivity`'s rotation-vector listener, moved into a composable so it registers and
 * unregisters with the arrow instead of with the activity.
 *
 * The rotation vector rather than the raw magnetometer, exactly as the Java has it: Android fuses
 * the magnetometer with the gyroscope and accelerometer behind that sensor, so the heading holds
 * still while a hand shakes and settles far faster than a bare compass does. A cave is also full
 * of steel — bolts, hangers, the surveyor's own lamp — and the fused sensor recovers from a
 * passing lump of it rather than swinging round to it.
 */
@Composable
actual fun rememberDeviceHeading(enabled: Boolean): State<Float?> {
    val heading = remember { mutableStateOf<Float?>(null) }
    val view = LocalView.current

    DisposableEffect(enabled, view) {
        val manager = view.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || manager == null || sensor == null) {
            // A tablet with no magnetometer is a real device, not an error: it draws the arrow
            // pointing up, the same as the desktop does.
            heading.value = null
            return@DisposableEffect onDispose {}
        }

        val listener =
            object : SensorEventListener {
                // Held on the listener rather than allocated per event. This fires as often as
                // the screen refreshes, and three arrays a frame is three arrays a frame the
                // collector has to walk while the surveyor is drawing.
                private val rotation = FloatArray(9)
                private val remapped = FloatArray(9)
                private val orientation = FloatArray(3)

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    val (axisX, axisY) = screenAxes(view)
                    SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
                    SensorManager.getOrientation(remapped, orientation)
                    heading.value =
                        normaliseHeading(Math.toDegrees(orientation[0].toDouble()).toFloat())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            manager.unregisterListener(listener)
            heading.value = null
        }
    }

    return heading
}

/**
 * Which way round the picture is being drawn, as the pair of axes that turns the sensor's reading
 * into a heading for the top of the screen. Ported straight from `GraphActivity.onSensorChanged`.
 *
 * Remapping the matrix rather than adding ninety degrees to the answer: the azimuth is read off
 * the device's own y axis, so on a phone held up to look at a passage — rather than flat, which is
 * the only case where the two agree — adding a constant gives a heading that is wrong by however
 * far the phone is tilted.
 *
 * `getRotation` and not the configuration's orientation: a tablet whose natural orientation is
 * landscape reports the same rotation as an upright phone, and it is the rotation away from
 * natural that the axes have to undo.
 */
private fun screenAxes(view: View): Pair<Int, Int> =
    when (view.display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
