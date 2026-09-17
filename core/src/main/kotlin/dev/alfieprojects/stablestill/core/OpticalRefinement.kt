package dev.alfieprojects.stablestill.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** How hard to work at refining, and when to stop. */
data class RefinementOptions(
    /** Pyramid levels, coarsest first. Four levels reach a 16 px misalignment. */
    val levels: Int = 4,
    val iterationsPerLevel: Int = 12,
    /** Roughly how many pixels to sample per level; the grid is subsampled to hit it. */
    val targetSamples: Int = 40_000,
    /** An update smaller than this ends the level. */
    val convergenceEpsilonPx: Double = 0.01,
    /**
     * Least mean gradient energy per sampled pixel that still counts as texture.
     *
     * A flat wall has no gradient, so the normal equations are singular and the
     * "correction" they produce is whatever the noise happened to look like.
     * Refusing to move is the correct answer there, and the gyro estimate stands.
     */
    val minGradientEnergy: Double = 1e-6,
    /**
     * Diagonal damping, as a fraction of the mean gradient energy.
     *
     * This is how the aperture problem is answered. A scene textured in one
     * direction only - a horizon, a window frame, ruled lines on a page - leaves
     * the perpendicular shift unobservable, and the normal equations for it are
     * singular in that direction alone. Refusing the whole step throws away the
     * direction that *is* observable; solving it undamped returns a number
     * governed by noise. Damping does neither: the observable direction is
     * recovered essentially unchanged and the unobservable one is pulled to
     * zero, which is the honest answer for a shift nothing in the frame
     * constrains.
     */
    val damping: Double = 1e-3,
)

/** What refining one frame against the anchor found. */
data class RefinementResult(
    val frameIndex: Int,
    /** Correction in full-resolution source pixels. */
    val translationX: Double,
    val translationY: Double,
    val samplingMatrix: Mat3,
    /** RMS luma difference from the anchor before and after, on the 0..1 range. */
    val residualBefore: Double,
    val residualAfter: Double,
    val iterations: Int,
    /**
     * Whether the *finest* level settled, which is the only one whose units the
     * answer is in. A coarse level converging says the search found the right
     * neighbourhood, not that it arrived.
     */
    val converged: Boolean,
) {
    val shiftPx: Double get() = hypot(translationX, translationY)

    /** How many times smaller the residual became. Below 1.0 the refinement hurt. */
    val improvement: Double
        get() = if (residualAfter <= 1e-12) Double.MAX_VALUE else residualBefore / residualAfter
}

/** A plan with every frame's homography refined, and the evidence for each. */
data class RefinedPlan(
    val plan: AlignmentPlan,
    val results: Map<Int, RefinementResult>,
)

/**
 * Corrects the gyro's homography against what the pixels actually show.
 *
 * A gyroscope sees rotation and nothing else. That is enough for a landscape,
 * where a few millimetres of hand translation move the image by a fraction of a
 * pixel, and it is not enough for a page at 30 cm: at f ~ 3110 one millimetre of
 * translation shifts the image by about 10 px, and tremor over a 350 ms burst is
 * comfortably several millimetres. Gyro-only alignment leaves a residual that is
 * invisible on a hillside and fatal on 8-point type.
 *
 * It matters for the noise reduction too, not only for sharpness. The rejection
 * threshold's ceiling is set by alignment residual rather than by gain - a
 * static scene ghosts near 0.40 because residual misalignment doubles
 * high-contrast edges - so reducing the residual raises the ceiling and buys
 * back the stacking the ceiling forgoes.
 *
 * **What is estimated is a translation**, two parameters, not a full eight-degree
 * homography. That is the term the gyro structurally cannot see, and it is what
 * parallax at close range produces; rotation the gyro already has, to
 * milliradians, from a 403 Hz trace. Two parameters also stay well-conditioned
 * on the texture a real scene offers, where eight would happily fit noise. If a
 * burst ever shows residual the translation cannot absorb, this is the place to
 * grow - not the place to have guessed.
 *
 * Plain Lucas-Kanade rather than ECC, and for a reason specific to this capture
 * path: ECC buys invariance to illumination change between frames, and the
 * exposure cap holds exposure time and gain fixed for the whole burst, so there
 * is no illumination change to be invariant to. Paying for it in complexity
 * would buy nothing.
 */
