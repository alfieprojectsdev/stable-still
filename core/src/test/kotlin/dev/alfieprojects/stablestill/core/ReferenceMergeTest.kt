package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The merge, pinned down without a phone in the room.
 *
 * Until now every question about the merge was a question about hardware: the
 * rejection ceiling was found by replaying one burst fourteen times on a handset
 * and looking at the result at 1:1. These tests ask the same questions of
 * synthetic bursts whose answers are known in advance, which is the difference
 * between a measurement and a check.
 */
class ReferenceMergeTest {

    private val width = 64
    private val height = 48
    private val crop = CropWindow(width, height, marginFraction = 0.125)

    /**
     * What a grey frame of this luma resolves to once it has been through the
     * shader's BT.601 conversion.
     *
     * Not simply `luma`. Neutral chroma would be 127.5 and eight bits cannot
     * hold it, so a grey frame carries a fraction of a level of tint. It is
     * identical in every frame and cancels out of every difference, but an
     * expectation written as "R should be 128" would be wrong by a level.
     */
    private fun expected(luma: Int): IntArray {
        val y = luma / 255.0
        val c = 128.0 / 255.0 - 0.5
        fun q(v: Double) = ((v.coerceIn(0.0, 1.0)) * 255.0 + 0.5).toInt()
        return intArrayOf(
            q(y + 1.402 * c),
            q(y - 0.344136 * c - 0.714136 * c),
            q(y + 1.772 * c),
        )
    }

    private fun merge(
        frames: List<YuvFrame>,
        shifts: List<Pair<Double, Double>>,
        sigma: Float,
        anchorIndex: Int = 0,
    ): MergeResult = ReferenceMerge.merge(
        frames.mapIndexed { i, f -> i to f },
        Synthetic.translationPlan(crop, anchorIndex, shifts),
        sigma,
    )

    @Test
    fun `sampling lands on texel centres, not half a pixel off`() {
        // A ramp, so any addressing error shows up as a value error.
        val frame = Synthetic.greyFrame(width, height) { x, _ -> 4 * x }
        val rgb = DoubleArray(3)

        // uv = (i + 0.5) / size addresses texel i's centre exactly.
        frame.sampleRgb(10.5 / width, 0.5 / height, rgb)
        assertEquals(40.0 / 255.0, rgb[0] - (1.402 * (128.0 / 255.0 - 0.5)), 1e-9)

        // uv = i / size sits on the boundary, so it is the mean of two texels.
        // This is the assertion that fails if the half-texel offset is dropped.
        frame.sampleRgb(10.0 / width, 0.5 / height, rgb)
        assertEquals(38.0 / 255.0, rgb[0] - (1.402 * (128.0 / 255.0 - 0.5)), 1e-9)
    }

    @Test
    fun `a single unmoved frame comes back as the crop of itself`() {
        val frame = Synthetic.greyFrame(width, height) { x, y -> (3 * x + 2 * y) % 256 }
        val result = merge(listOf(frame), listOf(0.0 to 0.0), sigma = 0.15f)

        assertEquals(crop.outputWidth, result.image.width)
        assertEquals(crop.outputHeight, result.image.height)
        assertEquals(1, result.framesContributed)
        // Nothing was rejected and nothing fell back: one frame weighted 1.0.
        assertEquals(1.0, result.effectiveFrameCount, 1e-6)
        assertEquals(0.0, result.anchorFallbackFraction, 0.0)

        val offsetX = crop.offsetX.toInt()
        val offsetY = crop.offsetY.toInt()
        for (py in 0 until crop.outputHeight step 7) {
            for (px in 0 until crop.outputWidth step 5) {
                val want = expected((3 * (px + offsetX) + 2 * (py + offsetY)) % 256)
                for (c in 0..2) {
                    assertEquals(
                        "channel $c at $px,$py",
                        want[c].toDouble(), result.image[px, py, c].toDouble(), 1.0,
                    )
                }
            }
        }

        val ppm = result.image.toPpm()
        val header = "P6\n${crop.outputWidth} ${crop.outputHeight}\n255\n"
        assertEquals(header, String(ppm, 0, header.length, Charsets.US_ASCII))
        assertEquals(header.length + crop.outputWidth * crop.outputHeight * 3, ppm.size)
    }

