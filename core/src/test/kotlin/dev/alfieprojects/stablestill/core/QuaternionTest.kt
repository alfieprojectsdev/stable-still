package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class QuaternionTest {

    @Test
    fun `rotating about Z by 90 degrees maps X onto Y`() {
        val q = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), PI / 2)
        val v = q.rotate(Vec3(1.0, 0.0, 0.0))
        assertEquals(0.0, v.x, 1e-12)
        assertEquals(1.0, v.y, 1e-12)
        assertEquals(0.0, v.z, 1e-12)
    }

    @Test
    fun `conjugate undoes the rotation`() {
        val q = Quaternion.fromRotationVector(Vec3(0.03, -0.11, 0.07))
        val round = q.conjugate() * q
        assertEquals(1.0, abs(round.w), 1e-12)
        assertEquals(0.0, round.x, 1e-12)
        assertEquals(0.0, round.y, 1e-12)
        assertEquals(0.0, round.z, 1e-12)
    }

    @Test
    fun `small angle branch stays accurate where the closed form loses precision`() {
        // A 200 Hz sample of a 0.1 rad/s tremor is a 5e-4 rad increment; the
        // branch below 1e-9 must not introduce error larger than the signal.
        for (theta in listOf(1e-12, 1e-10, 1e-9, 1e-7, 5e-4)) {
            val q = Quaternion.fromRotationVector(Vec3(0.0, 0.0, theta))
            assertEquals("angle for theta=$theta", theta, q.angle(), theta * 1e-6 + 1e-15)
            assertEquals("unit norm for theta=$theta", 1.0, q.norm(), 1e-12)
        }
    }

    @Test
    fun `rotation matrix agrees with direct quaternion rotation`() {
        val q = Quaternion.fromRotationVector(Vec3(0.2, 0.4, -0.3))
        val v = Vec3(0.7, -1.3, 2.1)
        val a = q.rotate(v)
        val b = q.toMatrix() * v
        assertEquals(a.x, b.x, 1e-12)
        assertEquals(a.y, b.y, 1e-12)
        assertEquals(a.z, b.z, 1e-12)
    }

    @Test
    fun `rotation matrix is orthonormal with unit determinant`() {
        val m = Quaternion.fromRotationVector(Vec3(0.5, -0.2, 0.9)).toMatrix()
        assertEquals(1.0, m.det(), 1e-12)
        val shouldBeIdentity = m * m.transpose()
        assertEquals(1.0, shouldBeIdentity.m00, 1e-12)
        assertEquals(1.0, shouldBeIdentity.m11, 1e-12)
        assertEquals(1.0, shouldBeIdentity.m22, 1e-12)
        assertEquals(0.0, shouldBeIdentity.m01, 1e-12)
    }

    @Test
    fun `slerp hits both endpoints and takes the short way round`() {
        val a = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), 0.0)
        val b = Quaternion.fromAxisAngle(Vec3(0.0, 0.0, 1.0), 1.0)
        assertEquals(0.0, Quaternion.slerp(a, b, 0.0).angle(), 1e-12)
        assertEquals(1.0, Quaternion.slerp(a, b, 1.0).angle(), 1e-12)
        assertEquals(0.5, Quaternion.slerp(a, b, 0.5).angle(), 1e-12)

        // Negated quaternion is the same rotation; slerp must not go the long way.
        val bNeg = Quaternion(-b.w, -b.x, -b.y, -b.z)
        assertEquals(0.5, Quaternion.slerp(a, bNeg, 0.5).angle(), 1e-12)
    }

    @Test
    fun `composition is not commutative and matches matrix products`() {
        val qx = Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), 0.4)
        val qy = Quaternion.fromAxisAngle(Vec3(0.0, 1.0, 0.0), 0.6)
        val viaQuat = (qx * qy).toMatrix()
        val viaMat = qx.toMatrix() * qy.toMatrix()
        assertEquals(viaMat.m00, viaQuat.m00, 1e-12)
        assertEquals(viaMat.m12, viaQuat.m12, 1e-12)
        assertEquals(viaMat.m21, viaQuat.m21, 1e-12)
        // The x components of XY and YX happen to coincide for these axes;
        // the ordering shows up in z, so assert on the component that moves.
        assertTrue(abs((qx * qy).z - (qy * qx).z) > 1e-6)
    }
}
