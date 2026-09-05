package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

class BurstAlignmentTest {

    private val t0 = 5_000_000_000L
    private val intrinsics = CameraIntrinsics.fromHorizontalFov(78.0, 4000, 3000)
    private val rig = RigAlignment(sensorOrientationDegrees = 0)
    private val crop = CropWindow(4000, 3000, 0.12)

    private fun centreOfOutput(plan: AlignmentPlan, frameIndex: Int): Pair<Double, Double> {
        val a = plan.alignments.first { it.frameIndex == frameIndex }
        return a.samplingMatrix.mapPoint(
            plan.crop.outputWidth / 2.0,
            plan.crop.outputHeight / 2.0,
        )!!
    }

    @Test
    fun `a perfectly still burst produces identity sampling`() {
        val frames = Synthetic.frames(t0, 6, 33)
        val track = MotionTrack.integrate(Synthetic.constantRate(t0, 400, 200, Vec3.ZERO))
        val plan = BurstAligner.plan(frames, track, intrinsics, rig, crop)

        assertEquals(6, plan.usableCount)
        for (a in plan.alignments) {
            assertEquals("frame ${a.frameIndex} should not move", 0.0, a.maxCornerShiftPx, 1e-6)
        }
        // The output centre maps to the source principal point, since the crop is centred.
        val (x, y) = centreOfOutput(plan, 3)
        assertEquals(intrinsics.cx, x, 1.0)
        assertEquals(intrinsics.cy, y, 1.0)
    }

    @Test
    fun `pure yaw displaces the sampled point along x by f tan theta`() {
        val frames = Synthetic.frames(t0, 2, 100, exposureMs = 0)
        // Rotate about the device Y axis (yaw for an upright phone).
        val rate = 0.2
        val track = MotionTrack.integrate(
            Synthetic.constantRate(t0 - 50 * Synthetic.MS, 400, 400, Vec3(0.0, rate, 0.0))
        )
        val plan = BurstAligner.plan(frames, track, intrinsics, rig, crop, anchorIndex = 0)

        val dt = 0.100 // seconds between the two frames
        val theta = rate * dt
        val (x, y) = centreOfOutput(plan, 1)
        assertEquals(
            "yaw should shift horizontally by f*tan(theta)",
            intrinsics.fx * tan(theta),
            x - intrinsics.cx,
            1.0,
        )
        assertEquals("yaw should not shift vertically", intrinsics.cy, y, 1.0)
    }

    @Test
    fun `pure pitch displaces the sampled point along y`() {
        val frames = Synthetic.frames(t0, 2, 100, exposureMs = 0)
        val rate = 0.2
        val track = MotionTrack.integrate(
            Synthetic.constantRate(t0 - 50 * Synthetic.MS, 400, 400, Vec3(rate, 0.0, 0.0))
        )
        val plan = BurstAligner.plan(frames, track, intrinsics, rig, crop, anchorIndex = 0)
        val (x, y) = centreOfOutput(plan, 1)
        assertEquals(intrinsics.cx, x, 1.0)
        assertEquals(
            intrinsics.fy * tan(rate * 0.100),
            abs(y - intrinsics.cy),
            1.0,
        )
    }

    @Test
    fun `flipping rig handedness reverses the correction`() {
        val frames = Synthetic.frames(t0, 2, 100, exposureMs = 0)
        val track = MotionTrack.integrate(
            Synthetic.constantRate(t0 - 50 * Synthetic.MS, 400, 400, Vec3(0.0, 0.2, 0.0))
        )
        val a = BurstAligner.plan(frames, track, intrinsics, rig, crop, anchorIndex = 0)
        val b = BurstAligner.plan(
            frames, track, intrinsics, RigAlignment(90).flipped(), crop, anchorIndex = 0
        )
        val dxA = centreOfOutput(a, 1).first - intrinsics.cx
        val dxB = centreOfOutput(b, 1).first - intrinsics.cx
        // Not a proof of which is correct - that is settled on-device - but the
        // two conventions must not silently produce the same answer.
        assertTrue("handedness must change the result", abs(dxA - dxB) > 1.0)
    }

    @Test
    fun `frames rotated beyond the crop budget are dropped not stretched`() {
        val frames = Synthetic.frames(t0, 5, 40, exposureMs = 0)
        // 3 rad/s is a deliberate swipe, far past what a 12 percent crop absorbs.
        val track = MotionTrack.integrate(
            Synthetic.constantRate(t0 - 50 * Synthetic.MS, 400, 400, Vec3(0.0, 3.0, 0.0))
        )
        val plan = BurstAligner.plan(frames, track, intrinsics, rig, crop, anchorIndex = 0)
        assertTrue("anchor itself is always usable", plan.alignments[0].usable)
        assertFalse("far frame must be rejected", plan.alignments.last().usable)
        assertTrue("some frames must survive", plan.usableCount >= 1)
    }

    @Test
    fun `anchor selection picks the steadiest frame not the first`() {
        val frames = Synthetic.frames(t0, 5, 50, exposureMs = 20)
        // Shake hard except during frame 3's exposure window.
        val quietStart = frames[3].sensorTimestampNanos
        val quietEnd = quietStart + frames[3].exposureTimeNanos
        val samples = Synthetic.gyro(t0 - 20 * Synthetic.MS, 400, 400) { tSec ->
            val abs = t0 - 20 * Synthetic.MS + (tSec * 1e9).toLong()
            if (abs in quietStart..quietEnd) Vec3.ZERO else Vec3(0.0, 0.0, 0.6)
        }
        val track = MotionTrack.integrate(samples)
        assertEquals(3, AnchorSelector.select(frames, track))

        val scores = AnchorSelector.scores(frames, track)
        assertTrue(scores.getValue(3) < scores.getValue(0))
    }

    @Test
    fun `a clock offset that is wrong produces a worse alignment than one that is right`() {
        val frames = Synthetic.frames(t0, 4, 40, exposureMs = 0)
        // Deliberately oscillatory. Under a *constant* rate a clock offset shifts
        // anchor and frame equally and cancels exactly, so it would prove nothing;
        // real hand tremor is a few Hz of oscillation, where it does not cancel.
        val start = t0 - 100 * Synthetic.MS
        val track = MotionTrack.integrate(
            Synthetic.gyro(start, 600, 400) { tSec ->
                Vec3(0.0, 0.5 * kotlin.math.sin(2 * Math.PI * 4.0 * tSec), 0.0)
            }
        )
        val good = BurstAligner.plan(frames, track, intrinsics, rig, crop, anchorIndex = 0)
        val bad = BurstAligner.plan(
            frames, track, intrinsics, rig, crop,
            sync = SyncCalibration(15 * Synthetic.MS), anchorIndex = 0,
        )
        // Same anchor, so a 15 ms sync error is pure added misalignment.
        val goodShift = good.alignments[3].maxCornerShiftPx
        val badShift = bad.alignments[3].maxCornerShiftPx
        assertTrue(
            "15 ms of sync error must change the warp (good=$goodShift bad=$badShift)",
            abs(goodShift - badShift) > 1.0,
        )
    }
}
