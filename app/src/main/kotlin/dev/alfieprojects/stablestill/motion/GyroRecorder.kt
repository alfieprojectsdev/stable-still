package dev.alfieprojects.stablestill.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dev.alfieprojects.stablestill.core.GyroSample
import dev.alfieprojects.stablestill.core.Vec3

/**
 * Keeps a rolling window of recent gyroscope history.
 *
 * The window has to extend *backwards* from the shutter press. By the time the
 * user's finger lands, the motion that will define the anchor frame has already
 * happened, so the recorder runs continuously from the moment the camera opens
 * and the capture just takes a slice out of it.
 *
 * All reads and writes are synchronised on [lock]. Sensor callbacks arrive on a
 * dedicated HandlerThread, and the capture pipeline reads from another, so this
 * is genuinely contended - but the critical sections are a few array writes.
 */
class GyroRecorder(
    private val context: Context,
    /** How much history to retain. Must comfortably exceed the burst window. */
    private val windowSeconds: Double = 4.0,
) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** TYPE_GRAVITY is already fused and low-passed; fall back to raw acceleration. */
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasGyroscope: Boolean get() = gyroSensor != null

    private val lock = Any()
    private val samples = ArrayDeque<GyroSample>()
    private var latestGravity: Vec3 = Vec3(0.0, -9.81, 0.0)

    private var thread: HandlerThread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val sample = GyroSample(
                        timestampNanos = event.timestamp,
                        omega = Vec3(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble(),
                        ),
                    )
                    synchronized(lock) {
                        samples.addLast(sample)
                        val cutoff = sample.timestampNanos - (windowSeconds * 1e9).toLong()
                        while (samples.isNotEmpty() && samples.first().timestampNanos < cutoff) {
                            samples.removeFirst()
                        }
                    }
                }

                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    val g = Vec3(
                        event.values[0].toDouble(),
                        event.values[1].toDouble(),
                        event.values[2].toDouble(),
                    )
                    synchronized(lock) { latestGravity = g }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (thread != null) return
        val t = HandlerThread("gyro-recorder").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        gyroSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        gravitySensor?.let {
            // Gravity changes slowly; sampling it fast wastes power for no gain.
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        synchronized(lock) { samples.clear() }
    }

    /**
     * Snapshot of samples covering `[fromNanos, toNanos]`, padded by [paddingNanos]
     * on each side.
     *
     * The padding matters: integration must start before the first frame and end
     * after the last, otherwise [dev.alfieprojects.stablestill.core.MotionTrack]
     * clamps at the edges and the outermost frames silently lose their correction.
     */
    fun slice(
        fromNanos: Long,
        toNanos: Long,
        paddingNanos: Long = 50_000_000L,
    ): List<GyroSample> = synchronized(lock) {
        val lo = fromNanos - paddingNanos
        val hi = toNanos + paddingNanos
        samples.filter { it.timestampNanos in lo..hi }
    }

    /** Everything currently retained, oldest first. */
    fun snapshot(): List<GyroSample> = synchronized(lock) { samples.toList() }

    fun gravity(): Vec3 = synchronized(lock) { latestGravity }

    /** True when the buffer actually spans the requested interval. */
    fun covers(fromNanos: Long, toNanos: Long): Boolean = synchronized(lock) {
        samples.isNotEmpty() &&
            samples.first().timestampNanos <= fromNanos &&
            samples.last().timestampNanos >= toNanos
    }
}
