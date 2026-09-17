package dev.alfieprojects.stablestill.core

import kotlin.math.abs
import kotlin.math.sqrt

/** How well one handedness explained the burst. */
data class HandednessScore(
    val handedness: Int,
    /** Mean RMS luma residual over the frames it could align, 0..1 range. */
    val meanResidual: Double,
    val perFrame: Map<Int, Double>,
    /**
     * Frames this hypothesis kept on the sensor - which is not the number it was
     * scored on. Scoring uses only frames both signs kept, so the two means are
     * comparable; this is here because a sign that drops frames the other keeps
     * is itself evidence, and hiding it inside the mean would make it invisible.
     */
    val usableCount: Int,
)

/** Which sign the pixels chose, and whether the burst was entitled to an opinion. */
data class HandednessVerdict(
    val chosen: RigAlignment,
    val scores: List<HandednessScore>,
    /** Gap between the two, as a fraction of the better one. */
    val margin: Double,
    /**
     * How far apart, in source pixels, the two hypotheses place a crop corner.
     *
     * The precondition for the whole test. If the two signs put every corner in
     * the same place, no residual can separate them and a verdict would be a
     * coin toss dressed as a measurement.
     */
    val separationPx: Double,
    val maxRotationRadians: Double,
    /** Frames scored under *both* signs. Zero means there is no evidence either way. */
    val comparedCount: Int,
    val decisive: Boolean,
) {
    val better: HandednessScore get() = scores.minByOrNull { it.meanResidual }!!
}

/**
 * Settles [RigAlignment.handedness] against a burst instead of against an
 * argument.
 *
 * The sign of the rotation between the gyro's device frame and the camera's
 * optical frame is the most error-prone constant in the pipeline, and the value
 * in the code is derived rather than confirmed on hardware. Deriving it means
 * getting three conventions right at once - Android's device frame, OpenCV's
 * camera frame, and which way `SENSOR_ORIENTATION` turns - and being wrong in
 * any one of them inverts the correction, so stabilisation makes shake *worse*.
 *
 * The test is simple once there is a residual to measure: warp the burst both
 * ways and keep whichever leaves the frames agreeing with the anchor. A wrong
 * sign roughly doubles the motion instead of removing it, so when the burst
 * rotated at all the gap is not subtle.
 *
 * **The burst has to be the right burst**, which is the part worth knowing
 * before capturing one. Handedness enters only through a rotation about the
 * optical axis, by `handedness * SENSOR_ORIENTATION`, and rotations about that
 * axis commute with it - so a burst that only *rolls* gives both signs
 * identical homographies and cannot decide anything. Pitch and yaw are what
 * separate them: about the device's X axis, `+90` and `-90` turn a pitch into
 * opposite image-plane rotations. Tilt the phone, do not twist it.
 *
 * [separationPx] reports whether the burst met that condition, so an
 * indecisive result says "this burst cannot tell" rather than picking a sign
 * from noise.
 */
object RigCalibration {

    /** Below this, the two hypotheses land on top of each other. */
    const val MIN_SEPARATION_PX = 2.0

    /** Below this relative gap, the winner is not distinguishable from the loser. */
    const val MIN_MARGIN = 0.02