    @Test
    fun `a sub-pixel shift is warped back out`() {
        // Both frames hold the same linear ramp, the second displaced by 2.5 px.
        // A ramp is reproduced exactly by bilinear interpolation, so if the warp
        // is right the two agree to the last bit, and if it is off by any
        // fraction of a pixel they differ by twice that in luma.
        val anchor = Synthetic.greyFrame(width, height) { x, _ -> 20 + 2 * x }
        val shifted = Synthetic.greyFrame(width, height) { x, _ -> 15 + 2 * x }

        val alone = merge(listOf(anchor), listOf(0.0 to 0.0), sigma = 0.15f)
        val corrected = merge(listOf(anchor, shifted), listOf(0.0 to 0.0, 2.5 to 0.0), 0.15f)
        val uncorrected = merge(listOf(anchor, shifted), listOf(0.0 to 0.0, 0.0 to 0.0), 0.15f)

        var worstCorrected = 0
        var worstUncorrected = 0
        for (py in 0 until crop.outputHeight) {
            for (px in 0 until crop.outputWidth) {
                worstCorrected = maxOf(
                    worstCorrected, abs(corrected.image[px, py, 0] - alone.image[px, py, 0]),
                )
                worstUncorrected = maxOf(
                    worstUncorrected, abs(uncorrected.image[px, py, 0] - alone.image[px, py, 0]),
                )
            }
        }
        assertTrue("Warped frame differs by $worstCorrected levels", worstCorrected <= 1)
        // Teeth: without the shift the same two frames visibly disagree, so the
        // check above is not passing because both paths do nothing.
        assertTrue("Unwarped frame differs by only $worstUncorrected", worstUncorrected >= 2)
    }

    @Test
    fun `stacking noisy copies reduces noise by about the root of the count`() {
        val random = Random(42)
        val base = 128
        val inputNoise = 8.0
        val frames = (0 until 8).map {
            Synthetic.greyFrame(width, height) { _, _ ->
                (base + random.nextGaussian() * inputNoise).toInt()
            }
        }
        val result = merge(frames, List(8) { 0.0 to 0.0 }, sigma = 0.30f)

        val want = expected(base)[0].toDouble()
        var sumSq = 0.0
        var n = 0
        for (py in 0 until crop.outputHeight) {
            for (px in 0 until crop.outputWidth) {
                val d = result.image[px, py, 0] - want
                sumSq += d * d
                n++
            }
        }
        val outputNoise = sqrt(sumSq / n)
        val reduction = inputNoise / outputNoise

        // sqrt(8) is 2.83. The merge cannot quite reach it, because the anchor
        // carries its own noise and every weight is computed against it, so the
        // result is pulled slightly towards one frame rather than eight.
        assertTrue(
            "Noise went from $inputNoise to $outputNoise, a factor of $reduction",
            reduction in 2.0..3.2,
        )
        assertEquals(8.0, result.effectiveFrameCount, 0.5)
    }

    @Test
    fun `a moving subject is rejected at the measured ceiling and ghosts above it`() {
        // The anchor sees only background; every other frame has a bright subject
        // sitting in the same place. That is the burst that set MAX_SIGMA.
        val background = 100
        val subject = 200
        val subjectRegion = { x: Int, y: Int -> x in 20..40 && y in 15..30 }
        val anchor = Synthetic.greyFrame(width, height) { _, _ -> background }
        val others = (1 until 8).map {
            Synthetic.greyFrame(width, height) { x, y ->
                if (subjectRegion(x, y)) subject else background
            }
        }
        val frames = listOf(anchor) + others
        val shifts = List(8) { 0.0 to 0.0 }

        // A point well inside the subject, in output coordinates.
        val px = 30 - crop.offsetX.toInt()
        val py = 22 - crop.offsetY.toInt()

        val atCeiling = merge(frames, shifts, sigma = NoiseModel.MAX_SIGMA)
        val wideOpen = merge(frames, shifts, sigma = 1.0f)

        assertEquals(
            "At the measured ceiling the subject must fall back to the anchor",
            expected(background)[0].toDouble(),
            atCeiling.image[px, py, 0].toDouble(),
            1.0,
        )
        assertTrue(
            "At sigma 1.0 the subject should ghost, read ${wideOpen.image[px, py, 0]}",
            wideOpen.image[px, py, 0] > 150,
        )

        // Background is identical in every frame, so it stacks fully at both
        // thresholds. Rejection must cost nothing where nothing moved.
        assertEquals(
            expected(background)[0].toDouble(),
            atCeiling.image[0, 0, 0].toDouble(),
            1.0,
        )

        // And the metric says so without anyone having to look at the picture:
        // the subject binds the effective frame count, not the background.
        assertTrue(
            "Effective frames ${atCeiling.effectiveFrameCount} should be near 1 in a rejected region",
            atCeiling.meanWeight.values.drop(1).all { it < 1.0 },
        )
    }

