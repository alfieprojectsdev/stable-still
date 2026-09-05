package dev.alfieprojects.stablestill.core

/**
 * Timing metadata for one captured frame, all in the camera's timestamp domain.
 *
 * @param sensorTimestampNanos `CaptureResult.SENSOR_TIMESTAMP` - the start of
 *   exposure of the *first row*, not the middle of the frame.
 * @param exposureTimeNanos `CaptureResult.SENSOR_EXPOSURE_TIME`.
 * @param rollingShutterSkewNanos `CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW`,
 *   the delay between the first and last row starting exposure. Zero if the
 *   device does not report it, which costs accuracy but never correctness.
 */
data class FrameMeta(
    val index: Int,
    val sensorTimestampNanos: Long,
    val exposureTimeNanos: Long,
    val rollingShutterSkewNanos: Long = 0L,
    val width: Int,
    val height: Int,
) {
    /** Exposure midpoint of image row [y], accounting for rolling-shutter readout. */
    fun rowMidExposureNanos(y: Int): Long {
        val denom = (height - 1).coerceAtLeast(1)
        val rowDelay = rollingShutterSkewNanos * y.toDouble() / denom
        return sensorTimestampNanos + rowDelay.toLong() + exposureTimeNanos / 2
    }

    /** Exposure midpoint of the centre row - the single time that represents this frame. */
    val midExposureNanos: Long
        get() = sensorTimestampNanos + rollingShutterSkewNanos / 2 + exposureTimeNanos / 2

    /** Exposure midpoint of the first and last rows. */
    val firstRowMidNanos: Long get() = rowMidExposureNanos(0)
    val lastRowMidNanos: Long get() = rowMidExposureNanos(height - 1)
}
