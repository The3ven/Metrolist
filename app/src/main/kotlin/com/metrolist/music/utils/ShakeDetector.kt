package com.metrolist.music.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Detects a deliberate "shake" gesture from the accelerometer and invokes [onShake].
 *
 * A shake is [REQUIRED_PEAKS] acceleration peaks above [SHAKE_THRESHOLD_G] g, each at
 * least [MIN_PEAK_GAP_MS] apart, within a [WINDOW_MS] window, debounced by [COOLDOWN_MS].
 */
class ShakeDetector(
    private val onShake: () -> Unit,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var peaks = 0
    private var firstPeakAt = 0L
    private var lastPeakAt = 0L
    private var lastTriggerAt = 0L

    fun isSupported(context: Context): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    fun start(context: Context) {
        if (sensorManager != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager = sm
        reset()
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        reset()
    }

    private fun reset() {
        peaks = 0
        firstPeakAt = 0L
        lastPeakAt = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
        if (gForce < SHAKE_THRESHOLD_G) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < COOLDOWN_MS) return

        if (lastPeakAt == 0L || now - lastPeakAt >= MIN_PEAK_GAP_MS) {
            if (firstPeakAt == 0L || now - firstPeakAt > WINDOW_MS) {
                firstPeakAt = now
                peaks = 0
            }
            lastPeakAt = now
            peaks++
            Timber.tag(TAG).d("peak %.2fg (%d/%d)", gForce, peaks, REQUIRED_PEAKS)
            if (peaks >= REQUIRED_PEAKS) {
                lastTriggerAt = now
                reset()
                Timber.tag(TAG).d("shake detected")
                onShake()
            }
        }
    }

    companion object {
        private const val TAG = "ShakeDetector"
        private const val SHAKE_THRESHOLD_G = 1.8f
        private const val REQUIRED_PEAKS = 3
        private const val MIN_PEAK_GAP_MS = 80L
        private const val WINDOW_MS = 1200L
        private const val COOLDOWN_MS = 3000L
    }
}