    /**
     * @param lumaAt each frame's luma. Called once per frame, not once per
     *   hypothesis: both signs are scored in the same pass so a 12.5 MP burst is
     *   read once.
     */
    fun settleHandedness(
        frames: List<FrameMeta>,
        track: MotionTrack,
        intrinsics: CameraIntrinsics,
        rig: RigAlignment,
        crop: CropWindow,
        sync: SyncCalibration = SyncCalibration.IDENTITY,
        targetSamples: Int = 40_000,
        lumaAt: (Int) -> LumaPlane,
    ): HandednessVerdict {
        require(frames.isNotEmpty()) { "Cannot calibrate against an empty burst" }
        // One anchor for both hypotheses. Anchor choice comes from gyro
        // steadiness and does not depend on the rig, but pinning it makes the
        // comparison a comparison of one thing.
        val anchorIndex = AnchorSelector.select(frames, track, sync)

        val plans = listOf(1, -1).associateWith { handedness ->
            BurstAligner.plan(
                frames = frames,
                track = track,
                intrinsics = intrinsics,
                rig = rig.copy(handedness = handedness),
                crop = crop,
                sync = sync,
                anchorIndex = anchorIndex,
            )
        }

        val separationPx = separation(plans.getValue(1), plans.getValue(-1), crop)
        val maxRotation = plans.getValue(1).alignments.maxOf { it.rotationRadians }

        val anchor = lumaAt(anchorIndex)
        val anchorSampling = plans.getValue(1).alignments
            .first { it.frameIndex == anchorIndex }.samplingMatrix
        val residuals = plans.keys.associateWith { LinkedHashMap<Int, Double>() }

        for (frame in frames) {
            if (frame.index == anchorIndex) continue
            // Both hypotheses are scored on the *same* frames or on neither.
            //
            // Scoring each on whatever it happened to keep compares two means
            // over two different bursts: a wrong sign that flings the
            // high-motion frames off the sensor would then be judged only on
            // the calm ones it kept, which are the frames it gets most nearly
            // right, and it can win on a subset while being wrong everywhere.
            val alignments = plans.mapValues { (_, plan) ->
                plan.alignments.first { it.frameIndex == frame.index }
            }
            if (alignments.values.any { !it.usable }) continue

            // Read once, score twice.
            val luma = lumaAt(frame.index)
            for ((handedness, alignment) in alignments) {
                residuals.getValue(handedness)[frame.index] = OpticalRefinement.residual(
                    anchor = anchor,
                    anchorSampling = anchorSampling,
                    frame = luma,
                    frameSampling = alignment.samplingMatrix,
                    crop = crop,
                    targetSamples = targetSamples,
                )
            }
        }

        val comparedCount = residuals.getValue(1).size
        val scores = plans.keys.map { handedness ->
            val perFrame = residuals.getValue(handedness)
            HandednessScore(
                handedness = handedness,
                meanResidual = if (perFrame.isEmpty()) Double.MAX_VALUE
                else perFrame.values.average(),
                perFrame = perFrame,
                usableCount = plans.getValue(handedness).usableCount,
            )
        }.sortedBy { it.meanResidual }

        // No frame survived under both signs, so there is no evidence at all.
        // The previous arithmetic turned that into a margin of 1.0 and a
        // confident verdict, which is the worst available answer: it would be
        // written into the manifest of every burst captured afterwards.
        val margin = when {
            comparedCount == 0 -> 0.0
            scores[0].meanResidual > 0.0 ->
                (scores[1].meanResidual - scores[0].meanResidual) / scores[0].meanResidual
            scores[1].meanResidual > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }

        return HandednessVerdict(
            chosen = rig.copy(handedness = scores[0].handedness),
            scores = scores,
            margin = margin,
            separationPx = separationPx,
            maxRotationRadians = maxRotation,
            comparedCount = comparedCount,
            decisive = comparedCount > 0 &&
                separationPx >= MIN_SEPARATION_PX &&
                margin >= MIN_MARGIN,
        )
    }

    /** The largest distance the two hypotheses put between the same crop corner. */
    private fun separation(a: AlignmentPlan, b: AlignmentPlan, crop: CropWindow): Double {
        val corners = listOf(
            0.0 to 0.0,
            (crop.outputWidth - 1).toDouble() to 0.0,
            0.0 to (crop.outputHeight - 1).toDouble(),
            (crop.outputWidth - 1).toDouble() to (crop.outputHeight - 1).toDouble(),
        )
        var worst = 0.0
        for (left in a.alignments) {
            val right = b.alignments.firstOrNull { it.frameIndex == left.frameIndex } ?: continue
            for ((u, v) in corners) {
                val p = left.samplingMatrix.mapPoint(u, v) ?: continue
                val q = right.samplingMatrix.mapPoint(u, v) ?: continue
                val d = sqrt(
                    (p.first - q.first) * (p.first - q.first) +
                        (p.second - q.second) * (p.second - q.second)
                )
                if (d > worst) worst = d
            }
        }
        return worst
    }

    /** A line for a session note, saying what was decided and on what evidence. */
    fun format(verdict: HandednessVerdict): String = buildString {
        appendLine(
            "handedness %+d  (margin %.1f%%, corners %.1f px apart, max rotation %.1f mrad)".format(
                verdict.chosen.handedness,
                verdict.margin * 100.0,
                verdict.separationPx,
                verdict.maxRotationRadians * 1000.0,
            )
        )
        for (score in verdict.scores) {
            appendLine(
                "  %+d  residual %s over %d compared, %d frames kept".format(
                    score.handedness,
                    if (score.meanResidual == Double.MAX_VALUE) "none"
                    else "%.5f".format(score.meanResidual),
                    verdict.comparedCount,
                    score.usableCount,
                )
            )
        }
        if (!verdict.decisive) {
            appendLine(
                when {
                    verdict.comparedCount == 0 ->
                        "  INDECISIVE: no frame survived under both signs, so " +
                            "nothing was compared and the sign above is the one " +
                            "it was handed, not one it chose."
                    verdict.separationPx < MIN_SEPARATION_PX ->
                        "  INDECISIVE: both signs place the frame within " +
                            "%.1f px, so this burst cannot tell them apart. ".format(
                                verdict.separationPx
                            ) + "Capture one with pitch and yaw in it, not roll."
                    else ->
                        "  INDECISIVE: the residuals differ by only %.1f%%.".format(
                            verdict.margin * 100.0
                        )
                }
            )
        }
    }

    /** True when the two hypotheses are far enough apart to be worth scoring. */
    fun canDecide(separationPx: Double): Boolean = abs(separationPx) >= MIN_SEPARATION_PX
}
