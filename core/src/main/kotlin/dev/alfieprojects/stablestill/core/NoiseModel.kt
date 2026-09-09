package dev.alfieprojects.stablestill.core

import kotlin.math.sqrt

/**
 * Measures how noisy a frame is, and turns that into a rejection threshold.
 *
 * The merge weights each pixel by how far it sits from the anchor, and
 * discounts anything beyond `rejectSigma`. That threshold was a constant, which
 * is wrong in a way that only shows up at high gain: the distance between two
 * *correctly aligned* frames is itself proportional to the sensor noise, so a
 * fixed threshold rejects genuine agreement exactly when there is most noise to
 * average away - which is precisely when stacking is worth doing.
 *
 * Both ends are now measured. On the A07 at ISO 322-376, where the frame's own
 * noise reads 0.013, residual noise in static regions stops falling at a
 * threshold near 0.12 - roughly nine times that figure - while a moving subject
 * starts leaving a visible ghost at 0.15. There is nothing to buy above the
 * lower of the two and something real to lose, which is what [MAX_SIGMA]
 * encodes.
 */
object NoiseModel {

    /**
     * Multiplier from per-channel noise to rejection threshold.
     *
     * Derived, not fitted, and that is deliberate.
     *
     * Two correctly aligned frames differ by the *difference* of two noisy
     * samples, which carries sqrt(2) times the noise of either. Across three
     * colour channels the magnitude of that difference follows a Maxwell
     * distribution, whose 99.9th percentile sits near 4.6 standard deviations.
     * Admitting essentially all genuine noise therefore wants about
     * `sqrt(2) * 4.6 = 6.5` times the per-channel figure. Chroma is noisier
     * than luma at high gain and the shader compares in RGB, so if anything
     * this errs tight.
     *
     * Measurement has since bracketed it rather than replaced it. Against a
     * burst with a hand moving through frame, ghosting became visible at about
     * eleven times the measured noise, and noise reduction had stopped
     * improving by about nine times it. Six and a half sits below both, keeping
     * roughly a 1.7x margin to the ghosting onset at the cost of a few percent
     * of the available noise reduction - a trade worth making, since the
     * measurement exists at one gain only and the margin is what covers the
     * others.
     */
    const val SIGMA_PER_NOISE = 6.5

    /** Which tile, by flatness rank, is taken as the noise floor. */
    const val PERCENTILE = 0.25

    /** Never reject so aggressively that a clean frame stops contributing. */
    const val MIN_SIGMA = 0.06f

    /**
     * Beyond this, the threshold admits genuinely different pixels and moving
     * subjects ghost instead of falling back to the anchor.
     *
     * Measured, not chosen. A burst with a hand crossing the frame was replayed
     * across fourteen thresholds and the moving region inspected at 1:1: the
     * background is clean at 0.12, carries a faint translucent contour of the
     * finger at 0.15, and shows it unmistakably at 0.20. A static burst of the
     * same scene puts its own ceiling far higher, around 0.40, where residual
     * misalignment starts doubling high-contrast edges - so the moving subject
     * is what binds.
     *
     * Nothing is given up by stopping here. Residual noise in static regions
     * flattens out at 0.12 and does not improve measurably through 1.00, so
     * every threshold above this ceiling buys ghosting and no noise reduction.
     * That knee was invisible to the earlier sweep, which scored whole frames
     * on noise alone and therefore could not see a cost it does not measure.
     */
    const val MAX_SIGMA = 0.15f

    /**
     * Rejection threshold for a frame whose per-channel noise is [noise],
     * expressed on the 0..1 range the shader compares in.
     */
    fun sigmaFor(noise: Double): Float {
        require(noise >= 0.0) { "Noise cannot be negative, was $noise" }
        return (noise * SIGMA_PER_NOISE).toFloat().coerceIn(MIN_SIGMA, MAX_SIGMA)
    }

    /**
     * Estimates per-channel noise from luma, as a fraction of full scale.
     *
     * Sampled as the standard deviation inside small tiles, and taken as a low
     * *percentile* across them. A mean is dragged upwards by every tile holding
     * an edge, and a median only survives while most of the frame is flat -
     * which a densely textured scene is not. The flattest quarter of tiles is
     * where sensor noise is closest to being the only thing varying.
     *
     * Not the minimum, though: a clipped black region has no variance at all
     * and would report a noise floor of zero for a frame that is full of it.
     *
     * This measures noise *plus* whatever texture the flattest tiles still
     * carry, so it is an upper bound. That errs toward a looser threshold,
     * which costs ghosting resistance rather than noise reduction - the
     * direction worth erring in only because the alternative silently discards
     * most of the stack.
     */
    fun estimateNoise(luma: LumaPlane, tile: Int = 8, stride: Int = 200): Double {
        require(tile >= 2) { "Tile must be at least 2 px, was $tile" }
        val deviations = ArrayList<Double>()
        var y = 0
        while (y + tile <= luma.height) {
            var x = 0
            while (x + tile <= luma.width) {
                var sum = 0.0
                var sumSq = 0.0
                for (ty in y until y + tile) {
                    for (tx in x until x + tile) {
                        val v = luma[tx, ty].toDouble()
                        sum += v
                        sumSq += v * v
                    }
                }
                val n = tile * tile
                val variance = sumSq / n - (sum / n) * (sum / n)
                deviations += sqrt(if (variance > 0.0) variance else 0.0)
                x += stride
            }
            y += stride
        }
        if (deviations.isEmpty()) return 0.0
        deviations.sort()
        val index = (deviations.size * PERCENTILE).toInt().coerceIn(0, deviations.lastIndex)
        return deviations[index] / 255.0
    }

    /** Threshold for [luma], measured and converted in one step. */
    fun sigmaFor(luma: LumaPlane): Float = sigmaFor(estimateNoise(luma))
}
