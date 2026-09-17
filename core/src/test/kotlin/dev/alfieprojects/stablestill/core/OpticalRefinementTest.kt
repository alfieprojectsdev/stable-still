package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Refinement, against misalignments whose true value is known.
 *
 * The case this exists for cannot be tested from a gyro trace, because the
 * whole point is the motion a gyro cannot see. So the bursts here are built by
 * displacing a scene by a stated number of pixels and asking the refiner to
 * name it back.
 */
class OpticalRefinementTest {

    // Large enough that the pyramid reaches its default depth. That is not test
    // convenience: how far a refiner can reach is set by how coarse its coarsest
    // level gets, and a 192 px grid runs out of halvings at three levels, which
    // is one short of what the displacements below need.
    private val width = 1024
    private val height = 768
    private val crop = CropWindow(width, height, marginFraction = 0.125)

    /**
     * Texture in both directions and at several scales, which is what makes a
     * translation observable. Periods sharing no common factor, so the match has
     * one minimum rather than a lattice of them.
     *
     * The long wavelengths are not padding. A gradient step can only reach about
     * a quarter of a period before it is walking towards the wrong minimum, and
     * a pyramid level only helps while its texture survives the halving - so the
     * displacement a refiner can recover is set by the *coarsest* structure in
     * the scene, not the finest. A scene of nothing but 17 px detail cannot have
     * an 11 px shift taken out of it by anyone. Real scenes, and pages of text
     * especially, carry both.
     */
    private fun scene(x: Double, y: Double): Double =
        128.0 + 30.0 * sin(2 * PI * x / 97.0) +
            25.0 * sin(2 * PI * y / 61.0) +
            20.0 * sin(2 * PI * x / 17.0) +
            15.0 * sin(2 * PI * y / 13.0) +
            12.0 * sin(2 * PI * (x + y) / 29.0)

    /** The scene displaced by [dx], [dy] pixels - the motion to be recovered. */
    private fun shifted(dx: Double, dy: Double): LumaPlane =
        Synthetic.greyFrame(width, height) { x, y ->
            scene(x - dx, y - dy).roundToInt()
        }.luma()

    private fun planTranslation(dx: Double, dy: Double) = Mat3(
        1.0, 0.0, crop.offsetX + dx,
        0.0, 1.0, crop.offsetY + dy,
        0.0, 0.0, 1.0,
    )

    private fun refineAgainstAnchor(dx: Double, dy: Double): RefinementResult {
        val anchor = shifted(0.0, 0.0)
        val frame = shifted(dx, dy)
        return OpticalRefinement.refine(
            frameIndex = 1,
            anchor = anchor,
            anchorSampling = planTranslation(0.0, 0.0),
            frame = frame,
            // The gyro's answer: no correction at all, because this displacement
            // is translation and a gyroscope cannot see translation.
            frameSampling = planTranslation(0.0, 0.0),
            crop = crop,
        )
    }

    @Test
    fun `a sub-pixel displacement is named back to a hundredth of a pixel`() {
        val result = refineAgainstAnchor(0.37, -0.62)
        assertEquals(0.37, result.translationX, 0.05)
        assertEquals(-0.62, result.translationY, 0.05)
        assertTrue("Residual grew: ${result.improvement}", result.improvement > 1.0)
    }

    @Test
    fun `a displacement of several pixels is recovered through the pyramid`() {
        val result = refineAgainstAnchor(3.4, -2.1)
        assertEquals(3.4, result.translationX, 0.1)
        assertEquals(-2.1, result.translationY, 0.1)
        assertTrue(result.converged)
    }

    @Test
    fun `the residual a page at arm's length would leave is absorbed`() {
        // 10-30 px is what docs/HANDOVER.md predicts gyro-only alignment leaves
        // on a page at 30 cm - unnoticeable on a landscape, fatal on 8-point
        // type. It is far outside what a single gradient step can reach, so this
        // is the test the pyramid exists to pass, across the whole predicted range.
        for ((dx, dy) in listOf(11.0 to 7.0, 20.0 to -14.0, 30.0 to 18.0)) {
            val result = refineAgainstAnchor(dx, dy)
            assertEquals("x of ($dx, $dy)", dx, result.translationX, 0.2)
            assertEquals("y of ($dx, $dy)", dy, result.translationY, 0.2)
            assertTrue(
                "Residual should collapse, went ${result.residualBefore} " +
                    "to ${result.residualAfter}",
                result.improvement > 5.0,
            )
        }
    }

    @Test
    fun `the pyramid's depth is what reaches that far, not the iteration count`() {
        // Teeth for the test above, and a guard on the default. Two levels is a
        // reach of a few pixels however long it iterates: it converges, reports
        // convergence, and settles a whole period of the scene's finest texture
        // away from the truth. A refiner that is confidently wrong is worse than
        // one that declines, so the depth that makes it right is not a number to
        // trim later without measuring.
        val shallow = OpticalRefinement.refine(
            frameIndex = 1,
            anchor = shifted(0.0, 0.0),
            anchorSampling = planTranslation(0.0, 0.0),
            frame = shifted(11.0, 7.0),
            frameSampling = planTranslation(0.0, 0.0),
            crop = crop,
            options = RefinementOptions(levels = 2, iterationsPerLevel = 60),
        )
        assertTrue(
            "Two levels should not reach 11 px, but got ${shallow.translationX}",
            kotlin.math.abs(shallow.translationX - 11.0) > 1.0,
        )
    }

