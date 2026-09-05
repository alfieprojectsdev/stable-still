package dev.alfieprojects.stablestill.probe

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Phase 0 of the project: find out what this phone can actually do, before a
 * single line of shader code is written against assumptions it does not meet.
 *
 * Everything here is read at runtime. Nothing is inferred from the model name,
 * because two devices sold as the same model routinely ship different sensors.
 */
class DeviceProbe(private val context: Context) {

    /**
     * Runs the full probe. Suspends for [gyroSampleMillis] while measuring the
     * gyroscope, so call it off the main thread.
     *
     * Ask the user to rest the phone on a table first: the noise-floor figure is
     * only meaningful at rest, and the rate measurement is cleaner too.
     */
    suspend fun run(gyroSampleMillis: Long = 1_500L): DeviceProbeReport {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val glEs = activityManager.deviceConfigurationInfo.glEsVersion

        return DeviceProbeReport(
            model = Build.MODEL,
            device = "${Build.MANUFACTURER} ${Build.DEVICE}",
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            totalRamMb = memInfo.totalMem / (1024 * 1024),
            glEsVersion = glEs,
            gyro = probeGyro(sensorManager, gyroSampleMillis),
            accelerometerPresent = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            gravitySensorPresent = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null,
            cameras = probeCameras(),
        )
    }

    // ---------------------------------------------------------------- gyroscope

    private suspend fun probeGyro(
        sensorManager: SensorManager,
        durationMillis: Long,
    ): GyroProbe {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: return GyroProbe(
                present = false,
                name = null,
                vendor = null,
                resolutionRadPerSec = 0f,
                maxRangeRadPerSec = 0f,
                reportedMinDelayMicros = 0,
                measuredRateHz = 0.0,
                intervalJitterFraction = 0.0,
                restNoiseRadPerSec = 0.0,
                restBiasRadPerSec = 0.0,
                restBiasAxesRadPerSec = listOf(0.0, 0.0, 0.0),
                sampleCount = 0,
                grade = GyroGrade.ABSENT,
                notes = listOf("TYPE_GYROSCOPE is not available on this device."),
            )

        val timestamps = ArrayList<Long>(1024)
        val magnitudes = ArrayList<Double>(1024)

        // Running per-axis sums, because the quantity that actually limits
        // alignment is the mean *vector* and the magnitudes cannot recover it:
        // |w| is non-negative, so averaging it folds noise into the result and
        // reports an offset even for a perfectly unbiased sensor at rest.
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0

        val thread = HandlerThread("probe-gyro").apply { start() }
        try {
            val handler = Handler(thread.looper)
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        timestamps += event.timestamp
                        val x = event.values[0].toDouble()
                        val y = event.values[1].toDouble()
                        val z = event.values[2].toDouble()
                        magnitudes += sqrt(x * x + y * y + z * z)
                        sumX += x
                        sumY += y
                        sumZ += z
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(
                    listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler
                )
                handler.postDelayed({
                    sensorManager.unregisterListener(listener)
                    if (cont.isActive) cont.resume(Unit)
                }, durationMillis)
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        } finally {
            thread.quitSafely()
        }

        val notes = mutableListOf<String>()

        // Measured rate, not the advertised one. minDelay is a best case the HAL
        // is under no obligation to actually deliver.
        val spanNanos = if (timestamps.size >= 2) timestamps.last() - timestamps.first() else 0L
        val rateHz = if (spanNanos > 0) {
            (timestamps.size - 1) * 1e9 / spanNanos
        } else {
            0.0
        }

        val intervals = timestamps.zipWithNext { a, b -> (b - a).toDouble() }
        val meanInterval = intervals.average().takeIf { intervals.isNotEmpty() && it > 0 } ?: 0.0
        val jitter = if (meanInterval > 0) {
            val variance = intervals.sumOf { (it - meanInterval) * (it - meanInterval) } / intervals.size
            sqrt(variance) / meanInterval
        } else {
            0.0
        }

        val meanMag = magnitudes.average().takeIf { magnitudes.isNotEmpty() } ?: 0.0
        val noise = if (magnitudes.size > 1) {
            sqrt(magnitudes.sumOf { (it - meanMag) * (it - meanMag) } / magnitudes.size)
        } else {
            0.0
        }

        // Zero-rate offset. Noise averages away over a burst; this does not - it
        // integrates straight into drift, so it is the figure that decides how
        // far alignment can be trusted across a 550 ms window.
        val sampleCount = magnitudes.size
        val biasAxes = if (sampleCount > 0) {
            listOf(sumX / sampleCount, sumY / sampleCount, sumZ / sampleCount)
        } else {
            listOf(0.0, 0.0, 0.0)
        }
        val bias = sqrt(biasAxes.sumOf { it * it })

        val advertisedHz = if (sensor.minDelay > 0) 1_000_000.0 / sensor.minDelay else 0.0
        if (advertisedHz > 0 && rateHz < advertisedHz * 0.6) {
            notes += "Delivering %.0f Hz against an advertised %.0f Hz.".format(rateHz, advertisedHz)
        }
        if (sensor.minDelay <= 0) {
            notes += "minDelay is ${sensor.minDelay}: reports as a non-continuous sensor, " +
                "which a real MEMS gyro should never do."
        }
        if (jitter > 0.5) {
            notes += "Sample intervals are very irregular (jitter %.0f%%), so timestamps ".format(jitter * 100) +
                "may be assigned on delivery rather than at sampling."
        }
        val lowered = "${sensor.name} ${sensor.vendor}".lowercase()
        if (listOf("virtual", "software", "fusion", "synthetic").any { it in lowered }) {
            notes += "Sensor name/vendor suggests a software-derived gyroscope."
        }
        if (meanMag > 0.2) {
            notes += "Device was moving during the probe; neither the noise floor nor the " +
                "zero-rate offset is a rest measurement."
        } else if (bias > 0.001) {
            // 0.001 rad/s integrates to 0.25 mrad over the 250 ms between an anchor
            // and the end of a burst, which is about a pixel at this camera's focal
            // length. Below that the offset is not worth mentioning.
            notes += "Zero-rate offset is %.4f rad/s, drifting %.2f mrad over 250 ms. ".format(
                bias, bias * 0.25 * 1000
            ) + "Subtract it before integrating the track."
        }

        val grade = when {
            rateHz >= 180 -> GyroGrade.HARDWARE_FAST
            rateHz >= 100 -> GyroGrade.HARDWARE_ADEQUATE
            rateHz >= 50 -> GyroGrade.MARGINAL
            else -> GyroGrade.UNUSABLE
        }.let { g ->
            // A gyro whose timestamps are unreliable cannot be integrated against
            // frame times no matter how fast it claims to be.
            if (jitter > 1.0) GyroGrade.UNUSABLE else g
        }

        return GyroProbe(
            present = true,
            name = sensor.name,
            vendor = sensor.vendor,
            resolutionRadPerSec = sensor.resolution,
            maxRangeRadPerSec = sensor.maximumRange,
            reportedMinDelayMicros = sensor.minDelay,
            measuredRateHz = rateHz,
            intervalJitterFraction = jitter,
            restNoiseRadPerSec = noise,
            restBiasRadPerSec = bias,
            restBiasAxesRadPerSec = biasAxes,
            sampleCount = timestamps.size,
            grade = grade,
            notes = notes,
        )
    }

