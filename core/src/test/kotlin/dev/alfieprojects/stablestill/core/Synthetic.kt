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
}
