package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MotionTrackTest {

    private val t0 = 1_000_000_000L

    @Test
    fun `constant rate integrates to rate times time`() {
        val rate = 0.4 // rad/s
        val samples = Synthetic.constantRate(t0, 500, 200, Vec3(0.0, 0.0, rate))
        val track = MotionTrack.integrate(samples)
        val angle = track.rotationBetween(track.startNanos, track.endNanos).angle()
        val expected = rate * (track.endNanos - track.startNanos) * 1e-9
        // Rotations about a fixed axis compose exactly, so this is tight.
        assertEquals(expected, angle, 1e-9)
    }

    @Test
    fun `rotationBetween is antisymmetric`() {
        val samples = Synthetic.constantRate(t0, 300, 200, Vec3(0.1, -0.2, 0.3))
        val track = MotionTrack.integrate(samples)
        val a = t0 + 50 * Synthetic.MS
        val b = t0 + 250 * Synthetic.MS
        val forward = track.rotationBetween(a, b)
        val backward = track.rotationBetween(b, a)
        val round = (backward * forward).normalized()
        assertEquals(1.0, kotlin.math.abs(round.w), 1e-12)
    }

    @Test
    fun `rotationBetween composes across an intermediate time`() {
        val samples = Synthetic.gyro(t0, 400, 200) { t ->
            Vec3(0.3 * sin(2 * PI * 3 * t), 0.2, -0.1 * t)
        }
        val track = MotionTrack.integrate(samples)
        val a = t0 + 20 * Synthetic.MS
        val m = t0 + 180 * Synthetic.MS
        val b = t0 + 350 * Synthetic.MS
        val direct = track.rotationBetween(a, b)
        val chained = (track.rotationBetween(m, b) * track.rotationBetween(a, m)).normalized()
        assertEquals(direct.w, chained.w, 1e-9)
        assertEquals(direct.x, chained.x, 1e-9)
        assertEquals(direct.y, chained.y, 1e-9)
        assertEquals(direct.z, chained.z, 1e-9)
    }

    @Test
    fun `orientation clamps instead of extrapolating outside the track`() {
        val samples = Synthetic.constantRate(t0, 200, 200, Vec3(0.0, 0.0, 1.0))
        val track = MotionTrack.integrate(samples)
        assertFalse(track.covers(t0 - 1))
        assertFalse(track.covers(track.endNanos + 1))
        val far = track.orientationAt(track.endNanos + 10_000 * Synthetic.MS)
        val edge = track.orientationAt(track.endNanos)
        assertEquals(edge.angle(), far.angle(), 1e-12)
    }

    @Test
    fun `interpolation lands between bracketing samples`() {
        val samples = Synthetic.constantRate(t0, 100, 100, Vec3(0.0, 0.0, 1.0))
        val track = MotionTrack.integrate(samples)
        // Halfway through a 10 ms gap at 1 rad/s is 5 mrad past the earlier sample.
        val mid = t0 + 25 * Synthetic.MS
        val angle = track.rotationBetween(t0, mid).angle()
        assertEquals(0.025, angle, 1e-6)
    }

    @Test
    fun `bias is subtracted before integration`() {
        val bias = Vec3(0.0, 0.0, 0.02)
        val samples = Synthetic.constantRate(t0, 1000, 200, bias)
        val estimated = GyroBias.estimate(samples)
        assertEquals(0.02, estimated.value.z, 1e-12)
        val track = MotionTrack.integrate(samples, estimated)
        assertEquals(0.0, track.rotationBetween(track.startNanos, track.endNanos).angle(), 1e-9)
    }

    @Test
    fun `bias estimation refuses a window that is actually moving`() {
        val samples = Synthetic.constantRate(t0, 200, 200, Vec3(0.0, 0.0, 0.9))
        assertEquals(Vec3.ZERO, GyroBias.estimate(samples).value)
    }

    @Test
    fun `mean angular speed recovers the rate that generated it`() {
        val samples = Synthetic.constantRate(t0, 300, 200, Vec3(0.0, 0.5, 0.0))
        val track = MotionTrack.integrate(samples)
        val speed = track.meanAngularSpeed(t0 + 10 * Synthetic.MS, t0 + 200 * Synthetic.MS)
        assertEquals(0.5, speed, 1e-6)
    }

    @Test
    fun `empty and single sample tracks degrade gracefully`() {
        val empty = MotionTrack.integrate(emptyList())
        assertEquals(0, empty.sampleCount)
        assertEquals(Quaternion.IDENTITY, empty.orientationAt(t0))
        assertFalse(empty.covers(t0))

        val single = MotionTrack.integrate(listOf(GyroSample(t0, Vec3(1.0, 1.0, 1.0))))
        assertEquals(1, single.sampleCount)
        assertTrue(single.covers(t0))
        assertEquals(0.0, single.rotationBetween(t0, t0).angle(), 1e-12)
    }
}
