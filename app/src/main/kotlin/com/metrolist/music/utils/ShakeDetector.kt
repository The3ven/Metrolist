package com.metrolist.music.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Detects a deliberate shake and invokes [onShake].
 *
 * Counts [REQUIRED_PEAKS] distinct shake swings with hysteresis: a swing is
 * registered when total acceleration rises above [HIGH_G], and the next swing is
 * only armed once it falls back below [LOW_G]. This gap rejects jitter and noise
 * (small wobbles never cross the full high→low band), so it takes a real
 * back-and-forth shake. The swings must land within [WINDOW_MS] of the first and
 * be at least [MIN_GAP_MS] apart; the count resets if you pause. Debounced by
 * [COOLDOWN_MS]. Peak state is NOT reset on start(), so brief listener churn
 * doesn't drop an in-progress shake.
 */
class ShakeDetector(
    private val onShake: () -> Unit,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null

    private var armed = true          // ready to count the next swing
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
        // Do NOT reset peak state here — survives brief start/stop churn.
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = SensorManager.GRAVITY_EARTH
        val gForce = Math.sqrt(((x * x + y * y + z * z) / (g * g)).toDouble())

        val now = SystemClock.elapsedRealtime()

        // Re-arm once the signal falls back down (hysteresis lower bound).
        if (gForce < LOW_G) {
            armed = true
            return
        }

        if (now - lastTriggerAt < COOLDOWN_MS) return

        // Count a swing on the rising edge past HIGH_G, only when armed and not too soon.
        if (armed && gForce > HIGH_G && now - lastPeakAt >= MIN_GAP_MS) {
            armed = false
            if (firstPeakAt == 0L || now - firstPeakAt > WINDOW_MS) {
                firstPeakAt = now
                peaks = 0
            }
            lastPeakAt = now
            peaks++
            if (peaks >= REQUIRED_PEAKS) {
                lastTriggerAt = now
                peaks = 0
                firstPeakAt = 0L
                onShake()
            }
        }
    }

    companion object {
        /** Rising-edge threshold for a swing (g). At rest ≈ 1.0. */
        private const val HIGH_G = 1.5

        /** Must fall below this to arm the next swing — the hysteresis band (anti-jitter). */
        private const val LOW_G = 1.1

        /** Swings needed to fire. */
        private const val REQUIRED_PEAKS = 3

        /** Debounce between counted swings (ms). */
        private const val MIN_GAP_MS = 100L

        /** All swings must occur within this of the first, else the count resets. */
        private const val WINDOW_MS = 2500L

        /** Debounce between triggers. */
        private const val COOLDOWN_MS = 1500L
    }
}
