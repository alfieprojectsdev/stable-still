package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deciding the rig's handedness from pixels.
 *
 * The constant in the code is derived, not confirmed on hardware, and being
 * wrong inverts the correction so that stabilisation makes shake worse. These
 * bursts are rendered *through* a stated handedness, so there is a right answer
 * to find rather than a plausible one to settle for.
 */
class RigCalibrationTest {

    private val width = 512
    private val height = 384
    private val crop = CropWindow(width, height, marginFraction = 0.12)
    private val intrinsics = CameraIntrinsics(
        fx = 520.0, fy = 520.0, cx = 256.0, cy = 192.0, width = width, height = height,
    )
    private val start = 1_000_000_000L

    /** Texture at several scales, so the residual has something to bite on. */
    private fun scene(x: Double, y: Double): Double =
        128.0 + 30.0 * sin(2 * PI * x / 91.0) +
            25.0 * sin(2 * PI * y / 67.0) +
            20.0 * sin(2 * PI * x / 19.0) +
            15.0 * sin(2 * PI * y / 23.0)

    private fun frames() = Synthetic.frames(
        startNanos = start, count = 6, intervalMs = 50, exposureMs = 10,
        width = width, height = height,
    )

    /**
     * Pitch and yaw, oscillating.
     *
     * Oscillatory on purpose - a constant rate is the profile under which a
     * common timing offset cancels exactly, so a test built on it proves less
     * than it appears to. Pitch and yaw rather than roll for a reason specific
     * to this test: roll commutes with the sensor-orientation rotation that
     * handedness flips, so a rolling burst gives both signs the same answer.
     */
    private fun tiltingGyro() = Synthetic.gyro(start, 300, 400) { t ->
        Vec3(0.9 * sin(2 * PI * 2.0 * t), 0.6 * sin(2 * PI * 1.5 * t + 1.0), 0.0)
    }

    /** Roll alone: the burst that cannot decide anything. */
    private fun rollingGyro() = Synthetic.gyro(start, 300, 400) { t ->
        Vec3(0.0, 0.0, 0.9 * sin(2 * PI * 2.0 * t))
    }

    /**
     * Renders a burst as a camera obeying [trueRig] would actually have seen it.
     *
     * Frame f shows the scene at `H_f^-1 . u`, which is the exact inverse of the
     * homography [BurstAligner] builds, so aligning with the same handedness
     * recovers the scene and aligning with the other does not.
     */
    private fun render(
        trueRig: RigAlignment,
        metas: List<FrameMeta>,
        track: MotionTrack,
        anchorIndex: Int,
    ): List<LumaPlane> {
        val anchorTime = metas.first { it.index == anchorIndex }.midExposureNanos
        return metas.map { meta ->
            val rotation = track.rotationBetween(anchorTime, meta.midExposureNanos)
            val h = intrinsics.matrix * trueRig.toCameraFrame(rotation) * intrinsics.inverse
            val inverse = h.inverse()
            Synthetic.greyFrame(width, height) { x, y ->
                val p = inverse.mapPoint(x + 0.5, y + 0.5)
                if (p == null) 0 else scene(p.first - 0.5, p.second - 0.5).roundToInt()
            }.luma()
        }
    }

    private fun settle(trueHandedness: Int, gyro: List<GyroSample>): HandednessVerdict {
        val metas = frames()
        val track = MotionTrack.integrate(gyro)
        val trueRig = RigAlignment(
            sensorOrientationDegrees = 90, frontFacing = false, handedness = trueHandedness,
        )
        val anchorIndex = AnchorSelector.select(metas, track)
        val planes = render(trueRig, metas, track, anchorIndex)
        return RigCalibration.settleHandedness(
            frames = metas,
            track = track,
            intrinsics = intrinsics,
            // Deliberately seeded with +1 every time: the answer must come from
            // the pixels, not from what it was handed.
            rig = RigAlignment(sensorOrientationDegrees = 90, handedness = 1),
            crop = crop,
        ) { planes[it] }
    }

    @Test
    fun `a burst rendered with positive handedness chooses positive`() {
        val verdict = settle(trueHandedness = 1, gyro = tiltingGyro())
        assertEquals(1, verdict.chosen.handedness)
        assertTrue(RigCalibration.format(verdict), verdict.decisive)
    }

    @Test
    fun `a burst rendered with negative handedness chooses negative`() {
        // The one that matters. Seeded with +1 and told otherwise by the pixels.
        val verdict = settle(trueHandedness = -1, gyro = tiltingGyro())
        assertEquals(-1, verdict.chosen.handedness)
        assertTrue(RigCalibration.format(verdict), verdict.decisive)
    }

    @Test
    fun `the wrong sign is not a close call`() {
        val verdict = settle(trueHandedness = 1, gyro = tiltingGyro())
        // A wrong sign does not fail to remove the motion, it applies it the
        // wrong way and roughly doubles it, so the gap should be enormous
        // rather than marginal. If this ever narrows, the burst rotated too
        // little to be calibrating anything.
        assertTrue(
            "Margin was only ${verdict.margin}",
            verdict.margin > 1.0,
        )
        assertTrue(
            "Corners only ${verdict.separationPx} px apart",
            verdict.separationPx > 10.0,
        )
    }

    @Test
    fun `a burst that only rolls admits it cannot decide`() {
        // Rotations about the optical axis commute with the sensor-orientation
        // rotation that handedness flips, so both hypotheses produce identical
        // homographies. The useful behaviour is to say so: a verdict here would
        // be a coin toss reported as a measurement, and it would be recorded in
        // the manifest of every burst captured afterwards.
        val verdict = settle(trueHandedness = 1, gyro = rollingGyro())
        assertFalse(RigCalibration.format(verdict), verdict.decisive)
        assertTrue(
            "Roll should leave the hypotheses on top of each other, " +
                "got ${verdict.separationPx} px",
            verdict.separationPx < RigCalibration.MIN_SEPARATION_PX,
        )
        assertTrue(RigCalibration.format(verdict).contains("not roll"))
    }

    @Test
    fun `a motionless burst cannot decide either`() {
        val verdict = settle(trueHandedness = 1, gyro = Synthetic.constantRate(
            start, 300, 400, Vec3.ZERO,
        ))
        assertFalse(verdict.decisive)
    }
}
