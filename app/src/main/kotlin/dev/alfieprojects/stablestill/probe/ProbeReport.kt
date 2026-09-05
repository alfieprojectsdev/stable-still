package dev.alfieprojects.stablestill.probe

import org.json.JSONArray
import org.json.JSONObject

/**
 * How usable the gyroscope is for frame-accurate stabilisation.
 *
 * Spec sheets are not evidence here. Plenty of budget phones list "gyroscope"
 * when what they expose is a low-rate fusion of accelerometer and magnetometer,
 * which is fine for screen rotation and useless for warping a 20 ms exposure.
 * The only trustworthy answer comes from measuring delivery rate on the device.
 */
enum class GyroGrade {
    /** >= 180 Hz. Full gyro-driven pipeline, rolling-shutter correction included. */
    HARDWARE_FAST,

    /** >= 100 Hz. Gyro warp is worth doing; per-row correction gets coarse. */
    HARDWARE_ADEQUATE,

    /** >= 50 Hz. Use the gyro only as a coarse prior for the optical refiner. */
    MARGINAL,

    /** Present but too slow or too quantised to trust. Optical alignment only. */
    UNUSABLE,

    /** No gyroscope on this device at all. Optical alignment only. */
    ABSENT;

    val supportsGyroWarp: Boolean
        get() = this == HARDWARE_FAST || this == HARDWARE_ADEQUATE || this == MARGINAL

    val supportsPerRowCorrection: Boolean
        get() = this == HARDWARE_FAST
}

/** Whether camera and sensor timestamps can be compared without calibration. */
enum class ClockRelationship {
    /** `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`: shared clock, zero offset. */
    SHARED_REALTIME,

    /** `UNKNOWN`: same monotonic family in practice, but the offset must be solved for. */
    NEEDS_CALIBRATION,

    /** Could not be determined. */
    UNKNOWN,
}

data class GyroProbe(
    val present: Boolean,
    val name: String?,
    val vendor: String?,
    val resolutionRadPerSec: Float,
    val maxRangeRadPerSec: Float,
    val reportedMinDelayMicros: Int,
    /** Rate actually observed, which is the number that matters. */
    val measuredRateHz: Double,
    /** Standard deviation of the interval between samples, as a fraction of the mean. */
    val intervalJitterFraction: Double,
    /** Noise floor while (hopefully) at rest, rad/s. */
    val restNoiseRadPerSec: Double,
    val sampleCount: Int,
    val grade: GyroGrade,
    val notes: List<String>,
)

data class CameraProbe(
    val cameraId: String,
    val hardwareLevel: String,
    val timestampSource: String,
    val clockRelationship: ClockRelationship,
    val sensorOrientationDegrees: Int,
    val physicalSizeMm: Pair<Float, Float>?,
    val focalLengthsMm: List<Float>,
    val hasOpticalStabilisation: Boolean,
    val supportsManualSensor: Boolean,
    val supportsRaw: Boolean,
    val reportsRollingShutterSkew: Boolean,
    /** YUV_420_888 output sizes paired with the best frame rate each can sustain. */
    val yuvSizes: List<YuvOption>,
    val aeFpsRanges: List<Pair<Int, Int>>,
)

data class YuvOption(val width: Int, val height: Int, val maxFps: Int) {
    val megapixels: Double get() = width.toDouble() * height / 1_000_000.0
}