object OpticalRefinement {

    /**
     * Refines one frame's sampling matrix against the anchor.
     *
     * Both matrices map an *output* pixel to a source pixel in their own frame,
     * which is what makes this well-posed: the anchor's own warp defines the
     * common grid, and the correction is whatever remains once the gyro has had
     * its say.
     */
    fun refine(
        frameIndex: Int,
        anchor: LumaPlane,
        anchorSampling: Mat3,
        frame: LumaPlane,
        frameSampling: Mat3,
        crop: CropWindow,
        options: RefinementOptions = RefinementOptions(),
    ): RefinementResult {
        var tx = 0.0
        var ty = 0.0
        var iterations = 0
        var converged = false

        // Coarsest level first. A correction of 20 px is a quarter of a pixel
        // eight levels down, which is why a pyramid is not optional: a
        // single-scale gradient step assumes the true shift is inside one
        // pixel's worth of gradient, and a close-range parallax residual is not.
        val depth = usableLevels(anchor, frame, crop, options.levels)
        for (level in depth - 1 downTo 0) {
            val scale = (1 shl level).toDouble()
            val anchorL = pyramidLevel(anchor, level)
            val frameL = pyramidLevel(frame, level)
            val grid = sampleGrid(
                anchorL, scaleSampling(anchorSampling, scale),
                crop, scale, options.targetSamples,
            )

            // Convergence describes this level only, and is cleared on entry:
            // the finest level is the one whose units the answer is in, and a
            // coarse level settling says the search found the right
            // neighbourhood, not that it arrived.
            //
            // The empty-grid case is handled by a branch rather than a
            // `continue` so the rescale below cannot be stepped over. That is
            // defensive rather than a fix for a live bug: `sampleGrid`'s bounds
            // are `1.5` and `width - 2.5` in *level* units, which in full-size
            // terms tighten as the level coarsens, so emptiness is monotone -
            // every empty level precedes every populated one, and the estimate
            // being carried across is still zero when it happens. Worth not
            // relying on, since it is a property of the bounds and not of the
            // loop.
            converged = false
            if (grid.count > 0) {
                val frameSamplingL = scaleSampling(frameSampling, scale)
                for (iteration in 0 until options.iterationsPerLevel) {
                    iterations++
                    val step = solveStep(grid, frameL, frameSamplingL, tx, ty, options)
                        ?: break
                    tx += step.first
                    ty += step.second
                    if (hypot(step.first, step.second) < options.convergenceEpsilonPx) {
                        converged = true
                        break
                    }
                }
            }

            // Down a level, a source pixel is worth two of the ones just used.
            if (level > 0) {
                tx *= 2.0
                ty *= 2.0
            }
        }

        val finest = sampleGrid(anchor, anchorSampling, crop, 1.0, options.targetSamples)
        val before = residual(finest, frame, frameSampling, 0.0, 0.0)
        val after = residual(finest, frame, frameSampling, tx, ty)

        // A refinement that made the match worse is a refinement that found
        // noise. Keeping the gyro's answer is the safe failure, and the result
        // still reports what was tried so a caller can see it happened.
        val keep = after <= before
        val appliedX = if (keep) tx else 0.0
        val appliedY = if (keep) ty else 0.0

        return RefinementResult(
            frameIndex = frameIndex,
            translationX = appliedX,
            translationY = appliedY,
            samplingMatrix = translation(appliedX, appliedY) * frameSampling,
            residualBefore = before,
            residualAfter = if (keep) after else before,
            iterations = iterations,
            converged = converged && keep,
        )
    }

