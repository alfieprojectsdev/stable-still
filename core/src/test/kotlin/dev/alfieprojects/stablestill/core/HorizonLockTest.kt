package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HorizonLockTest {

    private val rig = RigAlignment(sensorOrientationDegrees = 0)

    @Test
    fun `a level phone needs no correction`() {
        // Held upright: gravity points along device -Y.
        val g = Vec3(0.0, -9.81, 0.0)
        assertEquals(0.0, abs(HorizonLock.tiltRadians(g, rig)), 1e-9)
        val c = HorizonLock.correction(g, rig)
        assertEquals(1.0, c.m00, 1e-9)
        assertEquals(0.0, c.m01, 1e-9)
    }

    @Test
    fun `a tilted phone produces a correction of the matching size`() {
        val tilt = 0.08 // radians
        val g = Vec3(-9.81 * sin(tilt), -9.81 * cos(tilt), 0.0)
        assertEquals(tilt, abs(HorizonLock.tiltRadians(g, rig)), 1e-6)

        val c = HorizonLock.correction(g, rig)
        // A rotation matrix about Z: off-diagonal magnitude is sin(angle).
        assertEquals(tilt, abs(kotlin.math.asin(c.m10.coerceIn(-1.0, 1.0))), 1e-6)
    }

    @Test
    fun `correction is clamped so it cannot eat the whole crop budget`() {
        val tilt = 0.9 // phone held nearly sideways
        val g = Vec3(-9.81 * sin(tilt), -9.81 * cos(tilt), 0.0)
        val c = HorizonLock.correction(g, rig, maxCorrectionRadians = 0.12)
        val applied = abs(kotlin.math.asin(c.m10.coerceIn(-1.0, 1.0)))
        assertTrue("applied $applied should be clamped to 0.12", applied <= 0.12 + 1e-9)
        assertEquals(0.12, applied, 1e-9)
    }

    @Test
    fun `pointing straight down is a no-op rather than a divide by zero`() {
        val g = Vec3(0.0, 0.0, 9.81) // camera at the ceiling, gravity along the optical axis
        val c = HorizonLock.correction(g, rig)
        assertEquals(Mat3.IDENTITY, c)
    }

    @Test
    fun `sensor orientation is carried into the tilt`() {
        val g = Vec3(0.0, -9.81, 0.0)
        val rotated = RigAlignment(sensorOrientationDegrees = 90)
        assertEquals(PI / 2, abs(HorizonLock.tiltRadians(g, rotated)), 1e-6)
    }
}
