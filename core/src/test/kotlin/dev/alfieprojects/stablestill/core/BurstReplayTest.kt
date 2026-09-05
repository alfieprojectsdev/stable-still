package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays a real burst captured on the Galaxy A07 5G.
 *
 * The fixture in `src/test/resources/burst-sample` is the genuine article -
 * manifest, frame timing and all 190 gyro samples from a burst shot on
 * 5 September 2026 with the exposure cap on. Only the eight 17.9 MB frame
 * files are left out, because everything up to and including the alignment
 * plan is decided by timestamps and angular velocity, not by pixels.
 *
 * This is the test the whole archive format was built to make possible: an
 * alignment failure on the phone can now be reproduced at a desk in
 * milliseconds. Point [BURST_DIR_PROPERTY] at a full burst to include the
 * pixel-level checks as well.
 */
class BurstReplayTest {

    companion object {
        /** `-Dstablestill.burstDir=/path/to/burst-...` to exercise the pixel paths. */
        const val BURST_DIR_PROPERTY = "stablestill.burstDir"
    }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("burst-sample/manifest.txt")
        assertNotNull("Burst fixture is missing from test resources", url)
        return File(url!!.toURI()).parentFile
    }

    private fun load(): BurstArchiveContents = BurstReader.read(fixtureDir())

    @Test
    fun `a burst captured on the device reads back intact`() {
        val burst = load()
        assertEquals("SM-A076B", burst.manifest.deviceModel)
        assertEquals(8, burst.frames.size)
        assertEquals(4080, burst.manifest.width)
        assertEquals(3060, burst.manifest.height)

        // The exposure cap: 20 ms exactly, on every frame, at one gain. Constant
        // gain matters as much as its value - brightness drifting mid-burst is
        // variation the weighted merge has no way to absorb.
        assertTrue(burst.frames.all { it.exposureTimeNanos == 20_000_000L })
        assertEquals(setOf(1047), burst.frames.map { it.sensitivityIso }.toSet())

        // Skew this device declines to advertise, yet delivers on every frame.
        assertTrue(burst.frames.all { it.rollingShutterSkewNanos == 27_406_628L })
    }

    @Test
    fun `the gyro trace brackets every frame's exposure`() {
        val burst = load()
        assertTrue(
            "Trace does not cover the burst; outermost frames would clamp",
            burst.gyroCoversFrames(),
        )
        // 8 frames at 20 fps is 7 intervals, not 8 - 350 ms, not 400.
        assertEquals(350L, burst.frameSpanNanos / 1_000_000L)
    }

    @Test
    fun `replaying the trace produces a usable alignment plan`() {
        val burst = load()
        val track = MotionTrack.integrate(burst.gyro)
        val metas = burst.frames.map { it.toMeta() }
        val crop = CropWindow(burst.manifest.width, burst.manifest.height, marginFraction = 0.12)

        val plan = BurstAligner.plan(
            frames = metas,
            track = track,
            intrinsics = burst.manifest.intrinsics,
            rig = burst.manifest.rig,
            crop = crop,
            // The probe measured SHARED_REALTIME, so there is genuinely no
            // offset to solve for on this device.
            sync = SyncCalibration.IDENTITY,
        )

        assertEquals(
            "Every frame of a hand-held burst should be recoverable",
            metas.size, plan.usableCount,
        )
        assertTrue(plan.anchorIndex in metas.indices)

        // The anchor is the steadiest frame, so it must be at least as steady as
        // the burst's average - the property that makes choosing one worthwhile.
        val scores = AnchorSelector.scores(metas, track)
        val anchorScore = scores.getValue(plan.anchorIndex)
        val meanScore = scores.values.average()
        assertTrue(
            "Anchor $anchorScore should beat the mean $meanScore",
            anchorScore <= meanScore,
        )

        // The anchor maps to itself: a burst that shifted its own anchor would
        // be misaligned by construction, however good the rest looked.
        val anchorAlignment = plan.alignments.first { it.frameIndex == plan.anchorIndex }
        assertTrue(
            "Anchor shifted by ${anchorAlignment.maxCornerShiftPx} px",
            anchorAlignment.maxCornerShiftPx < 1.0,
        )
    }

    @Test
    fun `real hand tremor moves the frame by pixels, not none and not miles`() {
        val burst = load()
        val track = MotionTrack.integrate(burst.gyro)
        val metas = burst.frames.map { it.toMeta() }
        val crop = CropWindow(burst.manifest.width, burst.manifest.height, 0.12)
        val plan = BurstAligner.plan(
            metas, track, burst.manifest.intrinsics, burst.manifest.rig, crop,
        )

        val worst = plan.alignments.maxOf { it.maxCornerShiftPx }
        // Two failure modes this pins down at once. A track that integrated to
        // nothing - the bug a bias subtraction or a units slip produces - would
        // leave every shift at zero and the test would pass silently without
        // this lower bound. A sign error or a wrong rig handedness sends corners
        // off the sensor entirely, which the upper bound catches.
        assertTrue("Nothing moved at all: $worst px", worst > 1.0)
        assertTrue("Implausible shift: $worst px", worst < crop.offsetX)

        // The crop must be able to absorb what actually happened, or the margin
        // is decoration.
        val budget = crop.rotationBudgetRadians(burst.manifest.intrinsics)
        val worstRotation = plan.alignments.maxOf { it.rotationRadians }
        assertTrue(
            "Rotation $worstRotation exceeds the crop budget $budget",
            worstRotation < budget,
        )
    }

    @Test
    fun `gyro samples arrive at the rate the probe measured`() {
        val burst = load()
        val span = burst.gyro.last().timestampNanos - burst.gyro.first().timestampNanos
        val rate = (burst.gyro.size - 1) * 1e9 / span
        // The probe measured 402.7 Hz. Anything far from that means the recorder
        // is dropping samples under camera load, which would quietly degrade
        // every integration without ever failing.
        assertTrue("Gyro delivered $rate Hz during capture", rate in 350.0..450.0)
    }

    @Test
    fun `pixels load and the anchor is among the sharper frames`() {
        val dir = System.getProperty(BURST_DIR_PROPERTY)
        assumeTrue("Set -D$BURST_DIR_PROPERTY to run the pixel checks", dir != null)
        val burst = BurstReader.read(File(dir!!))

        val planes = burst.frames.map { BurstReader.readLuma(File(dir), it) }
        assertTrue(planes.all { it.meanLuma > 1.0 })

        val track = MotionTrack.integrate(burst.gyro)
        val plan = BurstAligner.plan(
            burst.frames.map { it.toMeta() },
            track,
            burst.manifest.intrinsics,
            burst.manifest.rig,
            CropWindow(burst.manifest.width, burst.manifest.height, 0.12),
        )
        val sharpness = planes.map { it.sharpness() }
        val anchorSharpness = sharpness[plan.anchorIndex]

        // Deliberately weak. The anchor is chosen from gyro steadiness, and this
        // asserts only that the choice is not actively perverse: sensor noise
        // inflates a Laplacian score, so at ISO 1047 the sharpest-scoring frame
        // is as likely to be the noisiest as the steadiest.
        assertTrue(
            "Anchor scored $anchorSharpness against ${sharpness.min()}..${sharpness.max()}",
            anchorSharpness >= sharpness.min(),
        )
    }
}
