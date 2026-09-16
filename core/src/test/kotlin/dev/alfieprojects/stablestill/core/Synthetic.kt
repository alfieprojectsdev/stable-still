package dev.alfieprojects.stablestill.core

/** Helpers for building synthetic bursts, so tests describe motion not plumbing. */
object Synthetic {

    const val MS: Long = 1_000_000L

    /** Gyro samples at [rateHz] over [durationMs] with a caller-supplied rate profile. */
    fun gyro(
        startNanos: Long,
        durationMs: Long,
        rateHz: Int,
        omegaAt: (tSeconds: Double) -> Vec3,
    ): List<GyroSample> {
        val periodNanos = (1_000_000_000L / rateHz)
        val count = ((durationMs * MS) / periodNanos).toInt() + 1
        return (0 until count).map { i ->
            val tNanos = startNanos + i * periodNanos
            GyroSample(tNanos, omegaAt((tNanos - startNanos) * 1e-9))
        }
    }

    fun constantRate(startNanos: Long, durationMs: Long, rateHz: Int, omega: Vec3) =
        gyro(startNanos, durationMs, rateHz) { omega }

    /** A burst of [count] frames at [intervalMs], each with [exposureMs] exposure. */
    fun frames(
        startNanos: Long,
        count: Int,
        intervalMs: Long,
        exposureMs: Long = 8,
        skewMs: Long = 0,
        width: Int = 4000,
        height: Int = 3000,
    ): List<FrameMeta> = (0 until count).map { i ->
        FrameMeta(
            index = i,
            sensorTimestampNanos = startNanos + i * intervalMs * MS,
            exposureTimeNanos = exposureMs * MS,
            rollingShutterSkewNanos = skewMs * MS,
            width = width,
            height = height,
        )
    }

    /**
     * A neutral-grey frame whose luminance is whatever [lumaAt] says.
     *
     * Chroma sits at 128, so R = G = B = luma. That keeps a test's expectations
     * arithmetic rather than colorimetric: a luma of 200 is an RGB of 200, and a
     * difference in luma is the same difference the merge weights on.
     */
    fun greyFrame(width: Int, height: Int, lumaAt: (x: Int, y: Int) -> Int): YuvFrame {
        val y = ByteArray(width * height)
        for (py in 0 until height) {
            for (px in 0 until width) {
                y[py * width + px] = lumaAt(px, py).coerceIn(0, 255).toByte()
            }
        }
        val chroma = ByteArray(((width + 1) / 2) * ((height + 1) / 2)) { 128.toByte() }
        return YuvFrame(width, height, y, chroma, chroma.copyOf())
    }

    /**
     * A plan that translates each frame by a fixed offset, bypassing the gyro.
     *
     * Alignment has its own tests. What the merge needs is a plan whose answer is
     * known exactly, so a failure means the merge is wrong rather than that the
     * integration drifted.
     *
     * @param shifts per frame, the extra source-pixel offset on top of the crop.
     */
    fun translationPlan(
        crop: CropWindow,
        anchorIndex: Int,
        shifts: List<Pair<Double, Double>>,
    ): AlignmentPlan = AlignmentPlan(
        anchorIndex = anchorIndex,
        crop = crop,
        alignments = shifts.mapIndexed { index, (dx, dy) ->
            FrameAlignment(
                frameIndex = index,
                samplingMatrix = Mat3(
                    1.0, 0.0, crop.offsetX + dx,
                    0.0, 1.0, crop.offsetY + dy,
                    0.0, 0.0, 1.0,
                ),
                usable = true,
                maxCornerShiftPx = kotlin.math.hypot(dx, dy),
                rotationRadians = 0.0,
            )
        },
    )
}
