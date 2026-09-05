package dev.alfieprojects.stablestill.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A 3-vector. Used for angular velocity, gravity, and image rays. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(dot(this))

    fun normalized(): Vec3 {
        val n = norm()
        return if (n < 1e-12) ZERO else this / n
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/**
 * Row-major 3x3 matrix.
 *
 * Rows are `m00 m01 m02 / m10 m11 m12 / m20 m21 m22`, so [times] applied to a
 * [Vec3] is the usual `M * v`. Homographies are stored in this type too - for a
 * homography the third row is the projective row rather than a rotation row.
 */
data class Mat3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double,
) {
    operator fun times(o: Mat3) = Mat3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
    )

    operator fun times(v: Vec3) = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    operator fun times(s: Double) = Mat3(
        m00 * s, m01 * s, m02 * s,
        m10 * s, m11 * s, m12 * s,
        m20 * s, m21 * s, m22 * s,
    )

    fun transpose() = Mat3(m00, m10, m20, m01, m11, m21, m02, m12, m22)

    fun det(): Double =
        m00 * (m11 * m22 - m12 * m21) -
            m01 * (m10 * m22 - m12 * m20) +
            m02 * (m10 * m21 - m11 * m20)

    fun inverse(): Mat3 {
        val d = det()
        require(abs(d) > 1e-15) { "Matrix is singular and cannot be inverted" }
        val i = 1.0 / d
        return Mat3(
            (m11 * m22 - m12 * m21) * i, (m02 * m21 - m01 * m22) * i, (m01 * m12 - m02 * m11) * i,
            (m12 * m20 - m10 * m22) * i, (m00 * m22 - m02 * m20) * i, (m02 * m10 - m00 * m12) * i,
            (m10 * m21 - m11 * m20) * i, (m01 * m20 - m00 * m21) * i, (m00 * m11 - m01 * m10) * i,
        )
    }

    /**
     * Applies this matrix as a homography to a pixel coordinate and performs the
     * perspective divide. Returns null when the point maps behind the camera or
     * onto the plane at infinity, which callers must treat as "unusable pixel".
     */
    fun mapPoint(px: Double, py: Double): Pair<Double, Double>? {
        val x = m00 * px + m01 * py + m02
        val y = m10 * px + m11 * py + m12
        val w = m20 * px + m21 * py + m22
        if (abs(w) < 1e-12) return null
        return Pair(x / w, y / w)
    }

    /** Column-major float array, the layout OpenGL ES expects for `mat3`. */
    fun toGlColumnMajor(): FloatArray = floatArrayOf(
        m00.toFloat(), m10.toFloat(), m20.toFloat(),
        m01.toFloat(), m11.toFloat(), m21.toFloat(),
        m02.toFloat(), m12.toFloat(), m22.toFloat(),
    )

    companion object {
        val IDENTITY = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun diagonal(a: Double, b: Double, c: Double) =
            Mat3(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c)

        /** Right-handed rotation of [radians] about the +Z axis. */
        fun rotationZ(radians: Double): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return Mat3(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        }
    }
}
