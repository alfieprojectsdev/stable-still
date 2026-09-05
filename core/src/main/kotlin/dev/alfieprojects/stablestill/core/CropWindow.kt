package dev.alfieprojects.stablestill.core

/**
 * The digital crop that gives warped frames somewhere to slide to.
 *
 * Without it, rotating a frame drags undefined pixels in from outside the sensor
 * and you get black wedges at the edges - the same reason a GoPro throws away
 * roughly 10% of its field of view in HyperSmooth.
 *
 * @param marginFraction fraction of width/height removed from *each* side.
 *   0.10 keeps 80% of each axis, i.e. 64% of the original pixel count.
 */
data class CropWindow(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val marginFraction: Double,
) {
    init {
        require(marginFraction >= 0.0 && marginFraction < 0.45) {
            "marginFraction must be in [0, 0.45), was $marginFraction"
        }
    }

    val offsetX: Double = sourceWidth * marginFraction
    val offsetY: Double = sourceHeight * marginFraction
    val outputWidth: Int = ((sourceWidth * (1.0 - 2.0 * marginFraction)).toInt()) and 1.inv()
    val outputHeight: Int = ((sourceHeight * (1.0 - 2.0 * marginFraction)).toInt()) and 1.inv()

    /** Translation taking an output pixel to the corresponding anchor-frame pixel. */
    val outputToAnchor: Mat3 = Mat3(
        1.0, 0.0, offsetX,
        0.0, 1.0, offsetY,
        0.0, 0.0, 1.0,
    )

    /**
     * The largest rotation, in radians, that can be absorbed before the crop
     * window walks off the sensor. Useful for telling the user "hold steadier"
     * instead of silently dropping half the burst.
     */
    fun rotationBudgetRadians(intrinsics: CameraIntrinsics): Double {
        val f = minOf(intrinsics.fx, intrinsics.fy)
        val slackPx = minOf(offsetX, offsetY)
        return kotlin.math.atan(slackPx / f)
    }
}
