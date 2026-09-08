package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NoiseModelTest {

    private fun plane(width: Int, height: Int, fill: (Int, Int) -> Int): LumaPlane {
        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * width + x] = fill(x, y).coerceIn(0, 255).toByte()
            }
        }
        return LumaPlane(width, height, bytes)
    }

    @Test
    fun `a flat frame has no noise and gets the tightest threshold`() {
        val flat = plane(600, 600) { _, _ -> 128 }
        assertEquals(0.0, NoiseModel.estimateNoise(flat), 1e-9)
        assertEquals(NoiseModel.MIN_SIGMA, NoiseModel.sigmaFor(flat), 1e-6f)
    }

    @Test
    fun `measured noise recovers the standard deviation it was given`() {
        val rng = Random(7)
        // Gaussian-ish via the sum of uniforms; sigma 8 of 255 is roughly what
        // the A07 produced at ISO 1047.
        val target = 8.0
        val noisy = plane(600, 600) { _, _ ->
            val g = (0 until 12).sumOf { rng.nextDouble() } - 6.0
            (128 + g * target).toInt()
        }
        val measured = NoiseModel.estimateNoise(noisy) * 255.0
        assertEquals("Measured $measured against $target", target, measured, target * 0.35)
    }

    @Test
    fun `a frame full of edges is judged by its flatter tiles, not its busiest`() {
        // Half flat, half hard stripes. A mean over tiles would be dominated by
        // the striped half and report noise an order of magnitude too high,
        // which would loosen the threshold until moving subjects ghosted.
        val mixed = plane(1200, 600) { x, _ ->
            if (x < 600) 128 else if ((x / 2) % 2 == 0) 0 else 255
        }
        val measured = NoiseModel.estimateNoise(mixed) * 255.0
        assertTrue("Edges leaked into the noise estimate: $measured", measured < 5.0)
    }

    @Test
    fun `the threshold rises with noise and is clamped at both ends`() {
        assertTrue(NoiseModel.sigmaFor(0.02) < NoiseModel.sigmaFor(0.05))
        assertEquals(NoiseModel.MIN_SIGMA, NoiseModel.sigmaFor(0.0), 1e-6f)
        assertEquals(NoiseModel.MAX_SIGMA, NoiseModel.sigmaFor(1.0), 1e-6f)
    }

    @Test
    fun `the A07's measured noise floor maps to a threshold well above the old constant`() {
        // 0.0250 is what estimateNoise actually reports for the ISO 1047 burst
        // in the test fixture - not the 9.87/255 quoted elsewhere, which came
        // from a mean over tiles rather than this percentile and is a different
        // statistic entirely.
        //
        // The bar here is loose on purpose. The sweep that would pin it down
        // was degenerate: against a static scene noise falls monotonically to
        // the clamp, so there is no measured ceiling to assert against. All
        // this checks is that a real high-ISO frame lands meaningfully above
        // the 0.10 constant it replaces, and short of the clamp.
        val sigma = NoiseModel.sigmaFor(0.0250)
        assertTrue("A07 noise mapped to $sigma", sigma > 0.12f && sigma < NoiseModel.MAX_SIGMA)
    }

    @Test
    fun `a clean daylight frame stays near the original constant`() {
        // Low-ISO noise should not produce a threshold wildly looser than the
        // 0.10 that was hand-picked for daylight, or this change would trade a
        // low-light win for a daylight ghosting regression.
        val sigma = NoiseModel.sigmaFor(2.5 / 255.0)
        assertTrue("Daylight mapped to $sigma", sigma in NoiseModel.MIN_SIGMA..0.12f)
    }
}
