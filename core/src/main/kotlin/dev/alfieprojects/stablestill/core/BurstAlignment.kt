package dev.alfieprojects.stablestill.core

/** Where a single frame lands once the gyro has had its say. */
data class FrameAlignment(
    val frameIndex: Int,
    /**
     * Maps an *output* (cropped) pixel to the pixel to sample in this frame's
     * source image. This is the matrix the fragment shader wants: it runs
     * backwards, from destination to source, so every output pixel is defined.
     */
    val samplingMatrix: Mat3,
    val usable: Boolean,
    /** Worst-case corner displacement in source pixels, for diagnostics. */
    val maxCornerShiftPx: Double,
    /** Rotation magnitude relative to the anchor, radians. */
    val rotationRadians: Double,
)

/** The full recipe for merging one burst into one still. */
data class AlignmentPlan(
    val anchorIndex: Int,
    val crop: CropWindow,
    val alignments: List<FrameAlignment>,
) {
    val usableFrames: List<FrameAlignment> get() = alignments.filter { it.usable }
    val usableCount: Int get() = alignments.count { it.usable }
}

/**
 * Turns "a pile of frames plus a gyro trace" into "where each frame goes".
 *
 * The model is a pure-rotation homography, `H = K R K^-1`. That is exact for a
 * camera that only rotates, and rotation is what hand tremor overwhelmingly is:
 * a few milliradians of pitch/yaw/roll, with translation small enough that its
 * parallax is sub-pixel beyond a couple of metres. Close-up subjects break that
 * assumption, which is precisely the case the optical refinement pass exists to
 * clean up.
 */
object BurstAligner {

    /**
     * @param frames capture metadata, any order.
     * @param track integrated gyro orientation covering the burst.
     * @param sync camera-to-gyro clock offset; see [SyncCalibration].
     * @param maxRotationRadians frames rotated further than this from the anchor
     *   are dropped rather than stretched into mush.
     */
    fun plan(
        frames: List<FrameMeta>,
        track: MotionTrack,
        intrinsics: CameraIntrinsics,
        rig: RigAlignment,
        crop: CropWindow,
        sync: SyncCalibration = SyncCalibration.IDENTITY,
        anchorIndex: Int = AnchorSelector.select(frames, track, sync),
        maxRotationRadians: Double = 0.10,
        extraRotation: Mat3 = Mat3.IDENTITY,
    ): AlignmentPlan {
        require(frames.isNotEmpty()) { "Cannot align an empty burst" }
        val anchor = frames.firstOrNull { it.index == anchorIndex }
            ?: frames.minByOrNull { it.index }!!
        val anchorTime = sync.toGyroClock(anchor.midExposureNanos)

        val alignments = frames.map { frame ->
            val frameTime = sync.toGyroClock(frame.midExposureNanos)
            // Rotation carrying an anchor-time ray into this frame's orientation:
            // exactly the direction a destination-to-source lookup needs.
            val deviceRotation = track.rotationBetween(anchorTime, frameTime)
            val rotationCam = rig.toCameraFrame(deviceRotation)
            val h = intrinsics.matrix * (rotationCam * extraRotation) * intrinsics.inverse
            val sampling = h * crop.outputToAnchor

            val shift = maxCornerShift(sampling, crop)
            val inBounds = cornersInside(sampling, crop, frame)
            val angle = deviceRotation.angle()

            FrameAlignment(
                frameIndex = frame.index,
                samplingMatrix = sampling,
                usable = inBounds && angle <= maxRotationRadians,
                maxCornerShiftPx = shift,
                rotationRadians = angle,
            )
        }
        return AlignmentPlan(anchor.index, crop, alignments)
    }

    private fun outputCorners(crop: CropWindow): List<Pair<Double, Double>> = listOf(
        0.0 to 0.0,
        (crop.outputWidth - 1).toDouble() to 0.0,
        0.0 to (crop.outputHeight - 1).toDouble(),
        (crop.outputWidth - 1).toDouble() to (crop.outputHeight - 1).toDouble(),
    )

    private fun maxCornerShift(sampling: Mat3, crop: CropWindow): Double {
        var worst = 0.0
        for ((u, v) in outputCorners(crop)) {
            val mapped = sampling.mapPoint(u, v) ?: return Double.MAX_VALUE
            val ref = crop.outputToAnchor.mapPoint(u, v)!!
            val dx = mapped.first - ref.first
            val dy = mapped.second - ref.second
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d > worst) worst = d
        }
        return worst
    }

    private fun cornersInside(sampling: Mat3, crop: CropWindow, frame: FrameMeta): Boolean {
        for ((u, v) in outputCorners(crop)) {
            val mapped = sampling.mapPoint(u, v) ?: return false
            if (mapped.first < 0.0 || mapped.first > frame.width - 1.0) return false
            if (mapped.second < 0.0 || mapped.second > frame.height - 1.0) return false
        }
        return true
    }
}

/**
 * Chooses which frame the others are aligned onto.
 *
 * Not the frame at the shutter press - the *steadiest* frame in the window. Its
 * own motion blur is baked into the result and cannot be undone by any amount of
 * alignment, so picking the calmest frame is the cheapest sharpness win in the
 * whole pipeline.
 */
object AnchorSelector {
    fun select(
        frames: List<FrameMeta>,
        track: MotionTrack,
        sync: SyncCalibration = SyncCalibration.IDENTITY,
    ): Int {
        require(frames.isNotEmpty()) { "Cannot select an anchor from an empty burst" }
        return frames.minByOrNull { frame ->
            val start = sync.toGyroClock(frame.sensorTimestampNanos)
            val end = sync.toGyroClock(
                frame.sensorTimestampNanos + frame.exposureTimeNanos + frame.rollingShutterSkewNanos
            )
            track.meanAngularSpeed(start, end)
        }!!.index
    }

    /** Per-frame steadiness scores in rad/s, lowest is sharpest. Diagnostics only. */
    fun scores(
        frames: List<FrameMeta>,
        track: MotionTrack,
        sync: SyncCalibration = SyncCalibration.IDENTITY,
    ): Map<Int, Double> = frames.associate { frame ->
        val start = sync.toGyroClock(frame.sensorTimestampNanos)
        val end = sync.toGyroClock(
            frame.sensorTimestampNanos + frame.exposureTimeNanos + frame.rollingShutterSkewNanos
        )
        frame.index to track.meanAngularSpeed(start, end)
    }
}
