package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinearAlgebraTest {

    @Test
    fun `inverse times original is identity`() {
        val m = Mat3(2.0, 0.3, -1.0, 0.1, 1.5, 0.4, 0.0, -0.2, 3.0)
        val i = m * m.inverse()
        assertEquals(1.0, i.m00, 1e-12)
        assertEquals(1.0, i.m11, 1e-12)
        assertEquals(1.0, i.m22, 1e-12)
        assertEquals(0.0, i.m01, 1e-12)
        assertEquals(0.0, i.m20, 1e-12)
    }

    @Test
    fun `intrinsics inverse matches the general matrix inverse`() {
        val k = CameraIntrinsics(1800.0, 1805.0, 1520.0, 1140.0, 3040, 2280)
        val diff = k.matrix * k.inverse
        assertEquals(1.0, diff.m00, 1e-9)
        assertEquals(1.0, diff.m11, 1e-9)
        assertEquals(0.0, diff.m02, 1e-9)
        assertEquals(0.0, diff.m12, 1e-9)
    }

    @Test
    fun `mapPoint applies the perspective divide`() {
        // Scale by 2 about the origin, then translate.
        val h = Mat3(2.0, 0.0, 10.0, 0.0, 2.0, 20.0, 0.0, 0.0, 1.0)
        val (x, y) = h.mapPoint(5.0, 7.0)!!
        assertEquals(20.0, x, 1e-12)
        assertEquals(34.0, y, 1e-12)
    }

    @Test
    fun `mapPoint returns null on the plane at infinity`() {
        val h = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
        assertNull(h.mapPoint(0.0, 5.0))
    }

    @Test
    fun `GL layout is column major`() {
        val m = Mat3(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val gl = m.toGlColumnMajor()
        // First three floats are the first *column*: m00, m10, m20.
        assertEquals(1.0f, gl[0], 0f)
        assertEquals(4.0f, gl[1], 0f)
        assertEquals(7.0f, gl[2], 0f)
        assertEquals(2.0f, gl[3], 0f)
    }
}
