package dev.alfieprojects.stablestill.core

import kotlin.math.atan2

/**
 * Levels the image against gravity, the stills equivalent of GoPro's Horizon
 * Lock.
 *
 * Gyro alignment alone stabilises frames *relative to each other*; it has no
 * idea which way is down, so a burst shot with a 3 degree tilt stays 3 degrees
 * tilted. The accelerometer supplies the missing absolute reference.
 *
 * This costs crop margin - a roll correction sweeps the corners furthest of any
 * rotation - so [maxCorrectionRadians] caps it rather than letting a badly held
 * phone eat the entire budget.
 */
object HorizonLock {

    /**
     * @param gravityDevice low-pass-filtered accelerometer vector in the device
     *   frame (or `TYPE_GRAVITY` directly, which is cheaper and already fused).
     * @return rotation about the optical axis that brings the horizon level, to
     *   be passed as `extraRotation` to [BurstAligner.plan].
     */
    fun correction(
        gravityDevice: Vec3,
        rig: RigAlignment,
        maxCorrectionRadians: Double = 0.12,
    ): Mat3 {
        if (gravityDevice.norm() < 1e-6) return Mat3.IDENTITY
        val gCam = rig.cameraFromDevice * gravityDevice

        // In the camera frame, image-down is +Y. If gravity's in-plane component
        // already points that way the horizon is level.
        val inPlane = kotlin.math.sqrt(gCam.x * gCam.x + gCam.y * gCam.y)
        if (inPlane < 1e-6) return Mat3.IDENTITY  // pointing straight up or down

        val roll = atan2(gCam.x, gCam.y)
        val clamped = roll.coerceIn(-maxCorrectionRadians, maxCorrectionRadians)
        return Mat3.rotationZ(-clamped)
    }

    /** Signed tilt of the horizon in radians; positive means the scene leans right. */
    fun tiltRadians(gravityDevice: Vec3, rig: RigAlignment): Double {
        if (gravityDevice.norm() < 1e-6) return 0.0
        val gCam = rig.cameraFromDevice * gravityDevice
        return atan2(gCam.x, gCam.y)
    }
}