    /**
     * Refines every usable frame in a plan.
     *
     * The anchor is left alone - it defines the grid and cannot be misaligned
     * with itself. A frame whose refined corners fall off the sensor becomes
     * unusable; refinement can only withdraw a frame, never reinstate one, since
     * the rotation test that may have dropped it is not re-run here.
     */
    fun refinePlan(
        plan: AlignmentPlan,
        options: RefinementOptions = RefinementOptions(),
        lumaAt: (Int) -> LumaPlane,
    ): RefinedPlan {
        val anchorAlignment = plan.alignments.firstOrNull { it.frameIndex == plan.anchorIndex }
            ?: error("Anchor frame ${plan.anchorIndex} has no alignment in the plan")
        val anchor = lumaAt(plan.anchorIndex)
        val results = LinkedHashMap<Int, RefinementResult>()

        val refined = plan.alignments.map { alignment ->
            if (!alignment.usable || alignment.frameIndex == plan.anchorIndex) return@map alignment
            val frame = lumaAt(alignment.frameIndex)
            val result = refine(
                frameIndex = alignment.frameIndex,
                anchor = anchor,
                anchorSampling = anchorAlignment.samplingMatrix,
                frame = frame,
                frameSampling = alignment.samplingMatrix,
                crop = plan.crop,
                options = options,
            )
            results[alignment.frameIndex] = result
            alignment.copy(
                samplingMatrix = result.samplingMatrix,
                usable = BurstAligner.cornersInside(
                    result.samplingMatrix, plan.crop, frame.width, frame.height,
                ),
                maxCornerShiftPx = BurstAligner.maxCornerShift(result.samplingMatrix, plan.crop),
            )
        }
        return RefinedPlan(plan.copy(alignments = refined), results)
    }

    /**
     * RMS luma difference between a frame warped onto the anchor's grid and the
     * anchor itself, on the 0..1 range.
     *
     * The number every other question here reduces to: whether a correction
     * helped, whether one rig handedness beats the other, whether a burst is
     * aligned at all.
     */
    fun residual(
        anchor: LumaPlane,
        anchorSampling: Mat3,
        frame: LumaPlane,
        frameSampling: Mat3,
        crop: CropWindow,
        targetSamples: Int = 40_000,
    ): Double = residual(
        sampleGrid(anchor, anchorSampling, crop, 1.0, targetSamples),
        frame, frameSampling, 0.0, 0.0,
    )

    // ------------------------------------------------------------------ internals

    /** The anchor's warped values on a subsampled output grid, with their coordinates. */
    private class Grid(
        val outX: DoubleArray,
        val outY: DoubleArray,
        val value: DoubleArray,
        val count: Int,
    )

    private fun sampleGrid(
        anchor: LumaPlane,
        anchorSampling: Mat3,
        crop: CropWindow,
        scale: Double,
        targetSamples: Int,
    ): Grid {
        val width = (crop.outputWidth / scale).toInt()
        val height = (crop.outputHeight / scale).toInt()
        val step = maxOf(1, sqrt(width.toDouble() * height / targetSamples).toInt())
        val capacity = ((width / step) + 1) * ((height / step) + 1)
        val xs = DoubleArray(capacity)
        val ys = DoubleArray(capacity)
        val vs = DoubleArray(capacity)
        var n = 0

        var py = 0
        while (py < height) {
            var px = 0
            while (px < width) {
                val outX = px + 0.5
                val outY = py + 0.5
                val mapped = anchorSampling.mapPoint(outX, outY)
                if (mapped != null &&
                    mapped.first >= 1.5 && mapped.second >= 1.5 &&
                    mapped.first <= anchor.width - 2.5 && mapped.second <= anchor.height - 2.5
                ) {
                    xs[n] = outX
                    ys[n] = outY
                    vs[n] = anchor.sample(mapped.first, mapped.second)
                    n++
                }
                px += step
            }
            py += step
        }
        return Grid(xs, ys, vs, n)
    }

