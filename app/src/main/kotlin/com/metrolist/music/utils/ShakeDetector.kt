package com.metrolist.music.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import timber.log.Timber

/**
 * Detects a shake gesture from the accelerometer and invokes [onShake].
 *
 * Uses a sustained-motion detector (à la Square's Seismic): a sample counts as
 * "accelerating" when the total acceleration exceeds [ACCEL_THRESHOLD_G]. A shake
 * fires when, over a short rolling window ([WINDOW_MS]), at least [MIN_SAMPLES]
 * samples spanning at least [MIN_SPAN_MS] are accelerating and they make up at
 * least [FRACTION] of the window. Debounced by [COOLDOWN_MS]. This is far more
 * reliable than counting discrete peaks: a real shake is continuous motion, not
 * cleanly separated spikes.
 */
class ShakeDetector(
    private val onShake: () -> Unit,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lastTriggerAt = 0L

    // Rolling window of recent samples: parallel arrays of timestamp (ms) + accelerating.
    private val times = ArrayDeque<Long>()
    private val accel = ArrayDeque<Boolean>()
    private var acceleratingCount = 0

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
        clearWindow()
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        clearWindow()
    }

    private fun clearWindow() {
        times.clear()
        accel.clear()
        acceleratingCount = 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = SensorManager.GRAVITY_EARTH
        // Total acceleration magnitude in g units (≈1.0 at rest).
        val gForce = Math.sqrt(((x * x + y * y + z * z) / (g * g)).toDouble())
        val accelerating = gForce > ACCEL_THRESHOLD_G

        val now = SystemClock.elapsedRealtime()

        // Add this sample and purge anything older than the window.
        times.addLast(now)
        accel.addLast(accelerating)
        if (accelerating) acceleratingCount++
        while (times.isNotEmpty() && now - times.first() > WINDOW_MS) {
            times.removeFirst()
            if (accel.removeFirst()) acceleratingCount--
        }

        if (now - lastTriggerAt < COOLDOWN_MS) return

        val count = accel.size
        val span = if (times.isEmpty()) 0L else now - times.first()
        if (count >= MIN_SAMPLES &&
            span >= MIN_SPAN_MS &&
            acceleratingCount >= (count * FRACTION).toInt()
        ) {
            lastTriggerAt = now
            clearWindow()
            Timber.tag(TAG).d("shake detected")
            onShake()
        }
    }

    companion object {
        private const val TAG = "ShakeDetector"

        /** A sample is "accelerating" when total accel exceeds this (g). 1.0 = at rest. */
        private const val ACCEL_THRESHOLD_G = 1.5

        /** Rolling window length. */
        private const val WINDOW_MS = 500L

        /** The accelerating samples must span at least this long (avoids single bumps). */
        private const val MIN_SPAN_MS = 200L

        /** Minimum samples in the window before a decision (≈ a few at 50 Hz). */
        private const val MIN_SAMPLES = 8

        /** Fraction of the window that must be accelerating. */
        private const val FRACTION = 0.6

        /** Debounce between triggers. */
        private const val COOLDOWN_MS = 2000L
    }
}