    // ------------------------------------------------------------------- camera

    private fun probeCameras(): List<CameraProbe> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            manager.cameraIdList
                .mapNotNull { id -> runCatching { probeCamera(manager, id) }.getOrNull() }
                // Back cameras first: that is what we shoot stills with.
                .sortedBy { it.sensorOrientationDegrees == 270 }
        }.getOrDefault(emptyList())
    }

    private fun probeCamera(manager: CameraManager, id: String): CameraProbe? {
        val c = manager.getCameraCharacteristics(id)
        val facing = c.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return null

        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        val tsSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val tsName = when (tsSource) {
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            else -> "UNSPECIFIED"
        }
        val clock = when (tsSource) {
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> ClockRelationship.SHARED_REALTIME
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> ClockRelationship.NEEDS_CALIBRATION
            else -> ClockRelationship.UNKNOWN
        }

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?: IntArray(0)

        val reportsSkew = c.availableCaptureResultKeys.any {
            it == CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW
        }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuv = map?.let { yuvOptions(it) } ?: emptyList()

        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower to it.upper } ?: emptyList()

        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        return CameraProbe(
            cameraId = id,
            hardwareLevel = level,
            timestampSource = tsName,
            clockRelationship = clock,
            sensorOrientationDegrees = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            physicalSizeMm = size?.let { it.width to it.height },
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList() ?: emptyList(),
            hasOpticalStabilisation = oisModes.any {
                it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            },
            supportsManualSensor = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ),
            supportsRaw = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
            ),
            reportsRollingShutterSkew = reportsSkew,
            yuvSizes = yuv,
            aeFpsRanges = fpsRanges,
        )
    }

    private fun yuvOptions(map: StreamConfigurationMap): List<YuvOption> =
        (map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray())
            .map { size ->
                val minDurationNanos = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
                val fps = if (minDurationNanos > 0) (1_000_000_000.0 / minDurationNanos).toInt() else 0
                YuvOption(size.width, size.height, fps)
            }
            .sortedByDescending { it.width.toLong() * it.height }
}
