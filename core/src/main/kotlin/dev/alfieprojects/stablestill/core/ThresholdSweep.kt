package dev.alfieprojects.stablestill.core

import kotlin.math.abs

/** One threshold's worth of answer. */
data class SweepPoint(
    val rejectSigma: Float,
    /** Mean frames actually averaged per pixel; noise falls as its square root. */
    val effectiveFrameCount: Double,
    /** Noise measured in the merged result, same units as [NoiseModel.estimateNoise]. */
    val residualNoise: Double,
    /**
     * Mean departure from the anchor, in levels, where a subject moved.
     *
     * This is the ghost, measured. It is what fourteen replays and an eyeball at
     * 1:1 were doing by hand.
     */
    val ghostLevels: Double,
    /** Mean departure from the anchor, in levels, where nothing moved - the benefit. */
    val staticLevels: Double,
    val anchorFallbackFraction: Double,
)

/**
 * Runs the merge across a range of rejection thresholds and reports what each
 * one bought and what it cost.
 *
 * The threshold has two ends and they are measured against different things.
 * Raising it admits more frames, which reduces noise - until the frames stop
 * disagreeing about noise and start disagreeing about the scene, at which point
 * it admits a moving subject and the result ghosts. Finding the crossing used to
 * require a phone, a burst, fourteen replays and a careful look at each one.
 *
 * Separating the two is [ReferenceMerge.disagreement]: pixels where some frame
 * departs a long way from the anchor are where the scene changed, and the
 * merged result departing from the anchor *there* is a ghost, while the same
 * departure elsewhere is noise being averaged away. One metric each, so a sweep
 * reports the trade rather than a number someone still has to interpret.
 */
object ThresholdSweep {

    /**
     * Disagreement beyond which a pixel counts as "the scene changed here".
     *
     * Two correctly aligned frames differ by the difference of two noise samples
     * across three channels, which at the noisiest burst on record - 0.025 -
     * reaches about 0.06. This sits five times above that, so noise cannot put a
     * pixel on the moving side of the line; only a subject or a gross alignment
     * failure can.
     */
    const val MOTION_THRESHOLD = 0.30f

    /**
     * @param frameAt called once per frame per threshold, plus once for the
     *   disagreement pass. Sweeping is the case where caching every frame in a
     *   map is worth the memory - otherwise a fourteen-point sweep reads a
     *   12.5 MP burst fifteen times.
     */
    fun sweep(
        plan: AlignmentPlan,
        sigmas: List<Float>,
        noiseStride: Int = 200,
        motionThreshold: Float = MOTION_THRESHOLD,
        frameAt: (Int) -> YuvFrame,
    ): List<SweepPoint> {
        require(sigmas.isNotEmpty()) { "Nothing to sweep" }
        val anchor = ReferenceMerge.anchorImage(plan, frameAt)
        val moved = ReferenceMerge.disagreement(plan, frameAt)

        return sigmas.map { sigma ->
            val result = ReferenceMerge.merge(plan, sigma, frameAt)
            val image = result.image

            var ghostSum = 0.0
            var ghostCount = 0L
            var staticSum = 0.0
            var staticCount = 0L
            for (p in moved.indices) {
                val x = p % image.width
                val y = p / image.width
                // Max across channels: a ghost is usually a luminance edge, but a
                // colour fringe at a moving boundary is the same failure.
                var worst = 0
                for (c in 0..2) {
                    val d = abs(image[x, y, c] - anchor[x, y, c])
                    if (d > worst) worst = d
                }
                if (moved[p] > motionThreshold) {
                    ghostSum += worst
                    ghostCount++
                } else {
                    staticSum += worst
                    staticCount++
                }
            }

            SweepPoint(
                rejectSigma = sigma,
                effectiveFrameCount = result.effectiveFrameCount,
                residualNoise = NoiseModel.estimateNoise(image.toLuma(), stride = noiseStride),
                ghostLevels = if (ghostCount == 0L) 0.0 else ghostSum / ghostCount,
                staticLevels = if (staticCount == 0L) 0.0 else staticSum / staticCount,
                anchorFallbackFraction = result.anchorFallbackFraction,
            )
        }
    }

    /** A readable table, for a log or a session note. */
    fun format(points: List<SweepPoint>): String = buildString {
        appendLine("  sigma  effFrames  residNoise  ghost(lv)  static(lv)  fallback")
        for (p in points) {
            appendLine(
                "  %5.3f  %9.2f  %10.5f  %9.2f  %10.2f  %8.4f".format(
                    p.rejectSigma, p.effectiveFrameCount, p.residualNoise,
                    p.ghostLevels, p.staticLevels, p.anchorFallbackFraction,
                )
            )
        }
    }
}
