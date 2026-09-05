package dev.alfieprojects.stablestill.core

/**
 * The offset between the camera's timestamp clock and the gyroscope's.
 *
 * Android *can* guarantee these share a clock: when
 * `SENSOR_INFO_TIMESTAMP_SOURCE` is `REALTIME`, camera timestamps are
 * `SystemClock.elapsedRealtimeNanos()`, the same base as sensor events, and the
 * offset is zero. When it is `UNKNOWN` - the common case on budget hardware -
 * the camera clock is some unspecified monotonic base, and a fixed but unknown
 * offset separates them.
 *
 * An unknown offset is not fatal, it is just an unknown to be solved for. A 5 ms
 * error at a typical 0.2 rad/s tremor puts every frame about a milliradian out,
 * which at f ≈ 2000 px is a visible 2 px smear - enough to matter, small enough
 * that a coarse-to-fine search finds it quickly.
 *
 * @param offsetNanos value added to a camera timestamp to land in gyro time.
 */
data class SyncCalibration(val offsetNanos: Long) {

    fun toGyroClock(cameraTimestampNanos: Long): Long = cameraTimestampNanos + offsetNanos

    fun toCameraClock(gyroTimestampNanos: Long): Long = gyroTimestampNanos - offsetNanos

    companion object {
        /** Assumes both clocks already agree - correct when the timestamp source is REALTIME. */
        val IDENTITY = SyncCalibration(0L)

        /**
         * Coarse-to-fine search for the offset that minimises [cost].
         *
         * [cost] is supplied by the caller because scoring an offset needs image
         * data: in practice it warps the burst with a candidate offset and
         * returns the residual misalignment the optical refinement pass still
         * has to remove. Perfect sync leaves nothing for it to do.
         *
         * The search is a plain grid rather than a gradient method on purpose -
         * the residual-vs-offset curve is bumpy at the pixel level and gradient
         * descent falls into the first dimple it meets.
         *
         * @param searchRangeNanos half-width of the initial sweep.
         * @param coarseSteps number of samples in each refinement pass.
         * @param refinements how many times to zoom in around the current best.
         */
        fun search(
            searchRangeNanos: Long = 30_000_000L,
            coarseSteps: Int = 25,
            refinements: Int = 3,
            cost: (SyncCalibration) -> Double,
        ): SyncCalibration {
            require(coarseSteps >= 3) { "Need at least 3 steps to bracket a minimum" }
            var centre = 0L
            var range = searchRangeNanos
            var best = SyncCalibration(0L)
            var bestCost = Double.MAX_VALUE

            repeat(refinements.coerceAtLeast(1)) {
                val step = (2.0 * range / (coarseSteps - 1)).toLong().coerceAtLeast(1L)
                for (i in 0 until coarseSteps) {
                    val candidate = SyncCalibration(centre - range + i * step)
                    val c = cost(candidate)
                    if (c < bestCost) {
                        bestCost = c
                        best = candidate
                    }
                }
                centre = best.offsetNanos
                range = (step * 2).coerceAtLeast(1L)
            }
            return best
        }
    }
}