data class DeviceProbeReport(
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val totalRamMb: Long,
    val glEsVersion: String,
    val gyro: GyroProbe,
    val accelerometerPresent: Boolean,
    val gravitySensorPresent: Boolean,
    val cameras: List<CameraProbe>,
) {
    /** The back camera we would actually shoot with, if there is one. */
    val primaryCamera: CameraProbe? get() = cameras.firstOrNull()

    /**
     * The headline answer: can this device run the gyro pipeline, and if not,
     * what does it fall back to?
     */
    fun verdict(): String {
        val cam = primaryCamera ?: return "No usable camera found."
        val parts = mutableListOf<String>()
        parts += when (gyro.grade) {
            GyroGrade.HARDWARE_FAST ->
                "Gyro pipeline fully supported (${"%.0f".format(gyro.measuredRateHz)} Hz)."
            GyroGrade.HARDWARE_ADEQUATE ->
                "Gyro pipeline supported at ${"%.0f".format(gyro.measuredRateHz)} Hz; " +
                    "per-row rolling-shutter correction will be approximate."
            GyroGrade.MARGINAL ->
                "Gyro is too slow (${"%.0f".format(gyro.measuredRateHz)} Hz) to drive the warp " +
                    "alone; it will seed the optical refiner instead."
            GyroGrade.UNUSABLE, GyroGrade.ABSENT ->
                "No usable gyroscope. Falling back to optical (image-based) alignment."
        }
        parts += when (cam.clockRelationship) {
            ClockRelationship.SHARED_REALTIME ->
                "Camera and sensor clocks are shared, so no sync calibration is needed."
            ClockRelationship.NEEDS_CALIBRATION ->
                "Camera timestamp source is UNKNOWN; a one-off sync calibration is required."
            ClockRelationship.UNKNOWN ->
                "Camera timestamp source could not be read; assume calibration is required."
        }
        if (!cam.reportsRollingShutterSkew) {
            parts += "Rolling-shutter skew is not reported; it will have to be estimated."
        }
        if (!cam.hasOpticalStabilisation) {
            parts += "No OIS, as expected - software stabilisation is the only option."
        }
        return parts.joinToString(" ")
    }

    /**
     * Frames we can hold in the ring buffer without risking an OOM.
     *
     * A YUV_420_888 frame costs 1.5 bytes per pixel. At 12 MP that is ~18 MB, so
     * a naive 10-frame buffer is 180 MB - more than the whole heap on a 4 GB
     * device. This budgets a quarter of total RAM and clamps hard.
     */
    fun recommendedStackDepth(option: YuvOption): Int {
        val bytesPerFrame = option.width.toLong() * option.height * 3 / 2
        val budgetBytes = totalRamMb * 1024L * 1024L / 4
        return (budgetBytes / bytesPerFrame).toInt().coerceIn(3, 12)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("model", model)
        put("device", device)
        put("androidRelease", androidRelease)
        put("sdkInt", sdkInt)
        put("totalRamMb", totalRamMb)
        put("glEsVersion", glEsVersion)
        put("verdict", verdict())
        put("accelerometerPresent", accelerometerPresent)
        put("gravitySensorPresent", gravitySensorPresent)
        put("gyro", JSONObject().apply {
            put("present", gyro.present)
            put("name", gyro.name ?: JSONObject.NULL)
            put("vendor", gyro.vendor ?: JSONObject.NULL)
            put("grade", gyro.grade.name)
            put("measuredRateHz", gyro.measuredRateHz)
            put("intervalJitterFraction", gyro.intervalJitterFraction)
            put("restNoiseRadPerSec", gyro.restNoiseRadPerSec)
            put("resolutionRadPerSec", gyro.resolutionRadPerSec.toDouble())
            put("maxRangeRadPerSec", gyro.maxRangeRadPerSec.toDouble())
            put("reportedMinDelayMicros", gyro.reportedMinDelayMicros)
            put("sampleCount", gyro.sampleCount)
            put("notes", JSONArray(gyro.notes))
        })
        put("cameras", JSONArray().apply {
            cameras.forEach { cam ->
                put(JSONObject().apply {
                    put("cameraId", cam.cameraId)
                    put("hardwareLevel", cam.hardwareLevel)
                    put("timestampSource", cam.timestampSource)
                    put("clockRelationship", cam.clockRelationship.name)
                    put("sensorOrientationDegrees", cam.sensorOrientationDegrees)
                    put("physicalSizeMm", cam.physicalSizeMm?.let { "${it.first}x${it.second}" }
                        ?: JSONObject.NULL)
                    put("focalLengthsMm", JSONArray(cam.focalLengthsMm.map { it.toDouble() }))
                    put("hasOpticalStabilisation", cam.hasOpticalStabilisation)
                    put("supportsManualSensor", cam.supportsManualSensor)
                    put("supportsRaw", cam.supportsRaw)
                    put("reportsRollingShutterSkew", cam.reportsRollingShutterSkew)
                    put("yuvSizes", JSONArray().apply {
                        cam.yuvSizes.forEach { s ->
                            put(JSONObject().apply {
                                put("width", s.width)
                                put("height", s.height)
                                put("maxFps", s.maxFps)
                            })
                        }
                    })
                    put("aeFpsRanges", JSONArray(cam.aeFpsRanges.map { "${it.first}-${it.second}" }))
                })
            }
        })
    }
}