    /** One Gauss-Newton step, or null when the scene has nothing to lock onto. */
    private fun solveStep(
        grid: Grid,
        frame: LumaPlane,
        frameSampling: Mat3,
        tx: Double,
        ty: Double,
        options: RefinementOptions,
    ): Pair<Double, Double>? {
        var hxx = 0.0
        var hxy = 0.0
        var hyy = 0.0
        var bx = 0.0
        var by = 0.0
        var used = 0

        for (i in 0 until grid.count) {
            val mapped = frameSampling.mapPoint(grid.outX[i], grid.outY[i]) ?: continue
            val sx = mapped.first + tx
            val sy = mapped.second + ty
            // Two pixels of margin: the central difference below taps one either
            // side, and a gradient built partly from the clamped edge would drag
            // the whole estimate outwards.
            if (sx < 2.0 || sy < 2.0 || sx > frame.width - 3.0 || sy > frame.height - 3.0) continue

            val f = frame.sample(sx, sy)
            val gx = (frame.sample(sx + 1.0, sy) - frame.sample(sx - 1.0, sy)) * 0.5
            val gy = (frame.sample(sx, sy + 1.0) - frame.sample(sx, sy - 1.0)) * 0.5
            val e = grid.value[i] - f

            hxx += gx * gx
            hxy += gx * gy
            hyy += gy * gy
            bx += gx * e
            by += gy * e
            used++
        }
        if (used < 16) return null
        // Nothing in frame has an edge: there is no correction to be had, at any
        // damping, and the gyro's answer stands.
        if ((hxx + hyy) / used < options.minGradientEnergy) return null

        // Damped, so a one-directional texture still yields the direction it
        // does constrain. See RefinementOptions.damping.
        val lambda = options.damping * (hxx + hyy) / 2.0
        val dxx = hxx + lambda
        val dyy = hyy + lambda
        val det = dxx * dyy - hxy * hxy
        if (abs(det) < 1e-18) return null

        return Pair((dyy * bx - hxy * by) / det, (dxx * by - hxy * bx) / det)
    }

    private fun residual(
        grid: Grid,
        frame: LumaPlane,
        frameSampling: Mat3,
        tx: Double,
        ty: Double,
    ): Double {
        var sumSq = 0.0
        var used = 0
        for (i in 0 until grid.count) {
            val mapped = frameSampling.mapPoint(grid.outX[i], grid.outY[i]) ?: continue
            val sx = mapped.first + tx
            val sy = mapped.second + ty
            if (sx < 0.5 || sy < 0.5 || sx > frame.width - 1.5 || sy > frame.height - 1.5) continue
            val d = grid.value[i] - frame.sample(sx, sy)
            sumSq += d * d
            used++
        }
        return if (used == 0) Double.MAX_VALUE else sqrt(sumSq / used)
    }

    private fun pyramidLevel(plane: LumaPlane, level: Int): LumaPlane {
        var current = plane
        repeat(level) { current = current.downsample() }
        return current
    }

    /** How many halvings leave a grid worth matching on. */
    private fun usableLevels(
        anchor: LumaPlane,
        frame: LumaPlane,
        crop: CropWindow,
        requested: Int,
    ): Int {
        var levels = 1
        val smallest = minOf(
            anchor.width, anchor.height, frame.width, frame.height,
            crop.outputWidth, crop.outputHeight,
        )
        while (levels < requested && (smallest shr levels) >= 32) levels++
        return levels
    }

    /**
     * The same warp expressed in the coordinates of a pyramid level.
     *
     * Both ends scale: an output pixel at level L stands for `2^L` full-size
     * output pixels, and so does the source pixel it maps to. Conjugating by the
     * scale is what keeps a matrix meaning the same thing at every level.
     */
    private fun scaleSampling(m: Mat3, scale: Double): Mat3 =
        Mat3.diagonal(1.0 / scale, 1.0 / scale, 1.0) * m * Mat3.diagonal(scale, scale, 1.0)

    private fun translation(tx: Double, ty: Double) = Mat3(
        1.0, 0.0, tx,
        0.0, 1.0, ty,
        0.0, 0.0, 1.0,
    )
}
