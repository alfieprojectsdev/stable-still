package dev.alfieprojects.stablestill.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import dev.alfieprojects.stablestill.core.CameraIntrinsics
import dev.alfieprojects.stablestill.core.FrameMeta
import dev.alfieprojects.stablestill.core.RigAlignment
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Continuous Camera2 capture into a ring buffer, with per-frame timing metadata.
 *
 * Deliberately not CameraX. CameraX's ImageCapture hides exactly what this
 * pipeline needs: the per-frame `SENSOR_TIMESTAMP`, exposure duration, and
 * rolling-shutter skew that let a gyro trace be matched to a pixel grid.
 *
 * Images and capture results arrive on different callbacks with no ordering
 * guarantee, so both are parked in maps keyed by sensor timestamp and paired as
 * the second half shows up.
 */
class CaptureEngine(
    private val context: Context,
    private val ringCapacity: Int,
) {
    companion object {
        private const val TAG = "CaptureEngine"

        /** Unmatched halves older than this are assumed lost and released. */
        private const val ORPHAN_TIMEOUT_NANOS = 1_500_000_000L
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val ringBuffer = FrameRingBuffer(ringCapacity)

    private var cameraCharacteristics: CameraCharacteristics? = null
    private var targetFps: Int? = null

    /**
     * Longest exposure a frame may use, or null to leave auto-exposure alone.
     *
     * Capping this is not a preference. Left to itself AE spends the entire
     * frame period on one exposure - 50 ms at 20 fps on this device - and the
     * blur that buys is baked into the anchor, where no amount of alignment
     * removes it. Stacking exists to trade noise for sharpness; an exposure that
     * long has already spent the sharpness.
     */
    private var maxExposureNanos: Long? = null

    /** Set once AE has metered the scene and manual values have been applied. */
    @Volatile
    private var exposureLocked = false

    /** What the cap actually resolved to, for the UI and the burst notes. */
    @Volatile
    var appliedExposureNanos: Long = 0L
        private set

    @Volatile
    var appliedIso: Int = 0
        private set
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val pendingImages = ConcurrentHashMap<Long, android.media.Image>()
    private val pendingResults = ConcurrentHashMap<Long, FrameMeta>()

    var cameraId: String? = null
        private set
    var intrinsics: CameraIntrinsics? = null
        private set
    var rig: RigAlignment? = null
        private set

    /** Frames delivered since [start], for a live "is this actually running" readout. */
    @Volatile
    var deliveredFrames: Long = 0L
        private set

    @SuppressLint("MissingPermission")
    suspend fun start(
        preview: Surface?,
        requestedSize: Size,
        targetFps: Int? = null,
        maxExposureNanos: Long? = null,
    ) {
        val id = selectBackCamera() ?: error("No back-facing camera available")
        cameraId = id
        val characteristics = cameraManager.getCameraCharacteristics(id)
        cameraCharacteristics = characteristics
        this.targetFps = targetFps
        this.maxExposureNanos = maxExposureNanos
        exposureLocked = false

        val t = HandlerThread("capture-engine").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val reader = ImageReader.newInstance(
            requestedSize.width,
            requestedSize.height,
            ImageFormat.YUV_420_888,
            // Headroom above the ring capacity. Without spare slots the reader
            // runs dry the instant a burst is taken and the preview freezes.
            ringCapacity + 3,
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            pendingImages[image.timestamp] = image
            tryPair(image.timestamp)
            reapOrphans(image.timestamp)
        }, h)

        intrinsics = buildIntrinsics(characteristics, requestedSize)
        rig = RigAlignment(
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            frontFacing = false,
        )

        cameraDevice = openCamera(id, h)
        val targets = listOfNotNull(preview, reader.surface)
        session = createSession(cameraDevice!!, targets, h)
        startRepeating(preview, reader.surface)
    }

    private fun startRepeating(preview: Surface?, readerSurface: Surface) {
        val device = cameraDevice ?: return
        val s = session ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            preview?.let { addTarget(it) }
            addTarget(readerSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // Any in-camera denoise or edge enhancement fights the stack: it
            // invents detail per frame that then fails to average away.
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            // Pin the frame rate if the HAL offers a fixed range. Left to its own
            // devices, AE widens the exposure in dim light and drops the rate to
            // the bottom of a range like 5-30, which stretches the burst window
            // without saying so - the burst still looks fine, it just spans four
            // times as much hand motion as intended.
            fixedFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        s.setRepeatingRequest(builder.build(), captureCallback, handler)
    }

    /**
     * The `[fps, fps]` range if the device advertises it, otherwise null.
     *
     * Only an exact fixed range is accepted. Asking for `[20, 20]` when the HAL
     * lists `[5, 30]` is rejected outright by some devices and silently ignored
     * by others, and a request that is ignored is worse than one not made.
     */
    private fun fixedFpsRange(): android.util.Range<Int>? {
        val fps = targetFps ?: return null
        val available = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return available.firstOrNull { it.lower == fps && it.upper == fps }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val reader = imageReader ?: return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            pendingResults[timestamp] = FrameMeta(
                index = 0, // assigned when the burst is taken
                sensorTimestampNanos = timestamp,
                exposureTimeNanos = exposure,
                // Some HALs under-declare this key and deliver it anyway, so read
                // it unconditionally rather than trusting availableCaptureResultKeys.
                rollingShutterSkewNanos =
                    result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L,
                width = reader.width,
                height = reader.height,
                sensitivityIso = iso,
            )
            appliedExposureNanos = exposure
            appliedIso = iso
            maybeLockExposure(exposure, iso)
            tryPair(timestamp)
        }
    }

    /**
     * Once AE has settled, replace it with the same exposure value clamped to
     * the cap, trading the lost light for gain.
     *
     * Metering is left to AE rather than reinvented: AE is good at deciding how
     * much light a scene needs, and bad only at deciding how long to spend
     * collecting it. So this waits for its answer, keeps the total, and
     * redistributes it - `iso * exposure / cap` is the same exposure-times-gain
     * product, moved off the axis that costs sharpness.
     *
     * Applied once. Re-locking on every frame would fight AE's own convergence
     * and produce a burst whose frames differ in brightness, which is precisely
     * what the merge cannot absorb.
     */
    private fun maybeLockExposure(exposure: Long, iso: Int) {
        val cap = maxExposureNanos ?: return
        if (exposureLocked || exposure <= 0L || iso <= 0) return
        if (exposure <= cap) return

        val c = cameraCharacteristics ?: return
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (isoRange == null || exposureRange == null) {
            Log.w(TAG, "No manual exposure range; leaving AE alone")
            exposureLocked = true
            return
        }

        val targetExposure = cap.coerceIn(exposureRange.lower, exposureRange.upper)
        val scaled = (iso.toLong() * exposure / targetExposure).toInt()
        val targetIso = scaled.coerceIn(isoRange.lower, isoRange.upper)
        exposureLocked = true

        // Off the capture callback's thread: reconfiguring the repeating request
        // from inside a result callback deadlocks on some HALs.
        handler?.post {
            runCatching { applyManualExposure(targetExposure, targetIso) }
                .onFailure { Log.w(TAG, "Could not lock exposure: ${it.message}") }
        }
        if (targetIso < scaled) {
            Log.w(
                TAG,
                "Exposure capped at ${targetExposure / 1_000_000} ms but ISO clamped to " +
                    "$targetIso of $scaled needed; frames will be darker than AE intended",
            )
        }
    }

    private fun applyManualExposure(exposureNanos: Long, iso: Int) {
        val device = cameraDevice ?: return
        val s = session ?: return
        val reader = imageReader ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            fixedFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        s.setRepeatingRequest(builder.build(), captureCallback, handler)
        Log.i(TAG, "Locked exposure to ${exposureNanos / 1_000_000} ms at ISO $iso")
    }

    private fun tryPair(timestamp: Long) {
        val image = pendingImages[timestamp] ?: return
        val meta = pendingResults[timestamp] ?: return
        pendingImages.remove(timestamp)
        pendingResults.remove(timestamp)
        deliveredFrames++
        ringBuffer.add(CapturedFrame(image, meta))
    }

    /**
     * Releases halves that will never find their partner.
     *
     * A dropped result leaves an Image pinned forever, and a handful of those is
     * enough to exhaust the reader pool and stall capture with no visible error.
     */
    private fun reapOrphans(nowNanos: Long) {
        pendingImages.entries.removeAll { (ts, image) ->
            (nowNanos - ts > ORPHAN_TIMEOUT_NANOS).also { stale ->
                if (stale) {
                    Log.w(TAG, "Releasing image with no capture result at $ts")
                    runCatching { image.close() }
                }
            }
        }
        pendingResults.entries.removeAll { (ts, _) -> nowNanos - ts > ORPHAN_TIMEOUT_NANOS }
    }

    /** Takes the burst around [shutterNanos]; the caller owns and must close the frames. */
    fun takeBurst(count: Int, shutterNanos: Long): List<CapturedFrame> =
        ringBuffer.takeBurst(count, shutterNanos)

    fun stop() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        ringBuffer.clear()
        pendingImages.values.forEach { runCatching { it.close() } }
        pendingImages.clear()
        pendingResults.clear()
        runCatching { imageReader?.close() }
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------------ helpers

    private fun selectBackCamera(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private fun buildIntrinsics(
        c: CameraCharacteristics,
        size: Size,
    ): CameraIntrinsics {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return if (focal != null && physical != null && focal > 0f) {
            CameraIntrinsics.fromPhysical(
                focalLengthMm = focal.toDouble(),
                sensorWidthMm = physical.width.toDouble(),
                sensorHeightMm = physical.height.toDouble(),
                imageWidth = size.width,
                imageHeight = size.height,
            )
        } else {
            // A wrong focal length shows up as under- or over-correction, not as
            // a crash, so an approximate fallback is better than refusing to run.
            Log.w(TAG, "Falling back to an assumed 78 degree horizontal field of view")
            CameraIntrinsics.fromHorizontalFov(78.0, size.width, size.height)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (cont.isActive) cont.resume(device) else device.close()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Camera error $error"))
                    }
                }
            }, handler)
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (cont.isActive) cont.resume(s)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Capture session configuration failed"))
                }
            }
        }, handler)
    }
}
