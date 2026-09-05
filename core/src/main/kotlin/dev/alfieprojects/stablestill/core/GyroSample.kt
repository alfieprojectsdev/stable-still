package dev.alfieprojects.stablestill.core

/**
 * One gyroscope reading.
 *
 * @param timestampNanos the sensor event timestamp, in the same clock domain as
 *   the camera's `SENSOR_TIMESTAMP` - *if* the device is honest about it. Whether
 *   those two clocks actually agree is a per-device question, which is what
 *   [SyncCalibration] exists to answer.
 * @param omega angular velocity in rad/s in the Android device frame
 *   (+X right, +Y up, +Z out of the screen).
 */
data class GyroSample(val timestampNanos: Long, val omega: Vec3)

/** Constant angular-rate offset subtracted from every sample before integration. */
data class GyroBias(val value: Vec3) {
    companion object {
        val NONE = GyroBias(Vec3.ZERO)

        /**
         * Estimates bias as the mean rate over a window the caller believes is
         * stationary. Returns [NONE] if the window looks like real motion,
         * because "calibrating" against a moving phone bakes in a permanent
         * drift that is worse than no correction at all.
         *
         * @param maxRestRate rad/s; any sample above this disqualifies the window.
         */
        fun estimate(samples: List<GyroSample>, maxRestRate: Double = 0.05): GyroBias {
            if (samples.isEmpty()) return NONE
            if (samples.any { it.omega.norm() > maxRestRate }) return NONE
            var sum = Vec3.ZERO
            for (s in samples) sum += s.omega
            return GyroBias(sum / samples.size.toDouble())
        }
    }
}
