package dev.alfieprojects.stablestill.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion used for orientation, stored as `w + xi + yj + zk`.
 *
 * Orientation quaternions in this codebase rotate a vector expressed in the
 * *body* (device) frame into the *reference* frame that integration started
 * from: `v_ref = q * v_body * q^-1`.
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    /** For a unit quaternion the conjugate is the inverse rotation. */
    fun conjugate() = Quaternion(w, -x, -y, -z)

    fun norm() = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quaternion {
        val n = norm()
        return if (n < 1e-12) IDENTITY else Quaternion(w / n, x / n, y / n, z / n)
    }

    /**
     * Rotation angle in radians, always in `[0, PI]`.
     *
     * Computed as `2*atan2(|v|, |w|)` rather than `2*acos(w)`. For the angles
     * this pipeline actually deals with - a 200 Hz sample of hand tremor is
     * ~5e-4 rad, and sub-nanoradian values show up in residuals - `acos` is
     * catastrophically imprecise, because `w` there differs from 1.0 by less
     * than a double can represent and the result collapses to exactly zero.
     */
    fun angle(): Double {
        val vNorm = sqrt(x * x + y * y + z * z)
        if (vNorm < 1e-300) return 0.0
        return 2.0 * atan2(vNorm, abs(w))
    }

    fun rotate(v: Vec3): Vec3 = toMatrix() * v

    fun toMatrix(): Mat3 {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return Mat3(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /**
         * Exponential map from a rotation vector (axis * angle, radians).
         *
         * Uses the small-angle series below ~1e-6 rad, which matters here: a
         * 200 Hz gyro sample of a hand tremor is exactly that small, and the
         * naive `sin(theta/2)/theta` form loses precision there.
         */
        fun fromRotationVector(rv: Vec3): Quaternion {
            val theta = rv.norm()
            if (theta < 1e-9) {
                // sin(t/2)/t -> 1/2 - t^2/48 as t -> 0
                val half = 0.5 - theta * theta / 48.0
                return Quaternion(1.0 - theta * theta / 8.0, rv.x * half, rv.y * half, rv.z * half)
                    .normalized()
            }
            val half = theta * 0.5
            val s = sin(half) / theta
            return Quaternion(cos(half), rv.x * s, rv.y * s, rv.z * s)
        }

        fun fromAxisAngle(axis: Vec3, radians: Double) =
            fromRotationVector(axis.normalized() * radians)

        /** Shortest-arc spherical interpolation; [t] is clamped to `[0, 1]`. */
        fun slerp(a: Quaternion, b: Quaternion, t: Double): Quaternion {
            val u = t.coerceIn(0.0, 1.0)
            var cosom = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            var end = b
            if (cosom < 0.0) {
                cosom = -cosom
                end = Quaternion(-b.w, -b.x, -b.y, -b.z)
            }
            if (cosom > 0.9995) {
                return Quaternion(
                    a.w + (end.w - a.w) * u,
                    a.x + (end.x - a.x) * u,
                    a.y + (end.y - a.y) * u,
                    a.z + (end.z - a.z) * u,
                ).normalized()
            }
            val omega = acos(cosom.coerceIn(-1.0, 1.0))
            val sinOm = sin(omega)
            val sa = sin((1.0 - u) * omega) / sinOm
            val sb = sin(u * omega) / sinOm
            return Quaternion(
                a.w * sa + end.w * sb,
                a.x * sa + end.x * sb,
                a.y * sa + end.y * sb,
                a.z * sa + end.z * sb,
            ).normalized()
        }
    }
}