    @Test
    fun `the weight is the shader's gaussian, not something near it`() {
        val anchor = Synthetic.greyFrame(width, height) { _, _ -> 100 }
        val other = Synthetic.greyFrame(width, height) { _, _ -> 110 }
        val sigma = 0.2f
        val result = merge(listOf(anchor, other), listOf(0.0 to 0.0, 0.0 to 0.0), sigma)

        // Ten levels in all three channels: d^2 = 3 * (10/255)^2.
        val dSq = 3.0 * (10.0 / 255.0) * (10.0 / 255.0)
        val want = kotlin.math.exp(-dSq / (sigma * sigma).toDouble())
        // The tolerance is the anchor's own quantisation: the shader compares
        // against an eight-bit render of the anchor, not against float.
        assertEquals(want, result.meanWeight.getValue(1), 0.01)
        assertEquals(1.0, result.meanWeight.getValue(0), 1e-9)
    }

    @Test
    fun `a frame that falls outside the sensor contributes nothing at all`() {
        val anchor = Synthetic.greyFrame(width, height) { x, y -> (3 * x + 2 * y) % 256 }
        val stray = Synthetic.greyFrame(width, height) { _, _ -> 255 }

        // Shifted far enough that every tap lands off the frame. The per-pixel
        // bounds guard has to drop it: clamping instead would smear the edge
        // pixel across the whole output and nothing would report it.
        val result = merge(listOf(anchor, stray), listOf(0.0 to 0.0, 1000.0 to 0.0), 0.15f)
        val alone = merge(listOf(anchor), listOf(0.0 to 0.0), 0.15f)

        assertEquals(0.0, result.meanWeight.getValue(1), 0.0)
        assertEquals(1.0, result.effectiveFrameCount, 1e-6)
        for (py in 0 until crop.outputHeight step 5) {
            for (px in 0 until crop.outputWidth step 5) {
                assertEquals(
                    alone.image[px, py, 0].toLong(), result.image[px, py, 0].toLong(),
                )
            }
        }
    }

    @Test
    fun `an anchor that is itself out of bounds leaves the picture defined`() {
        // Pass 3's fallback exists so a pixel no frame could fill resolves to the
        // anchor rather than to black. Here the anchor is pushed off the sensor
        // too, so the accumulator is empty and the fallback is all there is.
        val anchor = Synthetic.greyFrame(width, height) { _, _ -> 90 }
        val plan = AlignmentPlan(
            anchorIndex = 0,
            crop = crop,
            alignments = listOf(
                FrameAlignment(
                    frameIndex = 0,
                    samplingMatrix = Mat3(
                        1.0, 0.0, crop.offsetX + 5000.0,
                        0.0, 1.0, crop.offsetY,
                        0.0, 0.0, 1.0,
                    ),
                    usable = true,
                    maxCornerShiftPx = 5000.0,
                    rotationRadians = 0.0,
                ),
            ),
        )
        val result = ReferenceMerge.merge(listOf(0 to anchor), plan, 0.15f)

        assertEquals(1.0, result.anchorFallbackFraction, 0.0)
        // Clamped to the frame's edge by the anchor pass, which is uniform here,
        // so the picture is the scene rather than a black rectangle.
        assertEquals(expected(90)[0].toDouble(), result.image[0, 0, 0].toDouble(), 1.0)
    }
}