    @Test
    fun `an aligned frame is left alone`() {
        val result = refineAgainstAnchor(0.0, 0.0)
        assertEquals(0.0, result.translationX, 0.02)
        assertEquals(0.0, result.translationY, 0.02)
    }

    @Test
    fun `a scene with no texture refuses to invent a correction`() {
        // A blank wall. The normal equations are singular, and a refiner that
        // solves them anyway returns whatever the noise looked like - a
        // correction with no evidence behind it, applied to every pixel.
        val flat = Synthetic.greyFrame(width, height) { _, _ -> 130 }.luma()
        val result = OpticalRefinement.refine(
            frameIndex = 1,
            anchor = flat,
            anchorSampling = planTranslation(0.0, 0.0),
            frame = flat,
            frameSampling = planTranslation(0.0, 0.0),
            crop = crop,
        )
        assertEquals(0.0, result.shiftPx, 1e-9)
    }

    @Test
    fun `a texture running one way only leaves the other way alone`() {
        // Horizontal stripes: vertical displacement is observable, horizontal is
        // not. The aperture problem, and the honest answer is to correct what
        // can be seen without guessing at what cannot.
        //
        // Two periods, both varying only in y, so the ambiguity under test is
        // purely directional. A single sine would add a second ambiguity that
        // has nothing to do with the aperture problem and no refiner can resolve:
        // a perfectly periodic signal matches itself one whole stripe over, and
        // matches exactly, so the wrong answer is indistinguishable from the
        // right one by any residual.
        val stripes = { dy: Double ->
            Synthetic.greyFrame(width, height) { _, y ->
                (128.0 + 40.0 * sin(2 * PI * (y - dy) / 19.0) +
                    30.0 * sin(2 * PI * (y - dy) / 83.0)).roundToInt()
            }.luma()
        }
        val result = OpticalRefinement.refine(
            frameIndex = 1,
            anchor = stripes(0.0),
            anchorSampling = planTranslation(0.0, 0.0),
            frame = stripes(2.0),
            frameSampling = planTranslation(0.0, 0.0),
            crop = crop,
        )
        assertEquals("The observable direction should be corrected", 2.0, result.translationY, 0.15)
        assertTrue(
            "Nothing constrains x, so it should not wander: ${result.translationX}",
            kotlin.math.abs(result.translationX) < 0.5,
        )
    }

    @Test
    fun `refining a plan corrects every frame and withdraws none`() {
        val offsets = listOf(0.0 to 0.0, 2.0 to 1.0, -3.0 to 2.5, 1.5 to -4.0)
        val frames = offsets.map { (dx, dy) -> shifted(dx, dy) }
        val plan = Synthetic.translationPlan(crop, anchorIndex = 0, shifts = List(4) { 0.0 to 0.0 })

        val refined = OpticalRefinement.refinePlan(plan) { frames[it] }

        assertEquals(4, refined.plan.usableCount)
        for (i in 1..3) {
            val result = refined.results.getValue(i)
            assertEquals("frame $i x", offsets[i].first, result.translationX, 0.15)
            assertEquals("frame $i y", offsets[i].second, result.translationY, 0.15)
        }
        // The anchor is not refined against itself.
        assertTrue(0 !in refined.results)
    }

    @Test
    fun `refinement raises the threshold's ceiling, not just the sharpness`() {
        // The claim in docs/HANDOVER.md: the rejection threshold's ceiling is set
        // by alignment residual rather than by gain, so reducing the residual
        // buys back stacking that the ceiling was forgoing.
        //
        // Here it is, measured. Four frames of one static scene, displaced by a
        // few pixels each. Nothing in the scene moved, so at a correct alignment
        // every frame should contribute everywhere - and before refinement they
        // largely do not, because misalignment reads as disagreement and the
        // merge rejects disagreement.
        val offsets = listOf(0.0 to 0.0, 5.0 to 3.0, -6.0 to 4.0, 3.0 to -7.0)
        val yuv = offsets.map { (dx, dy) ->
            Synthetic.greyFrame(width, height) { x, y -> scene(x - dx, y - dy).roundToInt() }
        }
        val plan = Synthetic.translationPlan(crop, 0, List(4) { 0.0 to 0.0 })
        val sigma = NoiseModel.MAX_SIGMA

        val before = ReferenceMerge.merge(plan, sigma) { yuv[it] }
        val refined = OpticalRefinement.refinePlan(plan) { yuv[it].luma() }
        val after = ReferenceMerge.merge(refined.plan, sigma) { yuv[it] }

        assertTrue(
            "Misalignment should cost stacking, got ${before.effectiveFrameCount} of 4",
            before.effectiveFrameCount < 2.5,
        )
        assertTrue(
            "Refinement should buy it back, got ${after.effectiveFrameCount} of 4",
            after.effectiveFrameCount > 3.7,
        )
    }
}
