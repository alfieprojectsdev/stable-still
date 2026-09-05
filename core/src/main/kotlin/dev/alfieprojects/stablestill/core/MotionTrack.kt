package dev.alfieprojects.stablestill.core

/**
 * An integrated orientation history built from a burst of gyroscope samples.
 *
 * The track holds one orientation per gyro sample. Orientation at an arbitrary
 * timestamp - which is what we actually need, since camera frames never land on
 * a gyro tick - is recovered by slerp between the bracketing samples.
 *
 * Absolute orientation is meaningless here (it is relative to whatever the
 * device was doing at the first sample). Only *differences* are used, via
 * [rotationBetween], and those are what stabilisation needs.
 */
class MotionTrack private constructor(
    private val timestamps: LongArray,
    private val orientations: Array<Quaternion>,
) {
    val sampleCount: Int get() = timestamps.size
    val startNanos: Long get() = timestamps.first()
    val endNanos: Long get() = timestamps.last()

    /** True when [t] lies inside the integrated span, so no extrapolation is needed. */
    fun covers(t: Long): Boolean = sampleCount > 0 && t in startNanos..endNanos

    /**
     * Orientation at [t]. Timestamps outside the track clamp to the nearest end
     * rather than extrapolating: an unconstrained extrapolated rotation is the
     * fastest way to fling a frame off the sensor.
     */
    fun orientationAt(t: Long): Quaternion {
        if (timestamps.isEmpty()) return Quaternion.IDENTITY
        if (t <= timestamps.first()) return orientations.first()
        if (t >= timestamps.last()) return orientations.last()

        var lo = 0
        var hi = timestamps.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (timestamps[mid] <= t) lo = mid else hi = mid
        }
        val span = (timestamps[hi] - timestamps[lo]).toDouble()
        if (span <= 0.0) return orientations[lo]
        val u = (t - timestamps[lo]).toDouble() / span
        return Quaternion.slerp(orientations[lo], orientations[hi], u)
    }

    /**
     * Rotation carrying a body-frame vector observed at time [from] into the
     * body frame as it is oriented at time [to]: `v_to = R * v_from`.
     */
    fun rotationBetween(from: Long, to: Long): Quaternion =
        (orientationAt(to).conjugate() * orientationAt(from)).normalized()

    /** Mean angular speed (rad/s) over `[from, to]`, used to score frame sharpness. */
    fun meanAngularSpeed(from: Long, to: Long): Double {
        if (to <= from) return 0.0
        val angle = rotationBetween(from, to).angle()
        return angle / ((to - from) * 1e-9)
    }

    companion object {
        /**
         * Integrates [samples] into an orientation track.
         *
         * Uses midpoint (trapezoidal) angular velocity across each interval,
         * which halves the integration error against a zero-order hold for the
         * oscillatory motion a hand tremor produces - and costs nothing.
         */
        fun integrate(samples: List<GyroSample>, bias: GyroBias = GyroBias.NONE): MotionTrack {
            val sorted = samples.sortedBy { it.timestampNanos }
            if (sorted.isEmpty()) {
                return MotionTrack(LongArray(0), emptyArray())
            }
            val ts = LongArray(sorted.size)
            val qs = arrayOfNulls<Quaternion>(sorted.size)
            var q = Quaternion.IDENTITY
            ts[0] = sorted[0].timestampNanos
            qs[0] = q
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val cur = sorted[i]
                val dt = (cur.timestampNanos - prev.timestampNanos) * 1e-9
                if (dt > 0.0) {
                    val wPrev = prev.omega - bias.value
                    val wCur = cur.omega - bias.value
                    val wMid = (wPrev + wCur) * 0.5
                    // Body-frame increment composes on the right.
                    q = (q * Quaternion.fromRotationVector(wMid * dt)).normalized()
                }
                ts[i] = cur.timestampNanos
                qs[i] = q
            }
            @Suppress("UNCHECKED_CAST")
            return MotionTrack(ts, qs as Array<Quaternion>)
        }
    }
}
