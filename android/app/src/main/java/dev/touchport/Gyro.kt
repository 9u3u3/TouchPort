package dev.touchport

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import kotlin.math.abs

/**
 * Gyro aim: turns phone rotation into mouse motion, like gyro aim in mobile shooters.
 * Runs on its own thread at the fastest sensor rate and adds to the same mouse counters as touch aim.
 */
class Gyro(ctx: Context, private val link: Link, private val settings: Settings) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null
    private var lastTs = 0L

    val available get() = sensor != null

    /** Flipped by the GYRO button. */
    @Volatile var enabled = true

    /** Held keys, for the "while aiming / firing" modes. */
    @Volatile var buttons = 0L

    /** Display rotation (Surface.ROTATION_90 or ROTATION_270 in landscape). */
    @Volatile var rotation = Surface.ROTATION_90

    fun start() {
        if (sensor == null || settings.gyroMode == GyroMode.OFF || thread != null) return
        val t = HandlerThread("tp-gyro", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        lastTs = 0
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    private fun active() = enabled && when (settings.gyroMode) {
        GyroMode.OFF -> false
        GyroMode.ALWAYS -> true
        GyroMode.ADS -> buttons and (1L shl Keys.RMB) != 0L
        GyroMode.FIRE_ADS -> buttons and ((1L shl Keys.RMB) or (1L shl Keys.LMB)) != 0L
    }

    override fun onSensorChanged(e: SensorEvent) {
        val dt = if (lastTs == 0L) 0f else (e.timestamp - lastTs) * 1e-9f
        lastTs = e.timestamp
        if (dt <= 0f || dt > 0.1f || !active()) return

        // Angular rates in rad/s, counter-clockwise positive around the device axes.
        // In landscape the device X axis points up (ROTATION_90) or down (ROTATION_270),
        // and the device Y axis points left or right.
        val flip = if (rotation == Surface.ROTATION_270) -1f else 1f
        val left = if (settings.gyroRoll) e.values[2] else e.values[0] * flip   // turning left
        val up = e.values[1] * flip                                            // tilting up
        val l = if (abs(left) < DEADZONE) 0f else left
        val u = if (abs(up) < DEADZONE) 0f else up
        if (l == 0f && u == 0f) return

        val k = RAD_TO_DEG * COUNTS_PER_DEG * settings.gyroSens * dt
        val dx = -l * k * (if (settings.gyroInvertX) -1f else 1f)
        val dy = -u * k * (if (settings.gyroInvertY) -1f else 1f)
        link.addMouse(dx, dy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val RAD_TO_DEG = 57.29578f
        const val COUNTS_PER_DEG = 30f   // mouse counts per degree at sensitivity 1.0
        const val DEADZONE = 0.004f      // rad/s, hides sensor noise when the phone is still
    }
}
