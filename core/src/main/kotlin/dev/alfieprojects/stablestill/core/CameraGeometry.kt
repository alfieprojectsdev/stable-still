package dev.alfieprojects.stablestill.core

import kotlin.math.PI

/**
 * Pinhole intrinsics in *output image pixels*.
 *
 * Everything downstream works in the pixel grid of the frames the ring buffer
 * actually holds, not the full sensor active array, so build this with the
 * dimensions of the stream you configured.
 */
data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
) {
    val matrix: Mat3 = Mat3(fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0)
    val inverse: Mat3 = Mat3(1.0 / fx, 0.0, -cx / fx, 0.0, 1.0 / fy, -cy / fy, 0.0, 0.0, 1.0)

    companion object {
        /**
         * Builds intrinsics from Camera2 characteristics.
         *
         * @param focalLengthMm `LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]`
         * @param sensorWidthMm / [sensorHeightMm] `SENSOR_INFO_PHYSICAL_SIZE`
         *
         * Note this assumes the output stream spans the full active array. If the
         * stream aspect ratio differs from the sensor's, the HAL centre-crops and
         * the effective focal length in pixels changes; pass the cropped physical
         * extent rather than the full one in that case.
         */
        fun fromPhysical(
            focalLengthMm: Double,
            sensorWidthMm: Double,
            sensorHeightMm: Double,
            imageWidth: Int,
            imageHeight: Int,
        ): CameraIntrinsics {
            require(focalLengthMm > 0 && sensorWidthMm > 0 && sensorHeightMm > 0) {
                "Physical camera dimensions must be positive"
            }
            return CameraIntrinsics(
                fx = focalLengthMm / sensorWidthMm * imageWidth,
                fy = focalLengthMm / sensorHeightMm * imageHeight,
                cx = imageWidth / 2.0,
                cy = imageHeight / 2.0,
                width = imageWidth,
                height = imageHeight,
            )
        }

        /** Fallback for when characteristics are missing: assume a horizontal FOV. */
        fun fromHorizontalFov(fovDegrees: Double, imageWidth: Int, imageHeight: Int): CameraIntrinsics {
            val f = (imageWidth / 2.0) / kotlin.math.tan(fovDegrees * PI / 180.0 / 2.0)
            return CameraIntrinsics(f, f, imageWidth / 2.0, imageHeight / 2.0, imageWidth, imageHeight)
        }
    }
}

/**
 * Fixed rotation between the gyroscope's device frame and the camera's optical
 * frame.
 *
 * Android device frame: +X right, +Y up, +Z out of the screen toward the user.
 * Camera frame (OpenCV convention): +X right in the image, +Y down in the image,
 * +Z along the optical axis away from the camera.
 *
 * For a back camera held upright, the optical axis points along device -Z and
 * image-down is device -Y, giving `diag(1, -1, -1)`. The sensor's readout is
 * then rotated by `SENSOR_ORIENTATION` relative to that upright view.
 *
 * The [handedness] flag exists because the sign of that rotation is the single
 * most error-prone constant in this whole pipeline, and it is far cheaper to
 * settle it empirically on the device (Phase 4 calibration tries both and keeps
 * whichever reduces residual motion) than to argue about it in the abstract.
 */
data class RigAlignment(
    val sensorOrientationDegrees: Int,
    val frontFacing: Boolean = false,
    val handedness: Int = 1,
) {
    init {
        require(sensorOrientationDegrees % 90 == 0) { "SENSOR_ORIENTATION must be a multiple of 90" }
        require(handedness == 1 || handedness == -1) { "handedness must be +1 or -1" }
    }

    /** Rotation taking a device-frame vector into the camera frame. */
    val cameraFromDevice: Mat3 = run {
        // Upright camera frame relative to the device frame.
        val upright = if (frontFacing) {
            Mat3.diagonal(1.0, -1.0, 1.0)   // front camera looks along +Z
        } else {
            Mat3.diagonal(1.0, -1.0, -1.0)  // back camera looks along -Z
        }
        val theta = handedness * sensorOrientationDegrees * PI / 180.0
        Mat3.rotationZ(theta) * upright
    }

    /** Re-expresses a device-frame rotation in the camera frame. */
    fun toCameraFrame(deviceRotation: Quaternion): Mat3 =
        cameraFromDevice * deviceRotation.toMatrix() * cameraFromDevice.transpose()

    /** The same convention with the opposite rotation sign, for calibration sweeps. */
    fun flipped(): RigAlignment = copy(handedness = -handedness)
}
