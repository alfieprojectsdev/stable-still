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
    fun `the A07's low-ISO noise maps below the measured ghosting ceiling`() {
        // 0.0136 is what estimateNoise reports for the burst that pinned the
        // ceiling - a hand crossing frame at ISO 322-376. Ghosting became
        // visible in that burst at 0.15, so the derived threshold has to land
        // meaningfully under it or the model recommends the very setting the
        // measurement rules out.
        val sigma = NoiseModel.sigmaFor(0.0136)
        assertTrue("Derived $sigma is not below the 0.15 ghosting onset", sigma < 0.15f)
        assertTrue("Derived $sigma gives up the noise reduction", sigma > 0.08f)
    }

    @Test
    fun `the ceiling sits at the measured ghosting onset, not above it`() {
        // The number itself, pinned. A hand-crossing burst was replayed across
        // fourteen thresholds: clean at 0.12, a faint ghost contour at 0.15,
        // unmistakable at 0.20. Residual noise in static regions had already
        // stopped improving by 0.12, so nothing above this buys anything.
        //
        // If this fails because someone raised the clamp, the burst to re-run
        // is in docs/SESSION-LOG.md - do not raise it on a static scene alone,
        // which is what made the first attempt at this number degenerate.
        assertEquals(0.15f, NoiseModel.MAX_SIGMA, 1e-6f)
        assertEquals(NoiseModel.MAX_SIGMA, NoiseModel.sigmaFor(1.0), 1e-6f)
    }

    @Test
    fun `a high-ISO frame is held at the ceiling rather than allowed to ghost`() {
        // The ISO 1047 archive burst measures 0.0250, which the 6.5x rule alone
        // would take to 0.162 - past the onset. The clamp is what stops it, and
        // it costs almost nothing: that burst's own sweep recovered 1.92x noise
        // reduction at 0.15 against 1.96x at 0.16.
        assertEquals(NoiseModel.MAX_SIGMA, NoiseModel.sigmaFor(0.0250), 1e-6f)
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
