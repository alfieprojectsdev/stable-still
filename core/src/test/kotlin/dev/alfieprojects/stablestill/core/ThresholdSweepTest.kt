package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The threshold trade, as a test rather than as an afternoon with a handset.
 *
 * `docs/SESSION-LOG.md` records the finding this pins down: noise reduction
 * stops improving well before ghosting starts, so every threshold above the
 * knee buys a ghost and nothing else. That was established by replaying one
 * burst fourteen times on the phone and inspecting each result at 1:1. Here the
 * same shape has to hold on a burst whose answer is known by construction, so a
 * change that quietly breaks the trade fails a test instead of surviving until
 * someone next has a phone, a burst and an afternoon.
 */
class ThresholdSweepTest {

    private val width = 64
    private val height = 48
    private val crop = CropWindow(width, height, marginFraction = 0.125)

    /** Background noise plus a subject that only the anchor did not see. */
    private fun burst(): List<YuvFrame> {
        val random = Random(7)
        val background = 120
        val subject = 220
        val anchor = Synthetic.greyFrame(width, height) { _, _ ->
            (background + random.nextGaussian() * 6.0).toInt()
        }
        val rest = (1 until 8).map {
            Synthetic.greyFrame(width, height) { x, y ->
                val base = if (x in 20..40 && y in 15..30) subject else background
                (base + random.nextGaussian() * 6.0).toInt()
            }
        }
        return listOf(anchor) + rest
    }

    @Test
    fun `noise reduction finishes long before ghosting starts`() {
        val frames = burst()
        val plan = Synthetic.translationPlan(crop, anchorIndex = 0, shifts = List(8) { 0.0 to 0.0 })
        val sigmas = listOf(0.05f, 0.10f, 0.15f, 0.30f, 0.60f, 1.00f)
        val points = ThresholdSweep.sweep(plan, sigmas, noiseStride = 8) { frames[it] }
        val by = sigmas.zip(points).toMap()

        // Admitting more frames can only ever add weight.
        for (i in 1 until points.size) {
            assertTrue(
                "Effective frames fell from ${points[i - 1].effectiveFrameCount} " +
                    "to ${points[i].effectiveFrameCount}",
                points[i].effectiveFrameCount >= points[i - 1].effectiveFrameCount - 1e-6,
            )
        }

        // A threshold too tight to admit the frame's own noise rejects genuine
        // agreement, which is the bug deriving sigma from noise exists to avoid.
        assertTrue(
            "Tight threshold should stack poorly, got ${by[0.05f]!!.effectiveFrameCount}",
            by[0.05f]!!.effectiveFrameCount < 5.0,
        )
        // Not 8, and it should not be: a fifth of this frame is subject, where
        // only the anchor may contribute. The ceiling on a burst with something
        // moving in it is set by how much of the frame moved.
        assertTrue(
            "By the measured ceiling the static background should stack, got " +
                "${by[0.15f]!!.effectiveFrameCount}",
            by[0.15f]!!.effectiveFrameCount > 5.5,
        )

        // The knee: past it, residual noise stops improving.
        val atCeiling = by[0.15f]!!.residualNoise
        val wideOpen = by[1.00f]!!.residualNoise
        assertTrue(
            "Noise should improve from 0.05 to 0.15: " +
                "${by[0.05f]!!.residualNoise} then $atCeiling",
            atCeiling < by[0.05f]!!.residualNoise * 0.9,
        )
        assertTrue(
            "Past the knee noise should be flat: $atCeiling then $wideOpen",
            kotlin.math.abs(wideOpen - atCeiling) < atCeiling * 0.25,
        )

        // The cost, meanwhile, keeps climbing - which is the whole argument for
        // a ceiling that measurement sets rather than optimism.
        assertTrue(
            "The subject must not ghost at the measured ceiling, " +
                "got ${by[0.15f]!!.ghostLevels} levels",
            by[0.15f]!!.ghostLevels < 2.0,
        )
        assertTrue(
            "Wide open, the subject must ghost badly, got ${by[1.00f]!!.ghostLevels} levels",
            by[1.00f]!!.ghostLevels > 50.0,
        )
        for (i in 1 until points.size) {
            assertTrue(
                "Ghosting fell as the threshold rose: ${points[i - 1].ghostLevels} " +
                    "then ${points[i].ghostLevels}",
                points[i].ghostLevels >= points[i - 1].ghostLevels - 0.5,
            )
        }
    }

    @Test
    fun `a static burst has no moving pixels to ghost`() {
        // The control. Same scene in every frame, so the disagreement pass finds
        // nothing above the motion threshold and the sweep reports no ghost at
        // any threshold - including the ones that ruin a burst with a subject in
        // it. A metric that cannot tell those two bursts apart is the metric the
        // 8 September session was misled by.
        val random = Random(11)
        val frames = (0 until 6).map {
            Synthetic.greyFrame(width, height) { x, y ->
                ((100 + 30 * ((x / 8 + y / 8) % 2)) + random.nextGaussian() * 5.0).toInt()
            }
        }
        val plan = Synthetic.translationPlan(crop, 0, List(6) { 0.0 to 0.0 })
        val points = ThresholdSweep.sweep(plan, listOf(0.15f, 1.00f), noiseStride = 8) { frames[it] }

        assertTrue("A static burst reported a ghost", points.all { it.ghostLevels == 0.0 })
        assertTrue(
            "A static burst should stack nearly every frame at the ceiling",
            points[0].effectiveFrameCount > 5.0,
        )
    }

    @Test
    fun `a real burst sweeps without a phone`() {
        // -Dstablestill.burstDir=<burst with frames> prints the trade for that
        // burst, gyro-only and then refined, so the knee and the ghost can be
        // read side by side. Nothing asserted beyond the table existing: which
        // way the numbers go is the burst's business, and the point of running
        // it is to find out.
        val dir = System.getProperty(BurstReplayTest.BURST_DIR_PROPERTY)
        assumeTrue("Set -D${BurstReplayTest.BURST_DIR_PROPERTY} to sweep a real burst", dir != null)
        val burst = BurstReader.read(File(dir!!))
        val byIndex = burst.frames.associateBy { it.index }
        val frames = HashMap<Int, YuvFrame>()
        val frameAt = { i: Int -> frames.getOrPut(i) { BurstReader.readFrame(File(dir), byIndex.getValue(i)) } }

        val gyroPlan = BurstAligner.plan(
            burst.frames.map { it.toMeta() },
            MotionTrack.integrate(burst.gyro),
            burst.manifest.intrinsics,
            burst.manifest.replayRig,
            CropWindow(burst.manifest.width, burst.manifest.height, 0.12),
        )
        val refined = OpticalRefinement.refinePlan(gyroPlan) { i -> frameAt(i).luma() }
        val sigmas = listOf(0.03f, 0.06f, 0.09f, 0.12f, 0.15f, 0.20f, 0.25f, 0.30f, 0.40f, 0.60f)

        for ((label, plan) in listOf("gyro-only" to gyroPlan, "refined" to refined.plan)) {
            val table = ThresholdSweep.format(ThresholdSweep.sweep(plan, sigmas, frameAt = frameAt))
            println("${File(dir).name} $label (anchor ${plan.anchorIndex}, ${plan.usableCount}/${plan.alignments.size} usable):\n$table")
            assertTrue(table.isNotBlank())
        }
    }
}
